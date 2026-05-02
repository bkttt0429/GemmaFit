"""
test_exercise_templates_videos.py — Quick validation of template-aware analysis
on downloaded Wikimedia Commons videos.

Usage:
    python prototype/test_exercise_templates_videos.py
"""

import sys
import os

proto_dir = os.path.dirname(os.path.abspath(__file__))
if proto_dir not in sys.path:
    sys.path.insert(0, proto_dir)

from test_dashboard import extract_video_frames_cached, analyze_all_frames_cached
import cv2

UPLOAD_DIR = os.path.join(os.path.dirname(proto_dir), "test_assets", "videos")

VIDEOS = {
    "squat": os.path.join(UPLOAD_DIR, "squat_wikimedia_01.webm"),
    "push_up": os.path.join(UPLOAD_DIR, "pushup_cdc_01.webm"),
    "deadlift": os.path.join(UPLOAD_DIR, "deadlift_demo.webm"),
    "lunge": os.path.join(UPLOAD_DIR, "lunge_kettlebell.webm"),
}


def test_video(name: str, path: str, sample_every: int = 3):
    print(f"\n{'='*60}")
    print(f"Testing: {name.upper()}")
    print(f"File: {path}")
    
    if not os.path.exists(path):
        print(f"[SKIP] File not found: {path}")
        return
    
    # Basic OpenCV check
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        print(f"[FAIL] Cannot open video with OpenCV")
        return
    fps = cap.get(cv2.CAP_PROP_FPS)
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    cap.release()
    print(f"Video info: {w}x{h}, {fps:.1f}fps, {total_frames} frames (~{total_frames/fps:.1f}s)")
    
    # Extract frames with MediaPipe
    print(f"Extracting frames (sample every {sample_every})...")
    frames, vid_fps = extract_video_frames_cached(path, sample_every)
    print(f"Extracted {len(frames)} landmark frames")
    
    if not frames:
        print("[FAIL] No landmarks detected — MediaPipe could not find pose")
        return
    
    # Analyze
    print("Analyzing frames...")
    analyses, rep_counter = analyze_all_frames_cached(frames, vid_fps)
    print(f"Analyzed {len(analyses)} frames")
    
    # Exercise detection summary
    from collections import Counter
    ex_counts = Counter(a.exercise for a in analyses)
    print(f"\nExercise detection distribution:")
    for ex, count in ex_counts.most_common():
        pct = count / len(analyses) * 100
        avg_conf = sum(a.exercise_confidence for a in analyses if a.exercise == ex) / count
        print(f"  {ex}: {count} frames ({pct:.0f}%) | avg confidence: {avg_conf:.2f}")
    
    # Gate result summary
    status_counts = Counter()
    not_app_counts = Counter()
    flag_counts = Counter()
    
    for a in analyses:
        if a.gate_result:
            status_counts[a.gate_result.overall_status] += 1
            for f in a.gate_result.not_applicable:
                not_app_counts[f.id] += 1
            for f in a.gate_result.quality_flags:
                flag_counts[f.id] += 1
    
    print(f"\nGate overall status distribution:")
    for status, count in status_counts.most_common():
        pct = count / len(analyses) * 100
        print(f"  {status}: {count} frames ({pct:.0f}%)")
    
    if not_app_counts:
        print(f"\nMost common NOT_APPLICABLE flags:")
        for flag_id, count in not_app_counts.most_common(5):
            print(f"  {flag_id}: {count} frames")
    
    if flag_counts:
        print(f"\nMost active quality flags:")
        for flag_id, count in flag_counts.most_common(5):
            print(f"  {flag_id}: {count} frames")
    
    # Mock feedback samples
    print(f"\nSample coaching feedback:")
    shown = 0
    last_msg = None
    for a in analyses:
        msg = a.mock_feedback.get("message", "")
        if msg and msg != last_msg and shown < 5:
            print(f"  Frame {a.frame_index} [{a.mock_feedback.get('priority', 'low')}]: {msg}")
            last_msg = msg
            shown += 1
    
    # Rep counter
    print(f"\nRep counter: {rep_counter.rep_count} reps")
    if rep_counter.history:
        print(f"  Last rep quality: {rep_counter.history[-1].form_quality:.0f}")
    
    # Template metrics sample
    sample = next((a for a in analyses if a.template_metrics), None)
    if sample:
        print(f"\nTemplate metrics sample (frame {sample.frame_index}, {sample.exercise}):")
        for k, v in sample.template_metrics.items():
            print(f"  {k}: {v:.2f}")
    
    print(f"\n[PASS] {name.upper()} analysis complete")


def main():
    print("GemmaFit Exercise Template Video Validation")
    print("Testing heuristic detection + applicability gates on real videos")
    
    for name, path in VIDEOS.items():
        test_video(name, path, sample_every=3)
    
    print(f"\n{'='*60}")
    print("All tests completed!")


if __name__ == "__main__":
    main()