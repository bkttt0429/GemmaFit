# GemmaFit Task Roadmap

Current date: 2026-05-03  
Deadline: 2026-05-18  
Current route: **Main Track + Safety & Trust**

GemmaFit is no longer positioned as a single-exercise analyzer, a universal posture judge, or a medical diagnosis tool. The MVP is a trustworthy multi-exercise movement quality feedback system:

```text
Pose -> Motion Trace -> Exercise Template -> Structured Metrics
-> Trust Matrix -> Evidence Card -> Safe Gemma Feedback
```

Core claim:

```text
GemmaFit provides trustworthy motion feedback that knows its limits.
```

## Phase Overview

```text
Phase 0  Environment and assets                 Day 0-1    100% complete
Phase 1  Python biomechanics prototypes          Day 1-4    100% complete  202/202 PASS
Phase 2  Native C++ core prototypes              Day 5-6    100% complete  ctest 4/4
Phase 3  Multi-exercise Prototype Dashboard      Day 7-12   100% complete  exercises/core.py 13/13 PASS
Phase 4  Android + Trust integration             Day 13-15  in progress / partial complete
Phase 5  Demo, writeup, media gallery            Day 16-19  pending
```

## Phase 0 - Environment and Assets

### Environment

- [x] Conda env `gemmafit` with Python 3.11.
- [x] `mediapipe==0.10.14`, `opencv-python`, `numpy<2`, `huggingface_hub`.
- [x] Project instructions captured in `AGENTS.md`.
- [ ] Verify VS Code interpreter points to `gemmafit`.

### Models

- [x] `gemma-4-E4B-it-Q4_K_M.gguf` downloaded to `models/`.
- [x] `gemma-4-E2B-it-Q4_K_M.gguf` downloaded as backup.
- [ ] LiteRT / Google AI Edge model feasibility spike.
- [ ] Verify desktop or Android local Gemma smoke test.
- [ ] Keep `llama.cpp + GGUF` as fallback if LiteRT / AI Edge is not ready.

### Assets and Datasets

- [x] Squat Wikimedia video available.
- [x] Push-up CDC/Wikimedia video available.
- [x] Zenodo Squat Dataset available for static image benchmark.
- [x] Kaggle Squat Pose Dataset available.
- [x] Exercise Recognition Time Series available.
- [x] Lunge public asset available or fallback recorded.
- [x] Deadlift public asset available or fallback recorded.
- [x] `test_assets/asset_manifest.json` updated for demo assets.

## Phase 1 - Completed Python Prototypes

### Core Prototypes

- [x] `squat_prototype.py`: MediaPipe + initial visual feedback prototype.
- [x] `extract_landmarks.py`: video/image/CSV to 33-landmark CSV.
- [x] `compute_angles.py`: landmarks to angles and flags; 36 tests pass; FPPA and Rule 6 deg/s included.
- [x] `visualize_angles.py`: skeleton and angle overlay.
- [x] `test_angles.py`: 36 PASS.

### Biomechanics and Algorithm Work

| ID | Task | Deliverable | Status |
| --- | --- | --- | --- |
| R1 | De Leva segment mass table | COM tracker segment weighting | [x] |
| R2 | Base of Support convex hull | COM vs BoS logic | [x] |
| R3 | Knee valgus / FPPA references | Rule 1 prototype basis | [x] |
| R4 | Spine / trunk deviation references | Rule 2 and Rule 8 prototype basis | [x] |
| R5 | Joint ROM / overextension references | Rule 3 and Rule 7 basis | [x] |
| R6 | NASM wording constraints | Non-diagnostic coaching language | [x] |
| A1 | Savitzky-Golay angular velocity smoothing | `smooth_angle.py`, Rule 6 `600 deg/s` | [x] |
| A2 | Physical movement pattern prototype | `movement_classifier_prototype.py` | [x] |
| A3 | Rep Counter state machine | `rep_counter.py` | [x] |

### Validation

- [x] `validate_thresholds.py`: Zenodo threshold summary.
- [x] `report_zenodo_benchmark.py`: full Zenodo benchmark report.
- [x] Zenodo full result: Rule 2 Bad Back P=1.000, R=0.072, F1=0.134.
- [x] Zenodo full result: Bad Heel proxy P=0.711, R=0.880, F1=0.787.
- [x] `com_tracker_prototype.py`: De Leva COM + BoS convex hull.
- [x] `movement_classifier_prototype.py`: 35 PASS.
- [x] `muscle_focus_prototype.py`: 15 PASS.
- [x] `test_phase1_showcase.py`: 202/202 PASS.
- [x] `test_8rules.py`: 67 PASS.

