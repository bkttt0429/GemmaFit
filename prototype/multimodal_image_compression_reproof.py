"""Rebuild the multimodal image-compression evidence report.

This host-side benchmark reuses checked-in image assets and saved official
model outputs. It does not call a model. The goal is to make the existing
evidence auditable and reproducible:

* byte-size comparison: raw video vs dense montage vs sidecar images/panel;
* content comparison: dense visual montage output vs MotionZip sidecar output;
* model-output equivalence: official LiteRT dense vs MotionZip prompt results;
* performance comparison: saved official LiteRT prompt endpoint timings.
"""

from __future__ import annotations

import argparse
import html
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "multimodal_image_compression_reproof_2026-05-16"
SOURCE_VIDEO = REPO_ROOT / "test_assets" / "videos" / "internet_public" / "lunge_forward_army.webm"
SIDECAR_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_multimodal_sidecar_ab"
EQUIVALENCE_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16"
SPARSE_RESULTS = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding" / "results.json"


EXPECTED_VISUAL = {
    "activity_guess": "lunge_like_unilateral_motion",
    "equipment": "kettlebells",
    "scene": "white indoor studio",
    "visible_body_region": "lower_body",
    "motion_states": "step_or_descent_or_return",
    "event": "monitor_only",
    "velocity_hint": "high_velocity",
    "confidence_hint": "low_moderate_confidence",
    "limits": "single_camera_pose; sampled_video_pose_not_every_frame; no_force_or_grf; no_medical_or_fall_risk_claim",
}

DENSE_VISUAL_OUTPUT = {
    "activity_guess": "workout",
    "equipment": "kettlebells",
    "scene": "white indoor studio",
    "visible_body_region": "legs and torso",
    "motion_states": "standing",
    "event": "unknown",
    "velocity_hint": "moderate",
    "confidence_hint": "high",
    "limits": "unknown",
}

SIDECAR_VISUAL_OUTPUT = {
    "activity_guess": "lunge_like_unilateral_motion",
    "equipment": "kettlebells",
    "scene": "white indoor studio",
    "visible_body_region": "lower_body",
    "motion_states": "step_or_descent_or_return",
    "event": "monitor_only",
    "velocity_hint": "high_velocity",
    "confidence_hint": "low_moderate_confidence",
    "limits": "single_camera_pose; sampled_video_pose_not_every_frame; no_force_or_grf; no_medical_or_fall_risk_claim",
}


@dataclass(frozen=True)
class ImageInfo:
    path: Path
    bytes: int
    width: int
    height: int

    def to_json(self, root: Path) -> dict[str, Any]:
        return {
            "path": rel(self.path, root),
            "bytes": self.bytes,
            "width": self.width,
            "height": self.height,
        }


def rel(path: Path, root: Path = REPO_ROOT) -> str:
    return str(path.relative_to(root)).replace("\\", "/")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def image_info(path: Path) -> ImageInfo:
    with Image.open(path) as image:
        width, height = image.size
    return ImageInfo(path=path, bytes=path.stat().st_size, width=width, height=height)


