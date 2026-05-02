"""
test_exercise_override.py — Verify gate behavior when exercise is manually overridden.

This tests the core value proposition: when user specifies the exercise,
the system correctly marks irrelevant rules as NOT_APPLICABLE and
produces exercise-specific metrics + coaching.
"""

import sys
import os

proto_dir = os.path.dirname(os.path.abspath(__file__))
if proto_dir not in sys.path:
    sys.path.insert(0, proto_dir)

from test_dashboard import extract_video_frames_cached, analyze_all_frames_cached

UPLOAD_DIR = os.path.join(os.path.dirname(proto_dir), "test_assets", "videos")

VIDEOS = {
    "squat": os.path.join(UPLOAD_DIR, "squat_wikimedia_01.webm"),
    "push_up": os.path.join(UPLOAD_DIR, "pushup_cdc_01.webm"),
    "deadlift": os.path.join(UPLOAD_DIR, "deadlift_demo.webm"),
    "lunge": os.path.join(UPLOAD_DIR, "lunge_kettlebell.webm"),
}


def test_override(exercise: str, path: str):
    print(f"\n{'='*60}")
    print(f"Override Test: {exercise.upper()}")
    
    if not os.path.exists(path):
        print(f"[SKIP] File not found")
        return
    
    frames, fps = extract_video_frames_cached(path, sample_every=5)
    if not frames:
        print("[FAIL] No landmarks detected")
        return
    
    analyses, rep_counter = analyze_all_frames_cached(frames, fps, exercise_override=exercise)
    
    # Check NOT_APPLICABLE flags
    not_app_summary = {}
    for a in analyses:
        if a.gate_result:
            for f in a.gate_result.not_applicable:
                not_app_summary[f.id] = not_app_summary.get(f.id, 0) + 1
    
    print(f"Frames analyzed: {len(analyses)}")
    print(f"NOT_APPLICABLE flags:")
    for flag_id, count in sorted(not_app_summary.items(), key=lambda x: -x[1]):
        pct = count / len(analyses) * 100
        print(f"  {flag_id}: {count}/{len(analyses)} ({pct:.0f}%)")
    
    # Check active flags
    active_summary = {}
    for a in analyses:
        if a.gate_result:
            for f in a.gate_result.quality_flags:
                active_summary[f.id] = active_summary.get(f.id, 0) + 1
    
    if active_summary:
        print(f"Active quality flags:")
        for flag_id, count in sorted(active_summary.items(), key=lambda x: -x[1])[:5]:
            pct = count / len(analyses) * 100
            print(f"  {flag_id}: {count}/{len(analyses)} ({pct:.0f}%)")
    else:
        print("No active quality flags")
    
    # Template metrics sample
    sample = next((a for a in analyses if a.template_metrics), None)
    if sample:
        print(f"Template metrics sample:")
        for k, v in sample.template_metrics.items():
            print(f"  {k}: {v:.2f}")
    
    # Coaching feedback sample
    shown = 0
    last_msg = None
    for a in analyses:
        msg = a.mock_feedback.get("message", "")
        if msg and msg != last_msg and shown < 3:
            print(f"Coach: [{a.mock_feedback.get('priority')}] {msg}")
            last_msg = msg
            shown += 1
    
    # Key assertions
    errors = []
    
    if exercise == "push_up":
        if "knee_valgus_fppa" not in not_app_summary:
            errors.append("knee_valgus_fppa should be NOT_APPLICABLE for push_up")
    
    if exercise == "lunge":
        if "bilateral_asymmetry" not in not_app_summary:
            errors.append("bilateral_asymmetry should be NOT_APPLICABLE for lunge")
    
    if exercise == "deadlift":
        if "knee_valgus_fppa" not in not_app_summary:
            errors.append("knee_valgus_fppa should be NOT_APPLICABLE for deadlift")
    
    if errors:
        print(f"[FAIL] {'; '.join(errors)}")
    else:
        print(f"[PASS] All gate assertions passed")


def main():
    print("GemmaFit Exercise Override Gate Validation")
    print("Testing NOT_APPLICABLE correctness per template")
    
    for ex, path in VIDEOS.items():
        test_override(ex, path)
    
    print(f"\n{'='*60}")
    print("All override tests completed!")


if __name__ == "__main__":
    main()