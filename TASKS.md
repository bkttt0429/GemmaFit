# GemmaFit Task Roadmap

Current date: 2026-05-05
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

## Current Status - 2026-05-05

**Current milestone:** Phase 4 Android + Trust integration is now past the
basic Evidence Card stage. The latest completed slice is the first working
**Capability-Bounded Evidence Router** contract:

```text
Native motion_quality
-> capability_contract + evidence_dag
-> Android parse + session aggregation
-> Summary-only Gemma prompt
-> evidence-ref / capability validation fallback
```

### Done in the latest slice

- [x] Native `MotionQualityReport` now includes additive
  `capability_contract` and `evidence_dag` JSON.
- [x] Native contract declares `can_judge` / `cannot_judge`,
  `required_evidence`, `confidence_ceiling`, and `evidence_refs`.
- [x] Native Evidence DAG includes metric, visibility, quality-gate,
  safety-rule, not-applicable, and capability nodes.
- [x] Side-view squat can still judge depth / tempo / trunk evidence while
  blocking frontal knee valgus.
- [x] Low-confidence frames block hard form judgment instead of producing a
  hard warning.
- [x] Android parses `capability_contract` and `evidence_dag` without breaking
  old JSON.
- [x] `SessionCoachRenderer` sends compact capability/evidence context to the
  summary-only Gemma path.
- [x] Local model output is rejected if it cites missing `evidence_refs`.
- [x] Local model output is rejected if it selects a tool for a metric listed
  in `cannot_judge`.
- [x] Docs now describe Capability Contract, Evidence DAG, summary-only Gemma,
  and evidence-first UX.
- [x] Fine-tune docs now reserve v3 for the evidence-router dataset instead of
  mutating v2 benchmark data.

### Verified

- [x] `cmake --build native\build_local --target test_motion_quality --config Release`
- [x] `.\native\build_local\Release\test_motion_quality.exe` -> 23/23 pass
- [x] `ctest --test-dir native\build_local -C Release --output-on-failure` -> 7/7 pass
- [x] `.\gradlew.bat :app:testDebugUnitTest --console=plain`
- [x] `.\gradlew.bat :app:assembleDebug --console=plain`
- [x] Scoped `git diff --check` on touched files

### Still open

- [ ] Full deep literature report import and citation cleanup.
- [ ] Product Claims matrix: `claim`, `source`, `allowed wording`,
  `forbidden wording`, `code/doc target`.
- [ ] Fine-tune v3 data generator for Capability Contract + Evidence DAG input.
- [ ] LiteRT-LM real model smoke on Pixel 8 Pro with `gemmafit-v2-fc.litertlm`
  or later v3 artifact.
- [ ] Summary UI deeper display of capability/evidence refs, beyond prompt/debug
  plumbing.
- [ ] Pixel 8 Pro acceptance run on `/sdcard/DCIM/GemmaFitTest/` correct/wrong
  videos.
- [ ] Cleanup of unrelated dirty build artifacts and existing `VideoStates.kt`
  EOF whitespace warning.

## Phase Overview