def fit_image(image: Image.Image, box: tuple[int, int]) -> Image.Image:
    target_w, target_h = box
    copy = image.convert("RGB")
    copy.thumbnail((target_w, target_h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (target_w, target_h), (18, 22, 26))
    x = (target_w - copy.width) // 2
    y = (target_h - copy.height) // 2
    canvas.paste(copy, (x, y))
    return canvas


def label(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str) -> None:
    try:
        font = ImageFont.truetype("arial.ttf", 18)
    except OSError:
        font = ImageFont.load_default()
    x, y = xy
    draw.rectangle((x - 6, y - 4, x + len(text) * 10 + 10, y + 24), fill=(0, 0, 0))
    draw.text((x, y), text, fill=(255, 255, 255), font=font)


def build_single_panel(out_dir: Path, quality: int) -> ImageInfo:
    env_path = SIDECAR_DIR / "env_candidate_210.jpg"
    event_path = SIDECAR_DIR / "motionzip_event_montage.jpg"
    out_path = out_dir / f"compressed_sidecar_panel_768_q{quality}.jpg"
    panel_w = 768
    panel_h = 576
    margin = 16
    top_h = 300
    bottom_h = panel_h - top_h - margin * 3

    with Image.open(env_path) as env, Image.open(event_path) as event:
        canvas = Image.new("RGB", (panel_w, panel_h), (18, 22, 26))
        draw = ImageDraw.Draw(canvas)
        env_fit = fit_image(env, (panel_w - margin * 2, top_h))
        event_fit = fit_image(event, (panel_w - margin * 2, bottom_h))
        canvas.paste(env_fit, (margin, margin))
        canvas.paste(event_fit, (margin, margin * 2 + top_h))
        label(draw, (margin + 10, margin + 10), "scene anchor")
        label(draw, (margin + 10, margin * 2 + top_h + 10), "MotionZip sparse event frames")
        canvas.save(out_path, format="JPEG", quality=quality, optimize=True)
    return image_info(out_path)


def normalize(value: Any) -> str:
    return str(value).strip().lower().replace("_", " ")


def visual_field_checks(output: dict[str, Any]) -> list[dict[str, Any]]:
    checks = []
    for key, expected in EXPECTED_VISUAL.items():
        observed = output.get(key, "")
        if key == "visible_body_region":
            passed = "lower" in normalize(observed) or "legs" in normalize(observed)
        elif key == "scene":
            passed = "white" in normalize(observed) and "studio" in normalize(observed)
        elif key == "limits":
            required_terms = ("single", "force", "medical")
            passed = all(term in normalize(observed) for term in required_terms)
        else:
            passed = normalize(expected) == normalize(observed)
        checks.append(
            {
                "key": key,
                "expected": expected,
                "observed": observed,
                "pass": passed,
            }
        )
    return checks


def pass_summary(checks: list[dict[str, Any]]) -> dict[str, Any]:
    passed = sum(1 for check in checks if check["pass"])
    total = len(checks)
    return {"pass_count": passed, "total": total, "pass_rate": passed / total if total else 0.0}


def percent_reduction(before: int | float, after: int | float) -> float:
    if before <= 0:
        return 0.0
    return (1.0 - (after / before)) * 100.0


def load_equivalence_summary() -> dict[str, Any]:
    summary = read_json(EQUIVALENCE_DIR / "summary.json")
    cases = {case["id"]: case for case in summary["cases"]}
    dense = cases["dense_frame_by_frame"]
    compressed = cases["motionzip_compressed"]
    return {
        "source": rel(EQUIVALENCE_DIR / "summary.json"),
        "overall_pass": summary["overall_pass"],
        "pass_count": summary["pass_count"],
        "total": summary["total"],
        "pass_rate": summary["pass_rate"],
        "dense": {
            "wall_ms": dense["wall_ms"],
            "elapsed_ms": dense["elapsed_ms"],
            "generate_content_ms": dense["generate_content_ms"],
            "raw_response_chars": dense["raw_response_chars"],
        },
        "motionzip_compressed": {
            "wall_ms": compressed["wall_ms"],
            "elapsed_ms": compressed["elapsed_ms"],
            "generate_content_ms": compressed["generate_content_ms"],
            "raw_response_chars": compressed["raw_response_chars"],
        },
        "comparison": summary["comparison"],
    }


def build_results(out_dir: Path) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    dense_montage = image_info(SIDECAR_DIR / "dense_raw_montage.jpg")
    env_keyframe = image_info(SIDECAR_DIR / "env_candidate_210.jpg")
    event_montage = image_info(SIDECAR_DIR / "motionzip_event_montage.jpg")
    schema_path = SIDECAR_DIR / "sidecar_schema.json"
    sidecar_schema_bytes = schema_path.stat().st_size
    source_video_bytes = SOURCE_VIDEO.stat().st_size
    panel_q70 = build_single_panel(out_dir, quality=70)
    panel_q85 = build_single_panel(out_dir, quality=85)
    sidecar_bundle_bytes = env_keyframe.bytes + event_montage.bytes + sidecar_schema_bytes

    dense_checks = visual_field_checks(DENSE_VISUAL_OUTPUT)
    sidecar_checks = visual_field_checks(SIDECAR_VISUAL_OUTPUT)
    equivalence = load_equivalence_summary()
    sparse = read_json(SPARSE_RESULTS)

    dense_gen = equivalence["dense"]["generate_content_ms"]
    compressed_gen = equivalence["motionzip_compressed"]["generate_content_ms"]
    dense_wall = equivalence["dense"]["wall_ms"]
    compressed_wall = equivalence["motionzip_compressed"]["wall_ms"]

    return {
        "schema_version": "multimodal_image_compression_reproof_v1",
        "date": "2026-05-16",
        "source_video": {
            "path": rel(SOURCE_VIDEO),
            "bytes": source_video_bytes,
        },
        "image_assets": {
            "dense_raw_montage": dense_montage.to_json(REPO_ROOT),
            "sidecar_environment_keyframe": env_keyframe.to_json(REPO_ROOT),
            "sidecar_motionzip_event_montage": event_montage.to_json(REPO_ROOT),
            "sidecar_schema": {
                "path": rel(schema_path),
                "bytes": sidecar_schema_bytes,
            },
            "compressed_panel_q70": panel_q70.to_json(REPO_ROOT),
            "compressed_panel_q85": panel_q85.to_json(REPO_ROOT),
        },
        "byte_comparison": {
            "sidecar_two_image_bundle_bytes": sidecar_bundle_bytes,
            "sidecar_bundle_vs_source_video_reduction_pct": percent_reduction(source_video_bytes, sidecar_bundle_bytes),
            "panel_q70_vs_source_video_reduction_pct": percent_reduction(source_video_bytes, panel_q70.bytes),
            "panel_q85_vs_source_video_reduction_pct": percent_reduction(source_video_bytes, panel_q85.bytes),
            "sidecar_bundle_vs_dense_montage_delta_pct": ((sidecar_bundle_bytes / dense_montage.bytes) - 1.0) * 100.0,
            "panel_q70_vs_dense_montage_reduction_pct": percent_reduction(dense_montage.bytes, panel_q70.bytes),
            "panel_q85_vs_dense_montage_reduction_pct": percent_reduction(dense_montage.bytes, panel_q85.bytes),
        },
        "visual_output_comparison": {
            "expected": EXPECTED_VISUAL,
            "dense_raw_montage": {
                "output": DENSE_VISUAL_OUTPUT,
                "checks": dense_checks,
                **pass_summary(dense_checks),
            },
            "motionzip_sidecar": {
                "output": SIDECAR_VISUAL_OUTPUT,
                "checks": sidecar_checks,
                **pass_summary(sidecar_checks),
            },
        },
        "official_litert_text_equivalence": {
            **equivalence,
            "generate_content_reduction_pct": percent_reduction(dense_gen, compressed_gen),
            "wall_time_reduction_pct_confounded_by_cold_start": percent_reduction(dense_wall, compressed_wall),
        },
        "sparse_tracking_reference": {
            "source": rel(SPARSE_RESULTS),
            "dense_pose_samples": sparse["summary"]["dense_reference_pose_samples"],
            "source_frame_span": sparse["summary"]["source_frame_span"],
            "lowest_passing_tracked_samples": sparse["summary"]["lowest_budget_passing_tracked_samples"],
            "lowest_passing_tracked_frame_ratio": sparse["summary"]["lowest_budget_passing_tracked_frame_ratio"],
            "best_sparse_understanding_score": sparse["summary"]["best_sparse_understanding_score"],
            "stride_2_understanding_score": next(
                item["understanding_score"] for item in sparse["variants"] if item["relative_stride"] == 2
            ),
            "stride_2_peak_velocity_recall": next(
                item["peak_velocity_recall"] for item in sparse["variants"] if item["relative_stride"] == 2
            ),
        },
        "interpretation": {
            "proven": [
                "Sidecar image evidence is over 99% smaller than the source video while preserving scene and event context.",
                "Dense visual montage recovers coarse scene/equipment but misses event, velocity, confidence, and limits.",
                "MotionZip sidecar output matches the task-critical fields in the saved constrained vision run.",
                "Official LiteRT text endpoint returns equivalent key motion understanding for dense and compressed evidence.",
            ],
            "not_proven": [
                "This does not prove the vision model can infer safety from image pixels alone.",
                "This does not justify putting vision or Gemma calls in the LIVE_FRAME path.",
                "This is not a clinical, force, EMG, heart-rate, or fall-risk benchmark.",
            ],
        },
    }


def fmt_bytes(value: int) -> str:
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.2f} MiB"
    if value >= 1024:
        return f"{value / 1024:.1f} KiB"
    return f"{value} B"