Important benchmark note:

- [x] Zenodo is image-only and must not be used to validate Rule 6 timing.
- [x] FPPA / knee valgus should not be treated as validated on side-view Zenodo images.

## Phase 2 - Completed Native C++ Core

### Build and Tests

- [x] `native/CMakeLists.txt`: C++17 desktop build.
- [x] Native ctest: 4/4 pass (`test_com_tracker`, `test_safety_monitor`, `test_kinematics_pipeline`, `test_motion_quality`).
- [x] Android NDK cross-compile verified through `assembleDebug`.

### Native Modules

| ID | Module | Role | Status |
| --- | --- | --- | --- |
| L3.1 | `joint_angles.cpp` | joint angle computation | [x] |
| L3.2 | `body_segments.cpp` | body segment vectors | [x] |
| L3.3 | `symmetry.cpp` | bilateral symmetry score | [x] |
| L3.4 | `com_tracker.cpp` | De Leva COM + BoS | [x] |
| L3.5 | `safety_monitor.cpp` | 8 rule prototype checks | [x] |
| L3.6 | `movement_classifier.cpp` | physical movement pattern | [x] |
| L3.6b | `motion_quality.cpp` | exercise template detection, template metrics, applicability gates, structured report | [x] |
| L3.7 | `muscle_focus.cpp` | pose-based muscle focus estimate | [x] |
| L3.8 | `confidence_gate.cpp` | visibility/confidence gate | [x] |

### Bridges

- [x] `native/bridge/kinematics_bridge.cpp`: `float[99]` to JSON report prototype.
- [x] Native bridge emits first Android `motion_report` payload with exercise, metrics, quality flags, and not-applicable notes.
- [x] `native/bridge/llm_bridge.cpp`: JSON to local LLM / FC path prototype; Rule 6 aligned to `600 deg/s`.

## Phase 3 - Multi-exercise Prototype Dashboard

**Complete.** Exercise templates, heuristic detector, applicability gates, StructuredMotionReport, mock_gemma_feedback, and dashboard_v3.py are implemented and smoke-tested. Phase 4 is now the current priority.

### P0 Implementation Tasks

- [x] Define exercise templates for `squat`, `push_up`, `lunge`, and `deadlift`.
- [x] Implement heuristic auto-detection scoring with session-level auto-lock for single-exercise demo clips.
- [x] Implement template-specific metric extractor.
- [x] Implement applicability gate with:
  - `NOT_APPLICABLE`
  - `LOW_CONFIDENCE`
  - `VIEW_LIMITED`
  - `MONITOR`
  - `WARNING`
  - `CRITICAL`
- [x] Update Dashboard to show:
  - auto-detected exercise and confidence
  - skeleton overlay
  - joint angle / trajectory charts
  - active quality feedback
  - not applicable / low confidence explanations
  - mock Gemma per-frame message
- [x] Implement `mock_gemma_feedback` from structured JSON.
- [x] Export structured motion report JSON.

### Exercise Template Metrics

| Exercise | Metrics | Priority |
| --- | --- | --- |
| Squat | depth, knee_angle, hip_angle, trunk_lean, tempo | P0 |
| Push-up | elbow_angle, body_line, hip_sag, push_up_depth, tempo | P0 |
| Lunge | front_knee_angle, step_length_proxy, trunk_uprightness, stability | P0 |
| Deadlift | hip_hinge, trunk_angle, bar/body path proxy, tempo | P1 bonus |

### Applicability Gate Tasks

- [x] Rule 1 FPPA only active on frontal or near-frontal lower-body templates.
- [x] Rule 4 asymmetry only single-frame active on bilateral templates.
- [x] Lunge/unilateral phases must not emit critical asymmetry from one frame.
- [x] Rule 5 COM/BoS dynamic movement becomes monitor, not critical.
- [x] Rule 6 remains `600 deg/s`, with smoothing and consecutive-frame confirmation.
- [x] Unknown or mixed exercise must suppress template-specific warnings.

### Phase 3 Acceptance Criteria

