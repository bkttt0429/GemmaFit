# prototype/ — Python validation lab

The prototype layer is the fast iteration sandbox for GemmaFit. Every algorithm
is validated here before being moved to C++ ([native/kinematics/](../native/kinematics/))
or wired into the Android app. Files are flat at the top level by historical
convention; this index groups them by purpose.

## How to run

```bash
# Phase 1 / 8-rule sanity (no video required)
python prototype/test_phase1_showcase.py
python prototype/test_8rules.py
python prototype/test_layer2_fsm.py

# Live video pipeline smokes (write to prototype/outputs/<name>_smoke/)
python prototype/test_subject_selector_video.py
python prototype/test_smoothed_classifier_video.py
python prototype/test_regional_confidence_video.py
python prototype/validate_kalman_tracking_video.py --video test_assets/videos/synthetic_two_person_squat.mp4

# Live-stream desktop simulator (cv2 window or --headless)
python prototype/live_stream_simulator.py test_assets/videos/squat_wikimedia_01.webm

# Streamlit dashboard
streamlit run prototype/dashboard_v3.py

# Eval Gemma FC checkpoint against fc_training_data.json::validation
python prototype/eval_compare.py --base models/gemma4-e4b-Q4_K_M.gguf \
                                  --ft   models/gemmafit-q4_k_m.gguf  --n 90
```

`prototype/outputs/` is gitignored. Test fixtures and ground-truth videos live
under [test_assets/](../test_assets/).

## Module index

### Pose detection & multi-person tracking

| File | Role |
| --- | --- |
| [`multi_person_pose.py`](multi_person_pose.py) | MediaPipe Tasks `PoseLandmarker` wrapper (VIDEO mode, num_poses up to 4). Replaces legacy `mp.solutions.pose`. |
| [`person_proposals.py`](person_proposals.py) | Shared pose-to-person proposal helper: presence gate, expanded full-person bbox, proposal NMS, and bbox attachment for tracker/ReID crops. |
| [`validate_person_detection_video.py`](validate_person_detection_video.py) | Person proposal audit: converts pose landmarks into presence-gated full-person boxes and validates person / no-person video behavior before identity tracking. |
| [`subject_selector.py`](subject_selector.py) | Python port of [native/kinematics/subject_selector.cpp](../native/kinematics/subject_selector.cpp). Auto-pick + persistence + Kalman re-match (opt-in). |
| [`target_reid_roi_tracker_experiment.py`](target_reid_roi_tracker_experiment.py) | Prototype-only target re-ID / ROI-tracker stress test for overlap clips. ROI trackers are evaluated as short-term proposals, not accepted as identity truth. |
| [`target_active_focus_v2.py`](target_active_focus_v2.py) | Python-first active-focus v2 prototype: part ReID, pose continuity, ROI prediction, detector-burst hook, and hold-before-wrong-skeleton gating. |
| [`synthesize_two_person_video.py`](synthesize_two_person_video.py) | Side-by-side tile two single-person clips into a synthetic multi-person test video. |
| [`live_stream_simulator.py`](live_stream_simulator.py) | Desktop equivalent of the Android camera path: PoseLandmarker LIVE_STREAM + async result callback + cv2 overlay. |

### Exercise classification

| File | Role |
| --- | --- |
| [`exercises/core.py`](exercises/core.py) | Heuristic 4-class detector (squat / push_up / lunge / deadlift), templates, applicability gates, `SmoothedExerciseDetector` (sliding-window majority vote). |
| [`exercise_templates.py`](exercise_templates.py) | Older flat template definitions kept for back-compat. |
| [`movement_classifier.py`](movement_classifier.py) | Physical movement-pattern feature extraction. |
| [`movement_classifier_prototype.py`](movement_classifier_prototype.py) | Earlier prototype of the same. |

### Layer 2 sequence baseline

| File | Role |
| --- | --- |
| [`layer2_fsm.py`](layer2_fsm.py) | Deterministic temporal FSM for senior-mode baseline phases, rep events, balance-hold events, and abstain gates before training a Layer 2 model. |
| [`layer2_dataset_recorder.py`](layer2_dataset_recorder.py) | JSONL recorder for derived feature windows plus FSM weak labels; rejects raw video, crops, embeddings, histograms, and raw landmarks. |

### Kinematics primitives

| File | Role |
| --- | --- |
| [`compute_angles.py`](compute_angles.py) | 12 joint angles + FPPA + ROM helpers; ground truth for the C++ port. |
| [`smooth_angle.py`](smooth_angle.py) | Savitzky-Golay smoothing + angular velocity (Rule 6 ≥ 600 °/s). |
| [`rep_counter.py`](rep_counter.py) | TOP→DESCENT→BOTTOM→ASCENT state machine. |
| [`com_tracker_prototype.py`](com_tracker_prototype.py) | de Leva 1996 weighted COM + base-of-support convex hull. |
| [`muscle_focus_prototype.py`](muscle_focus_prototype.py) | Pose-based muscle-focus estimate (NOT EMG). |
| [`de_leva.py`](de_leva.py) | de Leva segment inertia parameters. |
| [`applicability_gate.py`](applicability_gate.py) | Per-template applicability rules. |
| [`regional_confidence.py`](regional_confidence.py) | Per-region (`upper_body`, `lower_body`, `torso`) visibility gate; replaces the simple mean-visibility check. |