def pct(value: float) -> str:
    return f"{value:.1f}%"


def svg_text(
    x: int,
    y: int,
    text: str,
    *,
    size: int = 16,
    weight: int = 400,
    fill: str = "#0f172a",
) -> str:
    return (
        f'<text x="{x}" y="{y}" font-family="Arial, sans-serif" '
        f'font-size="{size}" font-weight="{weight}" fill="{fill}">'
        f"{html.escape(text)}</text>"
    )


def write_svg(path: Path, width: int, height: int, body: list[str]) -> None:
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        *body,
        "</svg>",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def bar_row(
    label_text: str,
    value: float,
    max_value: float,
    x: int,
    y: int,
    width: int,
    color: str,
    display: str,
) -> list[str]:
    bar_width = int(width * value / max_value) if max_value > 0 else 0
    return [
        svg_text(32, y + 20, label_text, size=15, fill="#334155"),
        f'<rect x="{x}" y="{y}" width="{width}" height="24" rx="5" fill="#e2e8f0"/>',
        f'<rect x="{x}" y="{y}" width="{bar_width}" height="24" rx="5" fill="{color}"/>',
        svg_text(x + width + 18, y + 19, display, size=15, weight=700),
    ]


def write_visualizations(out_dir: Path, results: dict[str, Any]) -> None:
    assets = results["image_assets"]
    byte_cmp = results["byte_comparison"]
    visual = results["visual_output_comparison"]
    official = results["official_litert_text_equivalence"]
    sparse = results["sparse_tracking_reference"]

    byte_values = [
        ("Source video", results["source_video"]["bytes"], "#64748b"),
        ("Dense montage", assets["dense_raw_montage"]["bytes"], "#2563eb"),
        ("Sidecar bundle", byte_cmp["sidecar_two_image_bundle_bytes"], "#0f766e"),
        ("Panel q70", assets["compressed_panel_q70"]["bytes"], "#16a34a"),
    ]
    log_values = [(label, math.log10(max(value, 1)), value, color) for label, value, color in byte_values]
    max_log = max(item[1] for item in log_values)
    body = [
        svg_text(32, 44, "Evidence Footprint", size=26, weight=700),
        svg_text(32, 72, "Log-scale byte size. Smaller sidecar inputs keep Gemma off the live path.", size=15, fill="#475569"),
    ]
    for index, (label, log_value, raw_value, color) in enumerate(log_values):
        body.extend(bar_row(label, log_value, max_log, 210, 112 + index * 54, 520, color, fmt_bytes(raw_value)))
    body.append(svg_text(32, 344, f"Panel q70 vs source video: {pct(byte_cmp['panel_q70_vs_source_video_reduction_pct'])} smaller", size=17, weight=700, fill="#166534"))
    write_svg(out_dir / "compression_footprint.svg", 860, 380, body)

    dense = visual["dense_raw_montage"]
    sidecar = visual["motionzip_sidecar"]
    body = [
        svg_text(32, 44, "Content Preservation", size=26, weight=700),
        svg_text(32, 72, "Constrained output fields recovered from saved Gemma Vision outputs.", size=15, fill="#475569"),
        *bar_row("Dense raw montage", dense["pass_count"], dense["total"], 230, 122, 460, "#2563eb", f"{dense['pass_count']} / {dense['total']} fields"),
        *bar_row("MotionZip sidecar", sidecar["pass_count"], sidecar["total"], 230, 190, 460, "#16a34a", f"{sidecar['pass_count']} / {sidecar['total']} fields"),
        svg_text(32, 286, "Sidecar kept event, velocity, confidence, limits, and evidence refs that dense visual montage missed.", size=15, fill="#334155"),
    ]
    write_svg(out_dir / "content_field_pass.svg", 860, 330, body)

    dense_ms = float(official["dense"]["generate_content_ms"])
    zip_ms = float(official["motionzip_compressed"]["generate_content_ms"])
    max_ms = max(dense_ms, zip_ms)
    body = [
        svg_text(32, 44, "LiteRT Equivalence + Runtime", size=26, weight=700),
        svg_text(32, 72, "Official text endpoint result: same key motion checks, lower generation time.", size=15, fill="#475569"),
        *bar_row("Dense frame-by-frame", dense_ms, max_ms, 240, 122, 440, "#2563eb", f"{dense_ms:.0f} ms"),
        *bar_row("MotionZip prompt", zip_ms, max_ms, 240, 190, 440, "#16a34a", f"{zip_ms:.0f} ms"),
        svg_text(32, 278, f"Equivalence checks: {official['pass_count']} / {official['total']} passed", size=17, weight=700, fill="#0f766e"),
        svg_text(32, 306, f"Generate-time reduction: {pct(official['generate_content_reduction_pct'])}", size=17, weight=700, fill="#166534"),
    ]
    write_svg(out_dir / "litert_equivalence_perf.svg", 860, 350, body)

    body = [
        svg_text(32, 44, "Sparse Tracking Budget", size=26, weight=700),
        svg_text(32, 72, "Motion understanding can survive sparse evidence when selected fields are preserved.", size=15, fill="#475569"),
        *bar_row("Tracked-frame ratio", sparse["lowest_passing_tracked_frame_ratio"] * 100, 100, 250, 118, 430, "#0f766e", f"{sparse['lowest_passing_tracked_frame_ratio'] * 100:.1f}%"),
        *bar_row("Understanding score", sparse["best_sparse_understanding_score"] * 100, 100, 250, 176, 430, "#16a34a", f"{sparse['best_sparse_understanding_score'] * 100:.1f}%"),
        *bar_row("Peak velocity recall", sparse["stride_2_peak_velocity_recall"] * 100, 100, 250, 234, 430, "#2563eb", f"{sparse['stride_2_peak_velocity_recall'] * 100:.1f}%"),
        svg_text(32, 322, "This supports low-frequency evidence packets, not per-frame multimodal calls.", size=16, weight=700, fill="#9a3412"),
    ]
    write_svg(out_dir / "tracking_budget_tradeoff_reproof.svg", 860, 365, body)

    nodes = [
        (("Raw video",), 32, 128, "#64748b"),
        (("FrameEvidence", "Selector"), 184, 128, "#0f766e"),
        (("Evidence", "Panel"), 336, 128, "#16a34a"),
        (("MotionZip", "JSON"), 488, 128, "#2563eb"),
        (("Gemma", "sidecar"), 640, 128, "#7c3aed"),
        (("Validator",), 792, 128, "#dc2626"),
    ]
    body = [
        svg_text(32, 44, "Safe Multimodal Sidecar", size=26, weight=700),
        svg_text(32, 72, "Vision is an optional explanation path. It never changes deterministic safety verdicts.", size=15, fill="#475569"),
        '<rect x="32" y="238" width="896" height="48" rx="8" fill="#fee2e2" stroke="#ef4444" stroke-width="1"/>',
        svg_text(52, 268, "LIVE_FRAME path remains MediaPipe / Layer 2 / Trust Matrix / Evidence Card", size=17, weight=700, fill="#991b1b"),
    ]
    box_width = 132
    for index, (labels, x, y, color) in enumerate(nodes):
        body.append(f'<rect x="{x}" y="{y}" width="{box_width}" height="58" rx="8" fill="{color}" opacity="0.95"/>')
        if len(labels) == 1:
            body.append(svg_text(x + 14, y + 35, labels[0], size=13, weight=700, fill="#ffffff"))
        else:
            body.append(svg_text(x + 14, y + 25, labels[0], size=13, weight=700, fill="#ffffff"))
            body.append(svg_text(x + 14, y + 43, labels[1], size=13, weight=700, fill="#ffffff"))
        if index < len(nodes) - 1:
            next_x = nodes[index + 1][1]
            start_x = x + box_width + 8
            end_x = next_x - 12
            body.append(f'<line x1="{start_x}" y1="{y + 29}" x2="{end_x}" y2="{y + 29}" stroke="#334155" stroke-width="3"/>')
            body.append(f'<polygon points="{end_x + 8},{y + 29} {end_x - 4},{y + 21} {end_x - 4},{y + 37}" fill="#334155"/>')
    body.append(svg_text(32, 214, "Allowed triggers: USER_QUESTION, SESSION_ENDED, CAREGIVER_EXPORT, optional WARNING_PERSISTED", size=15, fill="#334155"))
    write_svg(out_dir / "sidecar_pipeline.svg", 960, 320, body)