- [x] Squat, Push-up, and Lunge templates run in Prototype Dashboard.
- [x] Deadlift bonus template works if asset is available.
- [x] Dashboard does not show unrelated hard safety rules as critical.
- [x] Mock Gemma message is generated from structured JSON.
- [x] Existing Phase 1 tests still pass.
- [x] Native ctest remains green.

## Phase 4 - Android + Trust Integration

**In progress / partial complete.** Android has native `motion_report`, Trust Matrix, Evidence Card, quality flags, and unsupported judgment display wired. True local Gemma, Android rep counting, and temporal Rule 6 integration remain open.

### Completed Android Tasks

- [x] Android NDK cross-compile verified with `assembleDebug`.
- [x] `KinematicsBridge.kt` receives native `motion_report`.
- [x] Android state parses:
  - `exercise`
  - `exercise_confidence`
  - `template_metrics`
  - `quality_flags`
  - `not_applicable`
- [x] Workout UI first version displays quality gate status.
- [x] Native `motion_quality` module implements exercise detection, template metrics, applicability gates, and structured motion report JSON.
- [x] Workout UI displays Trust Matrix status chips.
- [x] Workout UI displays Evidence Card details.
- [x] Unsupported judgments display includes `joint_force`, `clinical_injury_risk`, `medical_diagnosis`, and `muscle_activation_percentage`.
- [x] `FunctionRegistry` Rule 6 schema text aligned to `600 deg/s`.
- [x] Debug APK builds, installs, and launches on Pixel 8 Pro.

### Phase 4 P0 Tasks

- [x] Trust Matrix UI in Android and dashboard.
- [x] Evidence Card JSON.
- [x] Evidence Card UI.
- [x] Unsupported judgments display:
  - `joint_force`
  - `clinical_injury_risk`
  - `medical_diagnosis`
  - `muscle_activation_percentage`
- [ ] Safe Gemma prompt template.
- [ ] LiteRT / Google AI Edge feasibility spike.
- [ ] Local Gemma smoke test on Android or desktop.
- [ ] Correct refusal demo clips.
- [ ] Android rep counter state machine integration.
- [ ] Android Savitzky-Golay / Rule 6 temporal smoothing integration.

### Phase 4 P1 Tasks

- [ ] LiteRT / Google AI Edge local Gemma feedback.
- [ ] Android TTS for trust-aware coaching with cooldown.
- [ ] Session-level Evidence Card summary.
- [ ] Performance numbers:
  - device
  - model size
  - latency
  - tokens/sec
  - FPS
  - memory
  - offline mode

### Fallback Local Inference

- [ ] `llama.cpp + GGUF` fallback local inference if LiteRT / AI Edge is blocked.
- [ ] Keep existing E2B/E4B GGUF models in `models/` as fallback assets.

## Safety & Trust Benchmark Tasks

The Safety & Trust benchmark must include correct judgments and correct refusals.

| Scenario | Expected behavior | Status |
| --- | --- | --- |
| Frontal squat | Knee alignment gate may be active. | [ ] |
| Side-view squat | Knee valgus / FPPA must be `NOT_APPLICABLE`. | [ ] |
| Occluded body | `LOW_CONFIDENCE`; no warning or critical risk grade. | [ ] |
| Dynamic lift COM/BoS | COM is `MONITOR`, not critical. | [ ] |
| Lunge | Bilateral asymmetry is `MONITOR` or not applicable, not single-frame critical. | [ ] |
| Deadlift | Hip hinge and bar/body path proxy allowed; lumbar force refused. | [ ] |
| Non-exercise / mixed clip | `VIEW_LIMITED` or unknown; suppress template-specific warnings. | [ ] |

Benchmark table to produce:

| Rule / Metric | Dataset or Video Type | Expected | Result | Status |
| --- | --- | --- | --- | --- |
| Trunk lean | side squat | detectable | TBD | usable |
| Bar/body path proxy | side squat/deadlift | detectable | TBD | usable |
| Knee valgus | side view | not valid | TBD | safety pass if refused |
| COM/BoS dynamic | dynamic lift | monitor only | TBD | safety pass if downgraded |
| Rule 6 angular velocity | video/time-series | smoothing required | TBD | usable after temporal integration |

## Phase 5 - Demo, Writeup, Media Gallery

### YouTube Demo