```text
Phase 0  Environment and assets                 Day 0-1    100% complete
Phase 1  Python biomechanics prototypes          Day 1-4    100% complete  202/202 PASS
Phase 2  Native C++ core prototypes              Day 5-6    100% complete  ctest 4/4
Phase 3  Multi-exercise Prototype Dashboard      Day 7-12   100% complete  exercises/core.py 13/13 PASS
Phase 4  Android + Trust integration             Day 13-15  in progress / contract layer complete
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

### Dataset Roadmap

Dataset work is split by product purpose. Do not mix demo footage,
biomechanics regression fixtures, and fine-tune rows into one unlabeled pool.
Every reusable asset must have a manifest entry with source, license,
local path, intended use, expected behavior, and known limitations.

| Priority | Dataset / Asset Set | Purpose | Storage | Next action |
| --- | --- | --- | --- | --- |
| P0 | Safety & Trust video benchmark v1 | Prove correct judgment and correct refusal for demo/writeup. | `test_assets/benchmarks/safety_trust_v1/` | Build `manifest.json` with clip id, source, view, expected statuses, and evidence refs. |
| P0 | Current public demo clips | Regression for squat, push-up, lunge, and deadlift templates. | `test_assets/videos/`, `test_assets/videos/internet_public*/` | Normalize source metadata into `test_assets/asset_manifest.json`; mark lunge/deadlift source attribution before final writeup. |
| P0 | Pixel 8 Pro acceptance clips | Final on-device APK smoke: correct/wrong movement, low confidence, no-person, and subject-lost cases. | Phone: `/sdcard/DCIM/GemmaFitTest/`; repo only stores derived reports unless clip is public/demo-safe. | Record or copy a small fixed set; export `motion_report` JSON and screenshots into `docs/benchmark/`. |
| P0 | Fine-tune v3 evidence-router dataset | Train/evaluate safe Gemma routing from Capability Contract + Evidence DAG, not raw pose. | `finetune/data/gemmafit_v3_evidence_router.json` | Generate from native report fixtures plus synthetic edge cases; keep v2 files unchanged. |
| P0 | No-person / occlusion / multi-person fixtures | Guard against stale skeletons, false candidates, and overconfident warnings. | `test_assets/videos/`, `prototype/outputs/`, native JSON fixtures | Add expected `LOW_CONFIDENCE`, `VIEW_LIMITED`, or `SUBJECT_LOST` assertions. |
| P1 | Zenodo Squat static image benchmark | Static threshold sanity check for squat back/heel proxies. Not valid for timing or Rule 6. | `test_assets/datasets/zenodo_squat_good_bad_back_bad_heel/` | Finish checksum/status cleanup and document which metrics are valid vs invalid. |
| P1 | Kaggle Squat Pose Dataset | Extra squat landmark diversity and threshold sensitivity tests. | `test_assets/datasets/` or ignored local cache | Use only if license and schema are documented; do not let it dominate multi-exercise claims. |
| P1 | Exercise Recognition Time Series | Temporal smoothing / rep-count / Rule 6 stress tests. | `test_assets/datasets/` or ignored local cache | Map columns to GemmaFit motion traces; use for timing behavior, not medical or force claims. |
| P1 | Senior-mode public clips | Chair sit-to-stand, supported squat, balance hold, and step-touch demo coverage. | `test_assets/videos/internet_public*/` | Keep as product demo and accessibility validation, not sarcopenia/fall-risk evidence. |
| P2 | User-recorded optional clips | Robustness and UX tuning after MVP. | Ignored local cache by default | Store raw video only with explicit consent; commit derived landmarks/reports when useful. |

Safety & Trust benchmark v1 should cover these minimum cases:

| Clip type | Required source | Expected behavior |
| --- | --- | --- |
| Side-view squat | Public or Pixel recording | Judge trunk/depth/tempo; refuse frontal knee valgus. |
| Frontal squat | Public or Pixel recording | Allow knee alignment only if lower-body confidence and view are sufficient. |
| Push-up side/crop | Public CDC/Wikimedia or Pixel recording | Classify as push-up from upper-body evidence; do not require lower-body visibility. |
| Lunge | Public or Pixel recording | Avoid single-frame critical asymmetry; focus front knee/trunk/stability. |
| Deadlift | Public or Pixel recording | Judge hip hinge/trunk/path proxy; refuse lumbar force or disc loading. |
| No-person / blank frame | Synthetic or recorded | No skeleton render; `LOW_CONFIDENCE:no_person_detected`. |
| Occluded / cropped body | Public, synthetic, or recorded | `LOW_CONFIDENCE` or `VIEW_LIMITED`; no hard safety verdict. |
| Multi-person | Synthetic or recorded | Stable subject selection; secondary candidates only render if presence gate passes. |

Fine-tune v3 must be generated only after native report contracts are stable:

```text
native motion_report fixture
-> compact Capability Contract + Evidence DAG
-> allowed / blocked tool decision
-> evidence_refs + selection_basis + refusal_level
-> chat-formatted train / validation splits
```

V3 acceptance rules:

- [ ] Keep `fc_training_data_chat.json` as the immutable v2 benchmark file.
- [ ] Add `gemmafit_v3_evidence_router.json` with separate run suffix
  `gemmafit_v3_evidence_router`.
- [ ] Include positive tool-call rows and refusal rows in the same schema.
- [ ] Include view-limited, low-confidence, not-applicable, and unsupported
  medical/force/muscle-activation prompts.
- [ ] Validation set must include exact evidence-ref checks, invalid tool
  rejection, and Traditional Chinese prompt wrappers.
- [ ] Do not include raw user video, private filenames, or free-form medical
  labels in fine-tune data.

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

**In progress / contract layer complete.** Android has native `motion_report`,
Trust Matrix, Evidence Card, quality flags, unsupported judgment display,
summary-only coach plumbing, and the first Capability Contract / Evidence DAG
contract wired. True LiteRT-LM model validation on device, fine-tune v3 data,
and richer Summary UI evidence display remain open.

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
- [x] Native `motion_quality` emits `capability_contract` and `evidence_dag`.
- [x] Native tests cover side-view squat partial judgment, frontal knee valgus applicability, low-confidence hard-judgment block, quality-flag evidence nodes, and DAG edge endpoints.
- [x] Workout UI displays Trust Matrix status chips.
- [x] Workout UI displays Evidence Card details.
- [x] Unsupported judgments display includes `joint_force`, `clinical_injury_risk`, `medical_diagnosis`, and `muscle_activation_percentage`.
- [x] Android parses Capability Contract and Evidence DAG from native JSON.
- [x] Summary-only coach prompt includes compact capability and evidence refs.
- [x] Model result validation rejects missing evidence refs.
- [x] Model result validation rejects tools that conflict with `cannot_judge`.
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
- [x] Safe summary-only Gemma prompt template with Capability Contract and Evidence DAG refs.
- [x] Capability-gated fallback when model cites invalid evidence or blocked metrics.
- [ ] LiteRT / Google AI Edge feasibility spike.
- [ ] Local Gemma smoke test on Android or desktop.
- [ ] Correct refusal demo clips.
- [ ] Android rep counter state machine integration.
- [ ] Android Savitzky-Golay / Rule 6 temporal smoothing integration.

### Phase 4 P1 Tasks

- [ ] LiteRT / Google AI Edge local Gemma feedback.
- [ ] Android TTS for trust-aware coaching with cooldown.
- [x] Session-level deterministic coach summary.
- [ ] Summary UI display for Capability Contract / Evidence DAG refs.
- [ ] Fine-tune v3 evidence-router dataset.
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
