"""render_demo_video.py — render a Phase-3 demo MP4 from any input video.

Pipeline per frame:
  pose → angles → exercise detect → applicability gates →
  template metrics → structured report → mock Gemma feedback
Output:
  Side-by-side composite — annotated video on the left, info panel on the right.
  Skeleton, flagged joints, COM, recent joint trails, KPI strip,
  Gemma coaching message.

Usage:
  python prototype/render_demo_video.py --video squat
  python prototype/render_demo_video.py --video pushup
  python prototype/render_demo_video.py --video deadlift --out demo.mp4
  python prototype/render_demo_video.py --input C:/path/to/video.webm
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from collections import deque
from pathlib import Path
from typing import Deque, Optional

import cv2
import mediapipe as mp
import numpy as np

PROTO_DIR = Path(__file__).resolve().parent
ROOT_DIR  = PROTO_DIR.parent
sys.path.insert(0, str(PROTO_DIR))

from compute_angles import get_squat_phase
from movement_classifier import KEYPOINT, calc_angle
from com_tracker_prototype import track_com
from rep_counter import RepCounter
from exercises.core import (
    TEMPLATES, detect_exercise, apply_gates, extract_template_metrics,
    build_report, mock_gemma_feedback,
    STATUS_OK, STATUS_MONITOR, STATUS_WARNING, STATUS_CRITICAL,
)

LANDMARK_NAMES = [
    'nose','left_eye_inner','left_eye','left_eye_outer',
    'right_eye_inner','right_eye','right_eye_outer',
    'left_ear','right_ear','mouth_left','mouth_right',
    'left_shoulder','right_shoulder','left_elbow','right_elbow',
    'left_wrist','right_wrist','left_pinky','right_pinky',
    'left_index','right_index','left_thumb','right_thumb',
    'left_hip','right_hip','left_knee','right_knee',
    'left_ankle','right_ankle','left_heel','right_heel',
    'left_foot_index','right_foot_index',
]

SKELETON = [
    ('left_shoulder','right_shoulder'),
    ('left_shoulder','left_elbow'),('left_elbow','left_wrist'),
    ('right_shoulder','right_elbow'),('right_elbow','right_wrist'),
    ('left_shoulder','left_hip'),('right_shoulder','right_hip'),
    ('left_hip','right_hip'),
    ('left_hip','left_knee'),('left_knee','left_ankle'),
    ('right_hip','right_knee'),('right_knee','right_ankle'),
    ('left_ankle','left_heel'),('left_heel','left_foot_index'),
    ('right_ankle','right_heel'),('right_heel','right_foot_index'),
]

# BGR colours
COL_BG       = (36, 23, 15)        # dark navy
COL_PANEL    = (46, 26, 26)
COL_TEXT     = (224, 224, 224)
COL_DIM      = (160, 160, 160)
COL_BLUE     = (196, 205, 78)      # 4ECDC4
COL_RED      = (68, 68, 255)
COL_ORANGE   = (0, 165, 255)
COL_YELLOW   = (0, 215, 255)
COL_GREEN    = (170, 212, 0)       # 00D4AA
COL_GOLD     = (0, 215, 255)

STATUS_BGR = {
    STATUS_OK:       COL_GREEN,
    STATUS_MONITOR:  COL_YELLOW,
    STATUS_WARNING:  COL_ORANGE,
    STATUS_CRITICAL: COL_RED,
}
STATUS_LABEL = {
    STATUS_OK: 'CLEAN', STATUS_MONITOR: 'WATCH',
    STATUS_WARNING: 'WARNING', STATUS_CRITICAL: 'CORRECT NOW',
}

EXERCISE_LABEL = {
    'squat': 'Squat', 'push_up': 'Push-up',
    'lunge': 'Lunge', 'deadlift': 'Deadlift', 'unknown': 'Unknown',
}

ANGLE_TRIPLETS = {
    'left_knee':   ('left_hip',      'left_knee',     'left_ankle'),
    'right_knee':  ('right_hip',     'right_knee',    'right_ankle'),
    'left_hip':    ('left_shoulder', 'left_hip',      'left_knee'),
    'right_hip':   ('right_shoulder','right_hip',     'right_knee'),
    'left_elbow':  ('left_shoulder', 'left_elbow',    'left_wrist'),
    'right_elbow': ('right_shoulder','right_elbow',   'right_wrist'),
    'spine':       ('left_shoulder', 'left_hip',      'left_knee'),
}


# ── helpers ─────────────────────────────────────────────────────────────────

def _arr_from_landmarks(res, fallback_shape=(33, 3)) -> np.ndarray:
    arr = np.zeros(fallback_shape, dtype=np.float32)
    if not res or not res.pose_landmarks:
        return arr
    for i, lp in enumerate(res.pose_landmarks.landmark[:33]):
        arr[i, 0] = lp.x
        arr[i, 1] = lp.y
        arr[i, 2] = lp.visibility
    return arr


def _compute_angles(arr: np.ndarray) -> dict:
    out = {}
    for name, (a, b, c) in ANGLE_TRIPLETS.items():
        try:
            out[name] = float(calc_angle(
                arr[KEYPOINT[a]], arr[KEYPOINT[b]], arr[KEYPOINT[c]]))
        except Exception:
            out[name] = 180.0
    return out


def _lm_to_mp_list(arr: np.ndarray) -> list:
    class _LM:
        def __init__(self, x, y, z=0.0, vis=0.9):
            self.x = x; self.y = y; self.z = z; self.visibility = vis
    return [_LM(arr[i, 0], arr[i, 1], 0.0, arr[i, 2]) for i in range(33)]


def _draw_skeleton(img: np.ndarray, arr: np.ndarray,
                   flagged: set, com=None,
                   trails: Optional[dict] = None) -> None:
    h, w = img.shape[:2]

    # Trails (drawn first)
    if trails:
        for jname, hist in trails.items():
            if len(hist) < 2: continue
            pts = list(hist)
            for i in range(1, len(pts)):
                a = i / len(pts)
                color = tuple(int(c * (0.25 + 0.75 * a)) for c in COL_GOLD)
                cv2.line(img, pts[i-1], pts[i], color,
                         max(1, int(1 + 2*a)), cv2.LINE_AA)

    # Bones
    for a_name, b_name in SKELETON:
        ia, ib = KEYPOINT[a_name], KEYPOINT[b_name]
        ax, ay = int(arr[ia, 0]*w), int(arr[ia, 1]*h)
        bx, by = int(arr[ib, 0]*w), int(arr[ib, 1]*h)
        if (ax, ay) == (0, 0) or (bx, by) == (0, 0):
            continue
        hit = any(j in a_name or j in b_name for j in flagged)
        cv2.line(img, (ax, ay), (bx, by),
                 COL_RED if hit else COL_BLUE, 3, cv2.LINE_AA)

    # Joints + angle labels
    for jname in ('left_knee','right_knee','left_hip','right_hip',
                  'left_elbow','right_elbow'):
        idx = KEYPOINT.get(jname, -1)
        if idx < 0: continue
        jx, jy = int(arr[idx, 0]*w), int(arr[idx, 1]*h)
        if (jx, jy) == (0, 0): continue
        is_flagged = jname in flagged
        cv2.circle(img, (jx, jy), 6,
                   COL_RED if is_flagged else COL_GREEN, -1, cv2.LINE_AA)
        cv2.circle(img, (jx, jy), 7, (255, 255, 255), 1, cv2.LINE_AA)

    # COM star
    if com is not None:
        cx, cy = int(com.com.x * w), int(com.com.y * h)
        cv2.drawMarker(img, (cx, cy), COL_GOLD,
                       markerType=cv2.MARKER_STAR, markerSize=18, thickness=2)


def _put_text(img, txt, org, scale=0.6, color=COL_TEXT, thick=1, bg=False):
    if bg:
        (tw, th), _ = cv2.getTextSize(txt, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
        x, y = org
        cv2.rectangle(img, (x-3, y-th-4), (x+tw+3, y+4), COL_PANEL, -1)
    cv2.putText(img, txt, org, cv2.FONT_HERSHEY_SIMPLEX,
                scale, color, thick, cv2.LINE_AA)


def _wrap_text(text: str, width: int, scale: float, thick: int) -> list:
    """Word-wrap by measuring with cv2.getTextSize."""
    words = text.split()
    lines, cur = [], ''
    for w in words:
        cand = (cur + ' ' + w).strip()
        (tw, _), _ = cv2.getTextSize(cand, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
        if tw <= width:
            cur = cand
        else:
            if cur: lines.append(cur)
            cur = w
    if cur: lines.append(cur)
    return lines


def _render_panel(panel_w: int, panel_h: int,
                  cur_ex: str, cur_conf: float,
                  rep: int, phase: str,
                  status: str, status_msg: str,
                  metrics: dict, gemma_msg: str,
                  flagged_count: int, total_frames: int,
                  clean_pct: float,
                  processing_elapsed_s: float = 0.0,
                  processing_fps: float = 0.0,
                  coach_source: str = "mock_gemma_feedback") -> np.ndarray:
    panel = np.full((panel_h, panel_w, 3), COL_BG, dtype=np.uint8)
    pad = 24
    y = 36

    # Big exercise label
    label = EXERCISE_LABEL.get(cur_ex, cur_ex.title())
    _put_text(panel, label, (pad, y), scale=1.4, color=COL_GREEN, thick=3)
    y += 20
    _put_text(panel, f"detected at {cur_conf*100:.0f}% confidence",
              (pad, y), scale=0.55, color=COL_DIM)
    y += 30

    # Status pill
    status_color = STATUS_BGR.get(status, COL_GREEN)
    pill_label = STATUS_LABEL.get(status, status)
    cv2.rectangle(panel, (pad, y - 4), (pad + 180, y + 30),
                  status_color, -1)
    _put_text(panel, pill_label, (pad + 12, y + 20),
              scale=0.7, color=(20, 20, 20), thick=2)
    y += 56

    # Status message — wrap to panel width
    if status_msg:
        for line in _wrap_text(status_msg, panel_w - 2*pad, 0.55, 1)[:3]:
            _put_text(panel, line, (pad, y), scale=0.55,
                      color=COL_TEXT)
            y += 22
    y += 6

    # Divider
    cv2.line(panel, (pad, y), (panel_w - pad, y), (60, 60, 60), 1)
    y += 24

    # Reps + phase row
    _put_text(panel, f"REPS",  (pad, y), scale=0.5, color=COL_DIM)
    _put_text(panel, f"PHASE", (pad + 140, y), scale=0.5, color=COL_DIM)
    y += 24
    _put_text(panel, f"{rep}",  (pad, y),     scale=1.2, color=COL_TEXT, thick=2)
    _put_text(panel, phase or '-', (pad + 140, y),
              scale=0.85, color=COL_TEXT, thick=2)
    y += 30

    # Top metrics (max 4 entries)
    if metrics:
        _put_text(panel, "Key metrics", (pad, y),
                  scale=0.55, color=COL_DIM)
        y += 22
        for k, v in list(metrics.items())[:4]:
            unit = '°/s' if 'dps' in k or 'tempo' in k else (
                '%' if 'pct' in k else '°')
            row = f"{k.replace('_', ' '):26s} {v}{unit}"
            _put_text(panel, row, (pad, y), scale=0.5, color=COL_TEXT)
            y += 22
    y += 8

    # Divider
    cv2.line(panel, (pad, y), (panel_w - pad, y), (60, 60, 60), 1)
    y += 22

    # Gemma message block
    _put_text(panel, "GemmaFit Coach", (pad, y),
              scale=0.55, color=COL_GREEN, thick=2)
    _put_text(panel, coach_source.replace("_", " ")[:32],
              (pad + 175, y), scale=0.42, color=COL_DIM)
    y += 26
    if gemma_msg:
        for line in _wrap_text(gemma_msg, panel_w - 2*pad, 0.55, 1)[:5]:
            _put_text(panel, line, (pad, y), scale=0.55, color=COL_TEXT)
            y += 22

    # Footer KPI bar
    fy = panel_h - 70
    cv2.line(panel, (pad, fy - 14), (panel_w - pad, fy - 14), (60, 60, 60), 1)
    _put_text(panel, f"Frame {flagged_count}/{total_frames}",
              (pad, fy + 6), scale=0.5, color=COL_DIM)
    _put_text(panel, f"Clean: {clean_pct:.0f}%  Processing: {processing_elapsed_s:.1f}s",
              (pad, fy + 30), scale=0.5, color=COL_GREEN, thick=2)
    _put_text(panel, f"Render FPS: {processing_fps:.1f}",
              (pad + 250, fy + 6), scale=0.5, color=COL_DIM)
    _put_text(panel, "pose-based feedback · not medical diagnosis",
              (pad, panel_h - 14), scale=0.42, color=COL_DIM)

    return panel


# ── main render ─────────────────────────────────────────────────────────────

DEMO_VIDEOS = {
    'squat':    'squat_wikimedia_01.webm',
    'pushup':   'pushup_cdc_01.webm',
    'lunge':    'lunge_kettlebell.webm',
    'deadlift': 'deadlift_demo.webm',
}

DEFAULT_GEMMA_MODEL = ROOT_DIR / 'models' / 'gemmafit-q4_k_m.gguf'
DEFAULT_LLAMA_CLI = Path(r'D:\llama.cpp-bin\cpu-x64\llama-cli.exe')

_GEMMA_FORBIDDEN = (
    'injury', 'injured', 'risk', 'strain', 'pain',
    'diagnosis', 'medical', 'joint force', 'newtons', 'clinical',
    '受傷', '風險', '疼痛', '醫療', '診斷', '關節受力',
)


def _round_value(value):
    if isinstance(value, (int, float, np.floating)):
        return round(float(value), 2)
    return value


def _report_payload(report) -> dict:
    return {
        'exercise': report.exercise,
        'exercise_confidence': round(float(report.exercise_confidence), 2),
        'phase': report.phase,
        'rep': report.rep,
        'metrics': {
            k: _round_value(v)
            for k, v in (report.metrics or {}).items()
        },
        'quality_flags': [
            {
                'id': f.id,
                'status': f.status,
                'rule': f.rule,
                'joint': f.joint,
                'value': _round_value(f.value),
                'threshold': _round_value(f.threshold),
                'evidence': f.evidence,
                'reason': f.reason,
            }
            for f in (report.quality_flags or [])
        ],
        'not_applicable': [
            {
                'id': n.id,
                'rule': n.rule,
                'status': n.status,
                'reason': n.reason,
            }
            for n in (report.not_applicable or [])
        ],
        'boundaries': [
            'movement_quality_feedback_only',
            'no_medical_diagnosis',
            'no_injury_prediction',
            'no_joint_force_estimation',
        ],
    }


def _build_local_gemma_prompt(report) -> str:
    payload = _report_payload(report)
    return (
        "Output final answer only. No reasoning, no markdown, no JSON.\n"
        "You are GemmaFit, a local movement quality coach.\n"
        "Use only the allowed evidence in this structured report.\n"
        "Never estimate joint force. Never mention medical diagnosis, injury, "
        "pain, strain, or risk.\n"
        "If a judgment is listed as not_applicable, say it cannot be judged "
        "from this view instead of inferring it.\n"
        "Write exactly one concise coaching sentence under 22 words.\n"
        "Structured report:\n"
        f"{json.dumps(payload, ensure_ascii=False)}\n"
        "Final answer:\n"
    )


def _extract_llama_answer(stdout: str) -> str:
    text = stdout
    marker = 'Final answer:'
    if marker in text:
        text = text.split(marker)[-1]
    elif '\n> ' in text:
        text = text.split('\n> ')[-1]
    text = re.sub(r'\[ Prompt:[\s\S]*$', '', text)
    text = text.replace('Exiting...', '')
    lines = [
        line.strip()
        for line in text.splitlines()
        if line.strip()
        and not line.startswith('Loading model')
        and not line.startswith('build')
        and not line.startswith('model')
        and not line.startswith('modalities')
        and not line.startswith('available commands')
        and not line.startswith('/')
    ]
    return ' '.join(lines).strip()


def _safe_local_message(message: str, fallback: str) -> str:
    if not message:
        return fallback
    lowered = message.lower()
    if any(term in lowered for term in _GEMMA_FORBIDDEN):
        return fallback
    return message


def _local_gemma_feedback(report, model_path: Path, llama_cli: Path,
                          prompt_dir: Path, max_tokens: int = 80,
                          timeout_s: int = 90) -> dict:
    fallback = mock_gemma_feedback(report)
    if not model_path.exists():
        fallback['source'] = 'mock_gemma_feedback (local model missing)'
        return fallback
    if not llama_cli.exists():
        fallback['source'] = 'mock_gemma_feedback (llama-cli missing)'
        return fallback

    prompt_dir.mkdir(parents=True, exist_ok=True)
    prompt_path = prompt_dir / f'gemma_frame_{report.frame:06d}.txt'
    prompt_path.write_text(_build_local_gemma_prompt(report), encoding='utf-8')

    cmd = [
        str(llama_cli),
        '-m', str(model_path),
        '-f', str(prompt_path),
        '-c', '1024',
        '-n', str(max_tokens),
        '-t', '8',
        '--temp', '0.1',
        '--no-display-prompt',
        '--reasoning', 'off',
        '--reasoning-budget', '0',
        '-st',
        '--simple-io',
        '--color', 'off',
    ]
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(prompt_dir),
            text=True,
            encoding='utf-8',
            errors='replace',
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_s,
            check=False,
        )
        if proc.returncode != 0:
            fallback['source'] = f'mock_gemma_feedback (llama-cli exit {proc.returncode})'
            fallback['error'] = proc.stderr[-500:]
            return fallback
        raw = _extract_llama_answer(proc.stdout)
        msg = _safe_local_message(raw, fallback['message'])
        return {
            'source': 'local_gemma_llama_cli',
            'frame': report.frame,
            'level': fallback.get('level', 'ok'),
            'message': msg,
            'safety_note': 'Pose-based coaching only; not medical diagnosis.',
            'model_path': model_path.name,
        }
    except Exception as exc:
        fallback['source'] = f'mock_gemma_feedback (local gemma fallback: {type(exc).__name__})'
        fallback['error'] = str(exc)
        return fallback


def render(video_path: Path, out_path: Path,
           panel_w: int = 480, max_height: int = 720,
           trail_len: int = 18,
           coach: str = 'mock',
           gemma_model: Path = DEFAULT_GEMMA_MODEL,
           llama_cli: Path = DEFAULT_LLAMA_CLI,
           gemma_interval: int = 60) -> None:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"Cannot open video: {video_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
    src_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    src_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    # Scale source frame so its height ≤ max_height
    scale = min(1.0, max_height / max(1, src_h))
    vid_w = int(src_w * scale)
    vid_h = int(src_h * scale)
    out_w = vid_w + panel_w
    out_h = max(vid_h, 540)

    print(f"Source : {video_path.name}  {src_w}x{src_h} @ {fps:.1f}fps  ({total} frames)")
    print(f"Output : {out_path.name}  {out_w}x{out_h}")
    print(f"Coach  : {coach}")
    if coach == 'local-gemma':
        print(f"Model  : {gemma_model}")
        print(f"Runtime: {llama_cli}")
    start_time = time.perf_counter()
    gemma_prompt_dir = out_path.parent / f'{out_path.stem}_gemma_prompts'
    cached_gemma = None
    cached_gemma_key = None

    out_path.parent.mkdir(parents=True, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(str(out_path), fourcc, fps, (out_w, out_h))
    if not writer.isOpened():
        raise RuntimeError("Cannot open VideoWriter (mp4v unavailable?)")

    pose = mp.solutions.pose.Pose(min_detection_confidence=0.5,
                                  min_tracking_confidence=0.5)
    rep_ctr = RepCounter()
    prev_ang: Optional[dict] = None

    # Trail history (keypoint x,y in pixel coords of the scaled frame)
    trails = {jn: deque(maxlen=trail_len)
              for jn in ('left_wrist','right_wrist','left_knee','right_knee')}

    # Session-level lock: after WARM_UP frames, freeze the exercise to whichever
    # was most common, so the panel stops flickering between candidates.
    WARM_UP = 30
    warmup_votes: list = []
    locked_exercise: Optional[str] = None

    frame_idx = 0
    n_clean = 0
    n_total = 0
    while True:
        ok, src_frame = cap.read()
        if not ok: break
        n_total += 1

        # Resize source
        if scale < 1.0:
            src_frame = cv2.resize(src_frame, (vid_w, vid_h),
                                   interpolation=cv2.INTER_AREA)

        rgb = cv2.cvtColor(src_frame, cv2.COLOR_BGR2RGB)
        res = pose.process(rgb)
        arr = _arr_from_landmarks(res)
        angles = _compute_angles(arr)

        ex_res = detect_exercise(arr)
        # Apply session lock once warm-up votes are in.
        if locked_exercise is None:
            warmup_votes.append(ex_res.exercise)
            if frame_idx + 1 >= WARM_UP:
                from collections import Counter as _Cnt
                # Prefer the most common non-'unknown' label.
                votes = _Cnt(v for v in warmup_votes if v != 'unknown')
                locked_exercise = (votes.most_common(1)[0][0]
                                   if votes else 'unknown')
        if locked_exercise is not None and locked_exercise != 'unknown':
            from exercises.core import ExerciseDetectionResult as _EDR
            ex_res = _EDR(
                exercise=locked_exercise,
                exercise_confidence=max(ex_res.exercise_confidence,
                                        ex_res.candidate_scores.get(locked_exercise, 0.0)),
                candidate_scores=ex_res.candidate_scores,
                basis=['session_locked'],
                status=STATUS_OK,
            )
        tmpl   = TEMPLATES.get(ex_res.exercise, TEMPLATES['unknown'])
        com    = track_com(_lm_to_mp_list(arr), sex='male', contact='bipedal')
        qf, na = apply_gates([], tmpl, arr, angles, ex_res.exercise_confidence)
        metrics = extract_template_metrics(angles, tmpl, arr, fps, prev_ang)

        rep_ctr.update(angles.get('left_knee', 180.0), 0)
        phase = get_squat_phase(angles.get('left_knee', 180.0), 'top')

        report = build_report(frame_idx, ex_res, phase,
                              rep_ctr.rep_count, metrics, qf, na)
        top_flag = None
        if qf:
            _prio = {STATUS_CRITICAL: 0, STATUS_WARNING: 1,
                     STATUS_MONITOR: 2, STATUS_OK: 99}
            top_flag = sorted(qf, key=lambda f: _prio.get(f.status, 99))[0]
        gemma_key = (
            ex_res.exercise,
            phase,
            rep_ctr.rep_count,
            top_flag.id if top_flag else 'none',
            top_flag.status if top_flag else STATUS_OK,
            frame_idx // max(1, gemma_interval),
        )
        if coach == 'local-gemma':
            should_refresh = cached_gemma is None or gemma_key != cached_gemma_key
            if should_refresh:
                cached_gemma = _local_gemma_feedback(
                    report, Path(gemma_model), Path(llama_cli), gemma_prompt_dir)
                cached_gemma_key = gemma_key
                print(f"    local-gemma frame={frame_idx} source={cached_gemma.get('source')} "
                      f"msg={cached_gemma.get('message', '')[:70]}",
                      flush=True)
            gemma = cached_gemma
        else:
            gemma = mock_gemma_feedback(report)

        # Aggregate worst-status for the frame
        worst_status = STATUS_OK
        worst_msg = ""
        priority = {STATUS_CRITICAL: 0, STATUS_WARNING: 1,
                    STATUS_MONITOR: 2, STATUS_OK: 99}
        if qf:
            qf_sorted = sorted(qf, key=lambda f: priority.get(f.status, 99))
            top = qf_sorted[0]
            worst_status = top.status
            worst_msg = (f"{top.id.replace('rule_','rule ').replace('_',' ')}"
                         f" — {top.joint or '—'}")
        if worst_status == STATUS_OK: n_clean += 1

        flagged = {f.joint for f in qf
                   if f.status in (STATUS_CRITICAL, STATUS_WARNING)}

        # Update trails (in scaled-frame pixels)
        for jn in trails:
            idx_kp = KEYPOINT.get(jn, -1)
            if idx_kp < 0: continue
            x, y = arr[idx_kp, 0], arr[idx_kp, 1]
            if x > 0 and y > 0:
                trails[jn].append((int(x*vid_w), int(y*vid_h)))

        # Compose canvas
        canvas = np.full((out_h, out_w, 3), COL_BG, dtype=np.uint8)
        # Centre source vertically
        y_off = (out_h - vid_h) // 2
        canvas[y_off:y_off+vid_h, 0:vid_w] = src_frame
        _draw_skeleton(canvas[y_off:y_off+vid_h, 0:vid_w], arr,
                       flagged=flagged, com=com, trails=trails)

        clean_pct = 100.0 * n_clean / max(1, n_total)
        elapsed_now = time.perf_counter() - start_time
        render_fps = n_total / max(elapsed_now, 1e-6)
        panel = _render_panel(panel_w, out_h,
                              ex_res.exercise, ex_res.exercise_confidence,
                              rep_ctr.rep_count, phase,
                              worst_status, worst_msg,
                              metrics, gemma['message'],
                              n_total, total, clean_pct,
                              elapsed_now, render_fps,
                              gemma.get('source', coach))
        canvas[0:out_h, vid_w:vid_w+panel_w] = panel

        writer.write(canvas)
        frame_idx += 1
        prev_ang = angles

        if frame_idx % 20 == 0:
            pct = 100.0 * frame_idx / max(1, total) if total else 0
            print(f"  {frame_idx:4d}/{total or '?'}  ({pct:5.1f}%)  "
                  f"ex={ex_res.exercise}  status={worst_status}  rep={rep_ctr.rep_count}",
                  flush=True)

    writer.release()
    cap.release()
    pose.close()

    elapsed_total = time.perf_counter() - start_time
    render_fps_total = frame_idx / max(elapsed_total, 1e-6)
    final_clean = 100.0 * n_clean / max(1, n_total)
    print(f"\nDone: {frame_idx} frames written")
    print(f"Processing time: {elapsed_total:.2f}s  ({render_fps_total:.2f} render fps)")
    print(f"Clean rate: {final_clean:.0f}%")
    print(f"Output:     {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--video', choices=list(DEMO_VIDEOS),
                    help='Built-in demo asset key.')
    ap.add_argument('--input', help='Path to a custom video file.')
    ap.add_argument('--out', help='Output mp4 path.')
    ap.add_argument('--max-height', type=int, default=720)
    ap.add_argument('--panel-width', type=int, default=480)
    ap.add_argument('--coach', choices=['mock', 'local-gemma'], default='mock',
                    help='Coaching text source. local-gemma uses llama-cli + GGUF.')
    ap.add_argument('--gemma-model', default=str(DEFAULT_GEMMA_MODEL),
                    help='Path to trained Gemma GGUF for --coach local-gemma.')
    ap.add_argument('--llama-cli', default=str(DEFAULT_LLAMA_CLI),
                    help='Path to llama-cli executable for --coach local-gemma.')
    ap.add_argument('--gemma-interval', type=int, default=60,
                    help='Refresh local Gemma feedback every N frames.')
    args = ap.parse_args()

    if args.input:
        in_path = Path(args.input)
    elif args.video:
        in_path = ROOT_DIR / 'test_assets' / 'videos' / DEMO_VIDEOS[args.video]
    else:
        ap.error("Provide --video <key> or --input <path>")

    if not in_path.exists():
        sys.exit(f"Input not found: {in_path}")

    out_dir = ROOT_DIR / 'demo_output'
    out_path = Path(args.out) if args.out else (
        out_dir / f"{in_path.stem}_gemmafit_demo.mp4")

    render(in_path, out_path,
           panel_w=args.panel_width,
           max_height=args.max_height,
           coach=args.coach,
           gemma_model=Path(args.gemma_model),
           llama_cli=Path(args.llama_cli),
           gemma_interval=args.gemma_interval)


if __name__ == '__main__':
    main()