### Detection / safety

| File | Role |
| --- | --- |
| [`squat_prototype.py`](squat_prototype.py) | Squat-specific detection scaffold (legacy). |

### LLM glue

| File | Role |
| --- | --- |
| [`mock_gemma_feedback.py`](mock_gemma_feedback.py) | Deterministic mock feedback used by the dashboard before a real Gemma is loaded. |
| [`gemma_local.py`](gemma_local.py) | Local llama-cpp-python inference helper. |
| [`eval_compare.py`](eval_compare.py) | A/B benchmark harness for base vs fine-tuned GGUF (production / training prompt formats). |
| [`refusal_eval.py`](refusal_eval.py) | Refusal-scenario benchmark (in flight). |

### Demo / rendering

| File | Role |
| --- | --- |
| [`dashboard_v3.py`](dashboard_v3.py) | Streamlit dashboard showing skeleton overlay, joint trails, Evidence Card, unsupported judgments. |
| [`render_demo_video.py`](render_demo_video.py) | Annotated MP4 renderer for demo clips. |
| [`render_formula_visualizations.py`](render_formula_visualizations.py) | Formula-derivation visuals for the writeup. |
| [`generate_media_gallery.py`](generate_media_gallery.py) | Builds the Kaggle Media Gallery cover image. |
| [`visualize_angles.py`](visualize_angles.py) | One-off joint-angle visualizer. |

### Data prep

| File | Role |
| --- | --- |
| [`extract_landmarks.py`](extract_landmarks.py) | Image / video / CSV → 33-keypoint CSV pipeline (legacy `mp.solutions.pose`; superseded by `multi_person_pose.py` for new work). |
| [`run_zenodo_squat_dataset.py`](run_zenodo_squat_dataset.py) | Phase 1 Zenodo squat benchmark runner. |
| [`report_zenodo_benchmark.py`](report_zenodo_benchmark.py) | Aggregates Zenodo benchmark per-image outputs into a summary. |
| [`validate_thresholds.py`](validate_thresholds.py) | Threshold sweep / calibration helper. |
| [`validate_formula_video.py`](validate_formula_video.py) | Formula vs reference-video sanity check. |

### Tests / smokes

| File | Scope |
| --- | --- |
| [`test_phase1_showcase.py`](test_phase1_showcase.py) | Phase 1 unit tests — joint angles, COM, classifier (no video). |
| [`test_8rules.py`](test_8rules.py) | 8 safety rules unit tests. |
| [`test_angles.py`](test_angles.py) | Angle computation regression. |
| [`test_dashboard.py`](test_dashboard.py) | Dashboard rendering smoke. |
| [`test_exercise_override.py`](test_exercise_override.py) | Manual exercise override flow. |
| [`test_exercise_templates_videos.py`](test_exercise_templates_videos.py) | Template detection on demo MP4s. |
| [`test_subject_selector_video.py`](test_subject_selector_video.py) | Algorithm #1 — multi-person selection on synthetic 2-person clip + single-person clips. |
| [`test_smoothed_classifier_video.py`](test_smoothed_classifier_video.py) | Algorithm #3 — single-frame label flicker reduction (squat/push-up). |
| [`test_regional_confidence_video.py`](test_regional_confidence_video.py) | Algorithm #2 — per-region gate baseline + occluded-region tests. |
| [`test_kalman_rematch_video.py`](test_kalman_rematch_video.py) | Kalman re-match A/B vs heuristic re-match. |
| [`test_layer2_fsm.py`](test_layer2_fsm.py) | Layer 2 FSM and recorder unit smoke: reps, balance hold, abstain, JSONL weak labels, and raw-data rejection. |
| [`validate_kalman_tracking_video.py`](validate_kalman_tracking_video.py) | Full Kalman tracking video pass with overlay MP4 output. |
| [`validate_phase3.ipynb`](validate_phase3.ipynb) | Interactive Phase 3 verification notebook. |

## Outputs

`prototype/outputs/` (gitignored) — every test/validate script writes its
JSON traces and overlay MP4s here, organized by smoke name:

```
prototype/outputs/
  subject_selector_smoke/
  smoothed_classifier_smoke/
  regional_confidence_smoke/
  kalman_rematch_smoke/
  kalman_tracking_video/
  person_detection_video/
  target_reid_roi_tracker/
  target_active_focus_v2/
  live_stream_smoke/
  native_video_report/
```

## Contribution conventions

- New algorithm? Validate in Python here first, write a `test_<name>_video.py`
  that runs against [test_assets/videos/](../test_assets/videos/), then port to
  C++ ([native/kinematics/](../native/kinematics/)) once the parameters are
  pinned down.
- Tests that need MediaPipe live a video file should default to scenarios that
  point at [test_assets/videos/](../test_assets/videos/) so they are
  reproducible without re-downloading.
- Any test producing per-frame trace JSON should write to
  `prototype/outputs/<smoke_name>/` — never to repo root.