- [ ] 0:00-0:15 problem: pose apps often overclaim from single-camera proxies.
- [ ] 0:15-0:35 GemmaFit: local-first trustworthy movement feedback.
- [ ] 0:35-1:05 correct judgment: side squat trunk/path/tempo evidence.
- [ ] 1:05-1:30 correct refusal: side-view knee valgus is `NOT_APPLICABLE`.
- [ ] 1:30-1:55 low-quality clip: `LOW_CONFIDENCE`, no risk verdict.
- [ ] 1:55-2:20 local Gemma or fallback local inference feedback.
- [ ] 2:20-2:45 Safety & Trust matrix and Evidence Card.
- [ ] 2:45-3:00 closing: "GemmaFit does not pretend to know more than the evidence supports."

### Kaggle Deliverables

- [ ] Kaggle Writeup, <=1500 words.
- [ ] GitHub public repo cleanup.
- [ ] Media Gallery cover image.
- [ ] Live Demo APK.
- [ ] Safety & Trust benchmark table.
- [ ] Local inference performance table.
- [ ] Unsloth / FC benchmark only if time allows.

## Current Completed Evidence

| Item | Status | Evidence |
| --- | --- | --- |
| Conda `gemmafit` env | complete | Python 3.11 + MediaPipe 0.10.14 |
| Public datasets | complete | Zenodo, squat_kaggle, exercise_timeseries |
| GGUF model files | complete | E4B + E2B in `models/` |
| `compute_angles.py` | complete | 36 PASS, FPPA + Rule 6 deg/s |
| `smooth_angle.py` | complete | Savitzky-Golay, 600 deg/s |
| `rep_counter.py` | complete | state machine demo |
| `movement_classifier_prototype.py` | complete | 35 PASS |
| `muscle_focus_prototype.py` | complete | 15 PASS |
| `com_tracker_prototype.py` | complete | De Leva + BoS |
| `test_8rules.py` | complete | 67 PASS |
| `test_phase1_showcase.py` | complete | 202/202 PASS |
| Zenodo benchmark | complete | Rule 2 P=1.000/R=0.072, Bad Heel F1=0.787 |
| Native C++ core | complete | current kinematics modules + `motion_quality.cpp` |
| Native ctest | complete | 4/4 pass |
| `generate_synthetic.py` | complete | 600 FC examples, train=510/val=90 |
| `exercises/core.py` | complete | squat/push_up/lunge/deadlift templates, gates, mock_gemma, 13/13 PASS |
| `dashboard_v3.py` | complete | Phase 3 Streamlit dashboard, smoke test PASS |
| Android app | partial complete | native `motion_report`, Trust Matrix, Evidence Card, debug APK build/install/run |
| Function schema | complete | Rule 6 aligned to `600 deg/s` |
| Demo/writeup | pending | Phase 5 |

## MVP vs Bonus

| Priority | Item |
| --- | --- |
| P0 | Squat / Push-up / Lunge templates |
| P0 | Heuristic exercise auto-detection |
| P0 | Prototype Dashboard visualization |
| P0 | `NOT_APPLICABLE / LOW_CONFIDENCE / VIEW_LIMITED` |
| P0 | Trust Matrix UI |
| P0 | Evidence Card JSON and UI |
| P0 | Unsupported judgments display |
| P0 | Correct refusal demo |
| P1 | LiteRT / Google AI Edge local Gemma feedback |
| P1 | Android TTS integration |
| P1 | Rep counter and Rule 6 temporal integration on Android |
| Fallback | llama.cpp + GGUF local inference |
| P2 | Unsloth fine-tune benchmark |
| Research only | Multi-view 3D reconstruction |
| Out of scope | Precise force / inverse dynamics |
| Out of scope | Medical-grade diagnosis |

## Safety Language Checklist

- [ ] Use "movement quality feedback", not "medical diagnosis".
- [ ] Use "pose-based estimate", not "measured muscle activation".
- [ ] Use "single-camera proxy", not "precise joint force".
- [ ] Mark unsupported views as `VIEW_LIMITED`.
- [ ] Mark unsupported metrics as `NOT_APPLICABLE`.
- [ ] Display `unsupported_judgments` explicitly.
- [ ] Refuse or mark out of scope:
  - `joint_force`
  - `clinical_injury_risk`
  - `medical_diagnosis`
  - `muscle_activation_percentage`
- [ ] Keep Rule 6 as `600 deg/s`.
- [ ] Do not use Zenodo image data as Rule 6 validation.
