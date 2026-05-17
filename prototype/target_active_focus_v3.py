"""
Active-focus v3 prototype: ROI-led target tracking with pose re-acquire.

Compared with v2, this version treats the image ROI tracker as the target
focus source and treats MediaPipe pose candidates as structured evidence that
can be adopted only when they match the tracked ROI and target appearance.

Example:
  python prototype/target_active_focus_v3.py ^
      --video test_assets/videos/pixel_line_error_1774202137014.mp4 ^
      --label pixel_line_error_black_active_focus_v3 ^
      --start-ms 1900 --tap-x 0.47 --tap-y 0.53 --tracker csrt --num-poses 4
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np

from multi_person_pose import MultiPersonPose
from person_proposals import build_person_proposals, person_bbox_for
from subject_selector import PoseBBox, PoseCandidate
from target_active_focus_v2 import (
    ActiveFocusConfig,
    PartReIdSignature,
    _bbox_center,
    _bbox_iou,
    _center_distance,
    _core_similarity,
    _draw_candidate,
    _draw_person_bbox,
    _draw_predicted_roi,
    _expand_bbox,
    _green_contamination,
    _make_contact_sheet,
    _part_reid_signature,
    _put_header,
    _rect_from_norm,
    _shorts_similarity,
    _tap_core_signature,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VIDEO = PROJECT_ROOT / "test_assets" / "videos" / "pixel_line_error_1774202137014.mp4"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "target_active_focus_v3"


@dataclass
class RoiState:
    bbox: PoseBBox
    tracker_ok: bool
    confidence: float = 1.0
    lost_ms: int = 0


@dataclass
class V3TrackState:
    initialized: bool = False
    roi: Optional[RoiState] = None
    trusted_roi: Optional[RoiState] = None
    tracker_roi: Optional[RoiState] = None
    tracker: object | None = None
    anchor_reid: Optional[PartReIdSignature] = None
    anchor_core: Optional[PartReIdSignature] = None
    last_candidate: Optional[PoseCandidate] = None
    last_person_bbox: Optional[PoseBBox] = None
    last_selected_ms: Optional[int] = None
    hold_frames: int = 0
    lost_since_ms: Optional[int] = None
    track_id: int = 1


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def _norm_to_cv_bbox(bbox: PoseBBox, width: int, height: int) -> Tuple[int, int, int, int]:
    left, top, right, bottom = _rect_from_norm(bbox, width, height)
    return left, top, max(2, right - left), max(2, bottom - top)


def _cv_to_norm_bbox(rect: Tuple[float, float, float, float], width: int, height: int) -> PoseBBox:
    x, y, w, h = rect
    return PoseBBox(
        left=_clamp01(float(x) / max(1, width)),
        top=_clamp01(float(y) / max(1, height)),
        right=_clamp01(float(x + w) / max(1, width)),
        bottom=_clamp01(float(y + h) / max(1, height)),
    )


def _create_tracker(name: str):
    key = name.lower()
    factories = []
    if key == "csrt":
        factories = [("TrackerCSRT_create", cv2), ("TrackerCSRT_create", getattr(cv2, "legacy", None))]
    elif key == "kcf":
        factories = [("TrackerKCF_create", cv2), ("TrackerKCF_create", getattr(cv2, "legacy", None))]
    elif key == "mil":
        factories = [("TrackerMIL_create", cv2), ("TrackerMIL_create", getattr(cv2, "legacy", None))]
    elif key == "mosse":
        factories = [("TrackerMOSSE_create", getattr(cv2, "legacy", None))]
    else:
        raise ValueError(f"Unsupported tracker: {name}")

    for attr, module in factories:
        if module is not None and hasattr(module, attr):
            return getattr(module, attr)()
    raise RuntimeError(f"OpenCV tracker is unavailable: {name}")


def _tracker_init(
    state: V3TrackState,
    frame: np.ndarray,
    bbox: PoseBBox,
    tracker_name: str,
) -> None:
    height, width = frame.shape[:2]
    state.tracker = _create_tracker(tracker_name)
    state.tracker.init(frame, _norm_to_cv_bbox(bbox, width, height))
    roi = RoiState(bbox=bbox, tracker_ok=True, confidence=1.0, lost_ms=0)
    state.roi = roi
    state.trusted_roi = roi
    state.tracker_roi = roi


def _update_roi_from_tracker(
    state: V3TrackState,
    frame: np.ndarray,
    prev_ts_ms: Optional[int],
    ts_ms: int,
) -> float:
    if not state.initialized or state.tracker is None:
        return 0.0
    started = time.perf_counter()
    ok, cv_bbox = state.tracker.update(frame)
    elapsed = time.perf_counter() - started
    height, width = frame.shape[:2]
    dt_ms = max(0, ts_ms - prev_ts_ms) if prev_ts_ms is not None else 0
    if ok:
        tracker_roi = RoiState(
            bbox=_cv_to_norm_bbox(cv_bbox, width, height),
            tracker_ok=True,
            confidence=1.0,
            lost_ms=0,
        )
        state.tracker_roi = tracker_roi
        # The OpenCV tracker is a proposal, not the source of truth. While the
        # selected pose is healthy we let it move the visible ROI; once the
        # pose gate is holding, keep the last trusted ROI to avoid drift onto
        # another player or a background object.
        if state.hold_frames == 0 and state.lost_since_ms is None:
            state.roi = tracker_roi
    elif state.roi is not None:
        fallback = state.trusted_roi or state.roi
        state.tracker_roi = RoiState(
            bbox=fallback.bbox,
            tracker_ok=False,
            confidence=max(0.0, fallback.confidence - 0.12),
            lost_ms=fallback.lost_ms + dt_ms,
        )
        state.roi = fallback
    return elapsed


def _mean_keypoint_score(previous: Optional[PoseCandidate], candidate: PoseCandidate) -> float:
    if previous is None:
        return 0.5
    points = (11, 12, 23, 24, 25, 26, 27, 28)
    distances: List[float] = []
    for idx in points:
        if idx >= previous.landmarks.shape[0] or idx >= candidate.landmarks.shape[0]:
            continue
        if previous.landmarks[idx, 2] < 0.15 or candidate.landmarks[idx, 2] < 0.15:
            continue
        distances.append(
            float(
                math.hypot(
                    previous.landmarks[idx, 0] - candidate.landmarks[idx, 0],
                    previous.landmarks[idx, 1] - candidate.landmarks[idx, 1],
                )
            )
        )
    if not distances:
        return 0.5
    return _clamp01(1.0 - float(np.mean(distances)) / 0.35)


def _score_candidates(
    candidates: List[PoseCandidate],
    signatures: List[PartReIdSignature],
    state: V3TrackState,
    cfg: ActiveFocusConfig,
) -> List[dict]:
    scored: List[dict] = []
    scoring_roi = state.trusted_roi if (state.hold_frames > 0 or state.lost_since_ms is not None) else state.roi
    roi_bbox = scoring_roi.bbox if scoring_roi is not None else None
    for i, candidate in enumerate(candidates):
        candidate_bbox = person_bbox_for(candidate)
        roi_iou = _bbox_iou(roi_bbox, candidate_bbox) if roi_bbox is not None else 0.0
        roi_center = (
            _clamp01(1.0 - _center_distance(roi_bbox, candidate_bbox) / 0.38)
            if roi_bbox is not None
            else 0.0
        )
        reid = state.anchor_reid.similarity(signatures[i]) if state.anchor_reid is not None else 0.5
        core = _core_similarity(state.anchor_core, signatures[i])
        shorts = _shorts_similarity(state.anchor_reid, signatures[i])
        green = _green_contamination(state.anchor_core, signatures[i])
        keypoint = _mean_keypoint_score(state.last_candidate, candidate)
        visibility = _clamp01(candidate.avg_visibility)
        identity = _clamp01(
            0.30 * roi_center +
            0.22 * reid +
            0.16 * roi_iou +
            0.12 * core +
            0.10 * shorts +
            0.06 * keypoint +
            0.04 * visibility -
            0.12 * max(0.0, green - cfg.green_contamination_max)
        )
        scored.append({
            "index": i,
            "identity": identity,
            "roi_iou": roi_iou,
            "roi_center": roi_center,
            "reid": reid,
            "core": core,
            "shorts": shorts,
            "green_contamination": green,
            "keypoint": keypoint,
            "visibility": visibility,
        })
    scored.sort(key=lambda item: item["identity"], reverse=True)
    return scored


def _accept_candidate(best: dict, margin: float, state: V3TrackState, cfg: ActiveFocusConfig) -> Tuple[bool, str]:
    roi_gate = best["roi_iou"] >= 0.08 or best["roi_center"] >= 0.55
    if not roi_gate:
        return False, "roi_mismatch_hold"
    if best["green_contamination"] > cfg.green_contamination_max:
        return False, "subject_color_contaminated"

    # Coming back from hold is where false re-acquire happens in crowded clips.
    # In that mode, require the local body parts to agree, not just ROI overlap.
    reacquiring = state.lost_since_ms is not None or state.hold_frames > 0
    if reacquiring:
        if best["roi_iou"] < 0.20 and best["roi_center"] < 0.78:
            return False, "strict_roi_mismatch_hold"
        if best["reid"] < 0.68:
            return False, "strict_reid_mismatch_hold"
        if best["core"] < 0.68:
            return False, "strict_core_mismatch_hold"
        if best["shorts"] < 0.64:
            return False, "strict_shorts_mismatch_hold"
        if margin < 0.08 and best["identity"] < 0.74:
            return False, "strict_identity_uncertain"
        if best["identity"] < 0.74:
            return False, "strict_identity_hold"
        return True, "pose_reacquired_from_roi"

    if best["reid"] < 0.50 and best["core"] < 0.60:
        return False, "subject_reid_mismatch"
    if best["shorts"] < 0.58 and best["reid"] < 0.62:
        return False, "subject_shorts_mismatch"
    if margin < 0.035 and best["identity"] < 0.68:
        return False, "subject_identity_uncertain"

    if best["identity"] < 0.56:
        return False, "roi_focus_pose_hold"
    return True, "pose_confirmed_by_roi"


def _select_index_from_tap(candidates: List[PoseCandidate], x: float, y: float) -> int:
    containing = [
        (i, person_bbox_for(candidate).area)
        for i, candidate in enumerate(candidates)
        if person_bbox_for(candidate).contains(x, y)
    ]
    if containing:
        return max(containing, key=lambda item: item[1])[0]
    best_i = -1
    best_distance = float("inf")
    for i, candidate in enumerate(candidates):
        cx, cy = _bbox_center(person_bbox_for(candidate))
        distance = math.hypot(cx - x, cy - y)
        if distance < best_distance:
            best_i = i
            best_distance = distance
    return best_i


def _draw_roi_state(frame: np.ndarray, roi: Optional[RoiState], reason: str, hold_frames: int) -> None:
    if roi is None:
        return
    if reason == "target_lost":
        return
    elif "reacquired" in reason or "confirmed" in reason:
        _draw_predicted_roi(frame, roi.bbox, (0, 180, 255), "tracker ROI", dashed=False, thickness=1)
    else:
        _draw_predicted_roi(frame, roi.bbox, (0, 220, 255), f"ROI hold {hold_frames}", dashed=True, thickness=2)


def _run(
    video_path: Path,
    out_dir: Path,
    label: str,
    tracker_name: str,
    start_ms: int,
    tap_x: float,
    tap_y: float,
    every_ms: int,
    max_frames: int,
    num_poses: int,
    reacquire_ms: int,
    min_roi_ok_ratio: float,
) -> dict:
    if not video_path.exists():
        raise FileNotFoundError(video_path)
    out_dir.mkdir(parents=True, exist_ok=True)

    overlay_path = out_dir / f"{label}_active_focus_v3_overlay.mp4"
    trace_path = out_dir / f"{label}_active_focus_v3_trace.json"
    summary_path = out_dir / f"{label}_active_focus_v3_summary.json"
    contact_sheet_path = out_dir / f"{label}_active_focus_v3_contact_sheet.jpg"

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    writer = cv2.VideoWriter(
        str(overlay_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        1000.0 / every_ms,
        (width, height),
    )
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"Cannot create output video: {overlay_path}")

    cfg = ActiveFocusConfig()
    state = V3TrackState()
    trace: List[dict] = []
    frame_idx = 0
    last_emit_ms = -1
    prev_tracker_ts_ms: Optional[int] = None
    detect_seconds = 0.0
    reid_seconds = 0.0
    tracker_seconds = 0.0
    started = time.time()

    with MultiPersonPose(num_poses=num_poses) as pose:
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                ts_ms = int(round(frame_idx / fps * 1000))
                frame_idx += 1

                tracker_seconds += _update_roi_from_tracker(state, frame, prev_tracker_ts_ms, ts_ms)
                prev_tracker_ts_ms = ts_ms

                if ts_ms < start_ms or (ts_ms - last_emit_ms) < every_ms:
                    continue
                if max_frames > 0 and len(trace) >= max_frames:
                    break

                detect_start = time.perf_counter()
                raw_candidates = pose.detect_for_video(frame, ts_ms)
                detect_seconds += time.perf_counter() - detect_start

                proposals, gated_out = build_person_proposals(raw_candidates)
                candidates = [proposal.candidate for proposal in proposals]

                reid_start = time.perf_counter()
                signatures = [_part_reid_signature(frame, candidate) for candidate in candidates]
                reid_seconds += time.perf_counter() - reid_start

                selected_index: Optional[int] = None
                scored: List[dict] = []
                best_row: Optional[dict] = None
                margin: Optional[float] = None
                reason = "no_person_detected" if not candidates else "not_initialized"

                if not state.initialized and candidates:
                    tap_i = _select_index_from_tap(candidates, tap_x, tap_y)
                    if tap_i >= 0:
                        selected_index = tap_i
                        selected = candidates[tap_i]
                        person_bbox = person_bbox_for(selected)
                        _tracker_init(state, frame, _expand_bbox(person_bbox, 1.08, 1.06), tracker_name)
                        state.anchor_reid = signatures[tap_i]
                        state.anchor_core = _tap_core_signature(frame, tap_x, tap_y)
                        state.last_candidate = selected
                        state.last_person_bbox = person_bbox
                        state.last_selected_ms = ts_ms
                        state.hold_frames = 0
                        state.lost_since_ms = None
                        state.initialized = True
                        reason = "manual_target_initialized"
                elif state.initialized:
                    scored = _score_candidates(candidates, signatures, state, cfg)
                    if scored:
                        best_row = scored[0]
                        second = scored[1] if len(scored) > 1 else None
                        margin = best_row["identity"] - second["identity"] if second is not None else 1.0
                        accepted, reason = _accept_candidate(best_row, margin, state, cfg)
                        if accepted:
                            selected_index = int(best_row["index"])
                            selected = candidates[selected_index]
                            person_bbox = person_bbox_for(selected)
                            _tracker_init(state, frame, _expand_bbox(person_bbox, 1.06, 1.05), tracker_name)
                            state.last_candidate = selected
                            state.last_person_bbox = person_bbox
                            state.last_selected_ms = ts_ms
                            state.hold_frames = 0
                            state.lost_since_ms = None
                        else:
                            state.hold_frames += 1
                            if state.lost_since_ms is None:
                                state.lost_since_ms = ts_ms
                            if state.trusted_roi is not None:
                                state.roi = state.trusted_roi
                    else:
                        state.hold_frames += 1
                        if state.lost_since_ms is None:
                            state.lost_since_ms = ts_ms
                        if state.trusted_roi is not None:
                            state.roi = state.trusted_roi
                        reason = "roi_focus_no_pose"

                    if state.lost_since_ms is not None and ts_ms - state.lost_since_ms > reacquire_ms:
                        reason = "target_lost"

                hold_frame = state.initialized and selected_index is None
                for i, candidate in enumerate(candidates):
                    is_selected = i == selected_index
                    color = (64, 220, 120) if is_selected else (128, 156, 180)
                    label_text = f"cand={i} vis={candidate.avg_visibility:.2f}"
                    score_item = next((item for item in scored if int(item["index"]) == i), None)
                    if score_item is not None:
                        label_text += f" id={score_item['identity']:.2f} roi={score_item['roi_center']:.2f}"
                        label_text += f" reid={score_item['reid']:.2f} green={score_item['green_contamination']:.2f}"
                    _draw_person_bbox(frame, person_bbox_for(candidate), color, f"person {i}", 2 if is_selected else 1)
                    _draw_candidate(frame, candidate, label_text, color, is_selected, draw_skeleton=is_selected)

                _draw_roi_state(frame, state.roi, reason, state.hold_frames)

                row = {
                    "sample_index": len(trace),
                    "frame": frame_idx - 1,
                    "timestamp_ms": ts_ms,
                    "raw_candidates": len(raw_candidates),
                    "candidates_after_gate": len(candidates),
                    "proposals_after_nms": len(proposals),
                    "proposals_gated_out": gated_out,
                    "initialized": state.initialized,
                    "selected_index": selected_index,
                    "track_id": state.track_id if state.initialized else -1,
                    "hold_frame": hold_frame,
                    "hold_frames": state.hold_frames,
                    "reason": reason,
                    "best_identity_score": round(float(best_row["identity"]), 5) if best_row else None,
                    "best_roi_iou": round(float(best_row["roi_iou"]), 5) if best_row else None,
                    "best_roi_center": round(float(best_row["roi_center"]), 5) if best_row else None,
                    "best_reid_similarity": round(float(best_row["reid"]), 5) if best_row else None,
                    "best_core_similarity": round(float(best_row["core"]), 5) if best_row else None,
                    "best_shorts_similarity": round(float(best_row["shorts"]), 5) if best_row else None,
                    "best_green_contamination": round(float(best_row["green_contamination"]), 5) if best_row else None,
                    "top2_identity_margin": round(float(margin), 5) if margin is not None else None,
                    "roi_tracker_ok": state.roi.tracker_ok if state.roi else False,
                    "roi_confidence": round(float(state.roi.confidence), 5) if state.roi else None,
                    "tracker_proposal_ok": state.tracker_roi.tracker_ok if state.tracker_roi else False,
                    "tracker_proposal_bbox": (
                        [
                            round(float(state.tracker_roi.bbox.left), 5),
                            round(float(state.tracker_roi.bbox.top), 5),
                            round(float(state.tracker_roi.bbox.right), 5),
                            round(float(state.tracker_roi.bbox.bottom), 5),
                        ]
                        if state.tracker_roi
                        else None
                    ),
                    "trusted_roi_bbox": (
                        [
                            round(float(state.trusted_roi.bbox.left), 5),
                            round(float(state.trusted_roi.bbox.top), 5),
                            round(float(state.trusted_roi.bbox.right), 5),
                            round(float(state.trusted_roi.bbox.bottom), 5),
                        ]
                        if state.trusted_roi
                        else None
                    ),
                    "roi_bbox": (
                        [
                            round(float(state.roi.bbox.left), 5),
                            round(float(state.roi.bbox.top), 5),
                            round(float(state.roi.bbox.right), 5),
                            round(float(state.roi.bbox.bottom), 5),
                        ]
                        if state.roi
                        else None
                    ),
                    "selected_center": (
                        [
                            round(float(_bbox_center(person_bbox_for(candidates[selected_index]))[0]), 5),
                            round(float(_bbox_center(person_bbox_for(candidates[selected_index]))[1]), 5),
                        ]
                        if selected_index is not None
                        else None
                    ),
                    "candidate_centers": [
                        [
                            round(float(_bbox_center(person_bbox_for(candidate))[0]), 5),
                            round(float(_bbox_center(person_bbox_for(candidate))[1]), 5),
                        ]
                        for candidate in candidates
                    ],
                    "candidate_scores": [
                        {
                            "index": int(item["index"]),
                            "identity": round(float(item["identity"]), 5),
                            "roi_iou": round(float(item["roi_iou"]), 5),
                            "roi_center": round(float(item["roi_center"]), 5),
                            "reid": round(float(item["reid"]), 5),
                            "core": round(float(item["core"]), 5),
                            "shorts": round(float(item["shorts"]), 5),
                            "green_contamination": round(float(item["green_contamination"]), 5),
                            "keypoint": round(float(item["keypoint"]), 5),
                        }
                        for item in scored
                    ],
                }
                trace.append(row)

                header = [
                    f"GemmaFit active focus v3 | {label} | tracker={tracker_name}",
                    f"frame={row['frame']} t={ts_ms}ms selected={selected_index} hold={hold_frame} reason={reason}",
                    f"raw={row['raw_candidates']} proposals={row['proposals_after_nms']} gated_out={gated_out}",
                    f"id={row['best_identity_score']} roi={row['best_roi_center']} reid={row['best_reid_similarity']} margin={row['top2_identity_margin']}",
                    f"tap=({tap_x:.3f}, {tap_y:.3f})",
                ]
                _put_header(frame, header)
                writer.write(frame)
                last_emit_ms = ts_ms
        finally:
            cap.release()
            writer.release()

    initialized_frames = sum(1 for row in trace if row["initialized"])
    selected_frames = sum(1 for row in trace if row["selected_index"] is not None)
    hold_frames = sum(1 for row in trace if row["hold_frame"])
    roi_ok_frames = sum(1 for row in trace if row["roi_tracker_ok"])
    target_lost_frames = sum(1 for row in trace if row["reason"] == "target_lost")
    contaminated_selected = sum(
        1
        for row in trace
        if row["selected_index"] is not None
        and row["best_green_contamination"] is not None
        and row["best_green_contamination"] > cfg.green_contamination_max
    )
    roi_ok_ratio = roi_ok_frames / max(1, initialized_frames)
    selected_ratio = selected_frames / max(1, initialized_frames)

    _make_contact_sheet(overlay_path, contact_sheet_path)

    summary = {
        "label": label,
        "video": str(video_path),
        "outputs": {
            "overlay_video": str(overlay_path),
            "trace_json": str(trace_path),
            "summary_json": str(summary_path),
            "contact_sheet": str(contact_sheet_path),
        },
        "config": {
            "start_ms": start_ms,
            "tap": [tap_x, tap_y],
            "every_ms": every_ms,
            "num_poses": num_poses,
            "tracker": tracker_name,
            "reacquire_ms": reacquire_ms,
            "min_roi_ok_ratio": min_roi_ok_ratio,
            "score_weights": {
                "roi_center": 0.30,
                "reid": 0.22,
                "roi_iou": 0.16,
                "core": 0.12,
                "shorts": 0.10,
                "keypoint": 0.06,
                "visibility": 0.04,
            },
        },
        "summary": {
            "frames_analyzed": len(trace),
            "initialized_frames": initialized_frames,
            "selected_frames": selected_frames,
            "selected_ratio": round(selected_ratio, 5),
            "hold_frames": hold_frames,
            "roi_ok_frames": roi_ok_frames,
            "roi_ok_ratio": round(roi_ok_ratio, 5),
            "target_lost_frames": target_lost_frames,
            "contaminated_selected_frames": contaminated_selected,
            "elapsed_s": round(time.time() - started, 3),
            "detect_seconds": round(detect_seconds, 4),
            "reid_seconds": round(reid_seconds, 4),
            "tracker_seconds": round(tracker_seconds, 4),
            "avg_tracker_ms_per_video_frame": round((tracker_seconds / max(1, frame_idx)) * 1000.0, 4),
        },
        "assertions": [
            {
                "name": "manual target initialized",
                "passed": initialized_frames > 0,
                "detail": f"initialized_frames={initialized_frames}",
            },
            {
                "name": "ROI tracker stayed active",
                "passed": roi_ok_ratio >= min_roi_ok_ratio,
                "detail": f"roi_ok={roi_ok_frames}/{initialized_frames} ratio={roi_ok_ratio:.3f} required={min_roi_ok_ratio:.3f}",
            },
            {
                "name": "no selected frame exceeds color-contamination gate",
                "passed": contaminated_selected == 0,
                "detail": f"contaminated_selected_frames={contaminated_selected}",
            },
        ],
    }
    trace_path.write_text(json.dumps(trace, indent=2, ensure_ascii=False), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", default=str(DEFAULT_VIDEO))
    parser.add_argument("--label", default="pixel_line_error_active_focus_v3")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    parser.add_argument("--tracker", default="csrt", choices=["csrt", "kcf", "mil", "mosse"])
    parser.add_argument("--start-ms", type=int, default=1900)
    parser.add_argument("--tap-x", type=float, default=0.47)
    parser.add_argument("--tap-y", type=float, default=0.53)
    parser.add_argument("--every-ms", type=int, default=125)
    parser.add_argument("--max-frames", type=int, default=0)
    parser.add_argument("--num-poses", type=int, default=4)
    parser.add_argument("--reacquire-ms", type=int, default=1500)
    parser.add_argument("--min-roi-ok-ratio", type=float, default=0.70)
    args = parser.parse_args()

    summary = _run(
        video_path=Path(args.video),
        out_dir=Path(args.out_dir),
        label=args.label,
        tracker_name=args.tracker,
        start_ms=args.start_ms,
        tap_x=args.tap_x,
        tap_y=args.tap_y,
        every_ms=args.every_ms,
        max_frames=args.max_frames,
        num_poses=args.num_poses,
        reacquire_ms=args.reacquire_ms,
        min_roi_ok_ratio=args.min_roi_ok_ratio,
    )
    sm = summary["summary"]
    print(f"Overlay: {summary['outputs']['overlay_video']}")
    print(f"Trace:   {summary['outputs']['trace_json']}")
    print(f"Summary: {summary['outputs']['summary_json']}")
    print(f"Sheet:   {summary['outputs']['contact_sheet']}")
    print(
        "frames={frames_analyzed} selected={selected_frames} ratio={selected_ratio} "
        "hold={hold_frames} roi_ok={roi_ok_frames} roi_ratio={roi_ok_ratio} "
        "lost={target_lost_frames} avg_tracker_ms={avg_tracker_ms_per_video_frame}".format(**sm)
    )
    failures = 0
    for item in summary["assertions"]:
        tag = "OK" if item["passed"] else "FAIL"
        if not item["passed"]:
            failures += 1
        print(f"{tag:4s} {item['name']}: {item['detail']}")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