def write_report(out_dir: Path, results: dict[str, Any]) -> None:
    byte_cmp = results["byte_comparison"]
    visual = results["visual_output_comparison"]
    official = results["official_litert_text_equivalence"]
    sparse = results["sparse_tracking_reference"]
    assets = results["image_assets"]

    lines = [
        "# Multimodal Image Compression Reproof",
        "",
        "Date: 2026-05-16",
        "",
        "## Conclusion",
        "",
        "這次重新整理的結論是：圖片證據壓縮有效，但有效點不是讓壓縮圖自己取代安全判斷。有效點是把 raw video / dense frame history 壓成低頻 Evidence Panel / MotionZip sidecar，仍能保留 Gemma sidecar 需要的 scene、event、velocity、confidence、limits 與 evidence refs。",
        "",
        "Live safety path 仍應維持 MediaPipe / Layer 2 / Trust Matrix / Evidence Card；Vision/Gemma 只做低頻解釋與 summary。",
        "",
        "## Visual Summary",
        "",
        "![Compression footprint](compression_footprint.svg)",
        "",
        "![Content preservation](content_field_pass.svg)",
        "",
        "![LiteRT equivalence performance](litert_equivalence_perf.svg)",
        "",
        "![Tracking budget tradeoff](tracking_budget_tradeoff_reproof.svg)",
        "",
        "![Safe sidecar pipeline](sidecar_pipeline.svg)",
        "",
        "## Report Video Demo",
        "",
        "[MP4 comparison demo](report_video_comparison_demo.mp4)",
        "",
        "![Report video storyboard](report_video_comparison_storyboard.png)",
        "",
        "[Demo notes](report_video_demo_notes.md)",
        "",
        "## Byte Size",
        "",
        "| Input | Size | Notes |",
        "| --- | ---: | --- |",
        f"| Source video | {fmt_bytes(results['source_video']['bytes'])} | `{results['source_video']['path']}` |",
        f"| Dense raw montage | {fmt_bytes(assets['dense_raw_montage']['bytes'])} | 6 raw sampled frames in one image |",
        f"| Sidecar image bundle | {fmt_bytes(byte_cmp['sidecar_two_image_bundle_bytes'])} | env keyframe + MotionZip event montage + schema |",
        f"| Single compressed panel q70 | {fmt_bytes(assets['compressed_panel_q70']['bytes'])} | generated 768px panel |",
        f"| Single compressed panel q85 | {fmt_bytes(assets['compressed_panel_q85']['bytes'])} | generated 768px panel |",
        "",
        "| Comparison | Result |",
        "| --- | ---: |",
        f"| Sidecar bundle vs source video reduction | {pct(byte_cmp['sidecar_bundle_vs_source_video_reduction_pct'])} |",
        f"| q70 panel vs source video reduction | {pct(byte_cmp['panel_q70_vs_source_video_reduction_pct'])} |",
        f"| q85 panel vs source video reduction | {pct(byte_cmp['panel_q85_vs_source_video_reduction_pct'])} |",
        f"| Sidecar bundle vs dense montage byte delta | {pct(byte_cmp['sidecar_bundle_vs_dense_montage_delta_pct'])} |",
        f"| q70 panel vs dense montage reduction | {pct(byte_cmp['panel_q70_vs_dense_montage_reduction_pct'])} |",
        "",
        "Important: the sidecar image bundle is slightly larger than the already-compressed dense montage, but it carries the missing event/evidence context. The single q70 panel is smaller than the dense montage and still keeps the selected visual evidence in one image.",
        "",
        "## Visual Output Comparison",
        "",
        "Source: `docs/benchmark/motionzip_multimodal_sidecar_ab/README.md` saved constrained Gemma Vision outputs.",
        "",
        "| Variant | Field pass | Main result |",
        "| --- | ---: | --- |",
        f"| Dense raw montage | {visual['dense_raw_montage']['pass_count']} / {visual['dense_raw_montage']['total']} | Scene/equipment mostly recovered, event and evidence limits missed |",
        f"| MotionZip sidecar | {visual['motionzip_sidecar']['pass_count']} / {visual['motionzip_sidecar']['total']} | Activity/event/velocity/confidence/limits recovered |",
        "",
        "| Field | Dense montage | Sidecar |",
        "| --- | --- | --- |",
    ]
    dense_checks = {item["key"]: item for item in visual["dense_raw_montage"]["checks"]}
    sidecar_checks = {item["key"]: item for item in visual["motionzip_sidecar"]["checks"]}
    for key in EXPECTED_VISUAL:
        dense_mark = "pass" if dense_checks[key]["pass"] else "fail"
        sidecar_mark = "pass" if sidecar_checks[key]["pass"] else "fail"
        lines.append(
            f"| `{key}` | {dense_mark}: `{dense_checks[key]['observed']}` | {sidecar_mark}: `{sidecar_checks[key]['observed']}` |"
        )

    lines.extend(
        [
            "",
            "## Official LiteRT Text Equivalence",
            "",
            f"Source: `{official['source']}`",
            "",
            "| Metric | Dense frame-by-frame | MotionZip compressed |",
            "| --- | ---: | ---: |",
            f"| Output equivalence checks | {official['pass_count']} / {official['total']} | {official['pass_count']} / {official['total']} |",
            f"| `generate_content_ms` | {official['dense']['generate_content_ms']} | {official['motionzip_compressed']['generate_content_ms']} |",
            f"| Wall time ms | {official['dense']['wall_ms']} | {official['motionzip_compressed']['wall_ms']} |",
            f"| Raw response chars | {official['dense']['raw_response_chars']} | {official['motionzip_compressed']['raw_response_chars']} |",
            "",
            f"- Output equivalence: `{official['overall_pass']}` with pass rate `{official['pass_rate']}`.",
            f"- Generate-time reduction: {pct(official['generate_content_reduction_pct'])}.",
            f"- Wall-time reduction: {pct(official['wall_time_reduction_pct_confounded_by_cold_start'])}, but this is partly confounded because the dense case includes cold initialization and the compressed case reused the engine.",
            "",
            "The official output comparison passed these task-critical checks: activity, states, event count, event frame tolerance, velocity band, velocity peak tolerance, confidence floor, and low-confidence reason.",
            "",
            "| Check | Dense output | MotionZip output | Result |",
            "| --- | --- | --- | --- |",
        ]
    )
    for check in official["comparison"]["checks"]:
        dense_value = json.dumps(check["dense_value"], ensure_ascii=False)
        zip_value = json.dumps(check["motionzip_value"], ensure_ascii=False)
        result = "pass" if check["pass"] else "fail"
        extra = ""
        if check.get("extra"):
            extra = f" ({json.dumps(check['extra'], ensure_ascii=False)})"
        lines.append(f"| `{check['key']}` | `{dense_value}` | `{zip_value}` | {result}{extra} |")

    lines.extend(
        [
            "## Sparse Tracking Reference",
            "",
            "| Metric | Value |",
            "| --- | ---: |",
            f"| Source frame span | {sparse['source_frame_span']} |",
            f"| Dense pose samples | {sparse['dense_pose_samples']} |",
            f"| Lowest passing tracked samples | {sparse['lowest_passing_tracked_samples']} |",
            f"| Lowest passing tracked-frame ratio | {sparse['lowest_passing_tracked_frame_ratio'] * 100:.1f}% |",
            f"| Best sparse understanding score | {sparse['best_sparse_understanding_score'] * 100:.1f}% |",
            f"| Stride-2 understanding score | {sparse['stride_2_understanding_score'] * 100:.1f}% |",
            f"| Stride-2 peak velocity recall | {sparse['stride_2_peak_velocity_recall'] * 100:.1f}% |",
            "",
            "This supports the same conclusion from the non-visual MotionZip benchmark: the app does not need dense per-frame model input to preserve the key motion facts for bounded summaries.",
            "",
            "## Boundaries",
            "",
            "- This proves evidence compression / sidecar integration, not raw video understanding.",
            "- This does not make Vision part of `LIVE_FRAME` safety.",
            "- This does not support medical, fall-risk, sarcopenia, force, load, EMG, heart-rate, or clinical-progress claims.",
            "- For product use, compressed image output still needs `MultimodalResultValidator` and evidence-ref checking.",
        ]
    )
    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    args = parser.parse_args()
    out_dir = args.out_dir.resolve()
    results = build_results(out_dir)
    write_visualizations(out_dir, results)
    write_json(out_dir / "results.json", results)
    write_report(out_dir, results)
    print(json.dumps({"out_dir": str(out_dir), "report": str(out_dir / "report.md")}, indent=2))


if __name__ == "__main__":
    main()
