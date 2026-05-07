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
- [x] Full deep literature report imported into the docs with Chinese working
  review and English canonical review.
- [x] Product Claims matrix added for allowed wording, required qualifiers, and
  forbidden claim boundaries.
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

- [x] Fine-tune v3 data generator for Capability Contract + Evidence DAG input.
- [ ] Run the v3 Colab training job and copy back `training_done_v3.json`,
  `trainer_state_v3.json`, and conversion metadata.
- [ ] LiteRT-LM real model smoke on Pixel 8 Pro with `gemmafit-v2-fc.litertlm`
  or later v3 artifact.
- [ ] Summary UI deeper display of capability/evidence refs, beyond prompt/debug
  plumbing.
- [ ] Pixel 8 Pro acceptance run on `/sdcard/DCIM/GemmaFitTest/` correct/wrong
  videos.
- [ ] Cleanup of unrelated dirty build artifacts and existing `VideoStates.kt`
  EOF whitespace warning.

### Immediate Next Todo - Senior Hero Demo Closure (2026-05-07)

This is the next execution list. Work in this order unless a device/model
blocker forces a fallback path. The goal is a demonstrable Senior Hero flow,
not a broad refactor.

- [ ] `NEXT-P0-01` Senior state wiring: add a ViewModel/state reducer that owns
  selected senior activity, dual-task prompt state, latest gesture/voice
  attempt, care-log context, generated care log, backend, fallback, and
  evidence refs.
  - Acceptance: Senior UI can show prompt card, response status, and care-log
    summary from one state object; no mock-only UI path is required for the
    demo.
- [ ] `NEXT-P0-02` Live gesture pipeline: connect camera pose landmarks to
  `SeniorGestureDetector`, then emit `DualTaskAttempt` for left-hand A,
  right-hand B, clap confirm, and two-hand skip/cancel.
  - Acceptance: one live Pixel run records gesture attempts with
    `metric.dual_task.gesture.*` evidence refs and low-confidence fallback.
- [ ] `NEXT-P0-03` Care-log context builder: convert senior session summary +
  capability contract + evidence ledger into `CareLogContext`.
  - Acceptance: sit-to-stand and balance-hold sessions produce deterministic
    `CareLogContext` without raw landmarks, raw video, or medical labels.
- [ ] `NEXT-P0-04` Care-log render and memory write path: route through local
  model when available, otherwise use `SeniorCareLogRenderer`; write
  `CARE_ACTIVITY_LOG` only when evidence ids pass policy.
  - Acceptance: debug JSON shows `care_log.backend`, `function_name`,
    `evidence_refs`, `fallback`, and rejected unsupported judgments.
- [ ] `NEXT-P0-05` Native senior evidence ids: emit
  `metric.senior.reps`, `metric.senior.tempo`,
  `metric.senior.trunk_control`, `metric.senior.stability_events`, and dual-task
  gesture evidence nodes where applicable.
  - Acceptance: native tests cover sit-to-stand clean, low confidence,
    stability proxy event, and blocked fall-risk/sarcopenia capability nodes.
- [ ] `NEXT-P0-06` Official FunctionGemma baseline smoke: use the official
  FunctionGemma `.litertlm` path as a real LiteRT sanity check before trusting
  any GemmaFit v4 fine-tune artifact.
  - Acceptance: Android debug report shows a real `litert-lm:*` backend or a
    clear fallback reason; no demo claim depends on an unverified local model.
- [ ] `NEXT-P0-07` FunctionGemma v4 training/export: run
  `train_functiongemma_v4_senior_router.ipynb`, copy back `training_done_v4`,
  eval report, and `.litertlm` only after smoke passes.
  - Acceptance: `tool_call_eval_v4_senior.json` passes gates, LiteRT artifact
    loads, and unsupported claims still route to `refuse_unsupported_question`.
- [ ] `NEXT-P0-08` Pixel Senior acceptance pack: prepare or record short clips
  for clean sit-to-stand, stability proxy, balance hold, no person, and one live
  dual-task gesture.
  - Acceptance: exported debug reports in `docs/benchmark/` include
    `care_log`, `dual_task`, `capability_contract`, `evidence_dag`, backend,
    fallback, and evidence refs.
- [ ] `NEXT-P0-09` Caregiver export polish: produce a readable caregiver-facing
  export entrypoint using the five-section care log.
  - Acceptance: export text says activity completed, visible movement quality,
    what was not judged, next focus, and caregiver note; no fall-risk,
    sarcopenia, rehab-progress, force, EMG, or diagnosis claim.
- [ ] `NEXT-P0-10` Demo script update: make Senior Hero the first 90 seconds and
  keep General Fitness as the evidence/biomechanics foundation.
  - Acceptance: script shows offline mode, dual-task prompt, care log, refusal
    for fall-risk question, and evidence/debug trace.

### Immediate Next Todo - P1 After Demo Path Is Stable

- [ ] `NEXT-P1-01` Optional ASR wrapper behind feature flag using Android
  `SpeechRecognizer`; bounded parser remains authoritative.
- [ ] `NEXT-P1-02` Add Senior-specific UI copy localization for zh-TW and en-US.
- [ ] `NEXT-P1-03` Add trend memory summary for 7D/30D senior activity logs,
  using app-provided aggregates only.
- [ ] `NEXT-P1-04` Add small synthetic/no-person/occlusion Senior benchmark
  clips and compact expected-output fixtures.
- [ ] `NEXT-P1-05` Clean unrelated dirty artifacts after demo-critical files are
  committed or explicitly ignored.

### Next Todo Validation Commands

Run these after each implementation slice:

```powershell
python finetune/data/generate_v4_senior_router.py --validate
python finetune/eval_v4_senior_router.py --dataset finetune/data/gemmafit_v4_senior_evidence_router.json --strict
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
git diff --check
```

### Training Data Source Plan - Senior v4

Use this before starting FunctionGemma v4 training. The router SFT dataset should
be structured evidence-to-function-call data. Public video/skeleton datasets are
used to improve scenario coverage, fixtures, and acceptance tests; they are not
used to train medical conclusions.

| ID | Source | Use in GemmaFit | Data to extract | Status / action | Boundary |
| --- | --- | --- | --- | --- | --- |
| `DATA-P0-01` | GemmaFit synthetic senior evidence router data (`finetune/data/generate_v4_senior_router.py`) | Primary FunctionGemma v4 SFT source. | `care_log_context`, `dual_task_context`, `activity_context`, `motion_context`, `capability_contract`, `evidence_ledger`, expected tool call. | Ready; current generator/eval passes 9200-row strict gates. Expand with native fixtures after `NEXT-P0-05`. | This teaches routing and refusal, not biomechanics or clinical labels. |
| `DATA-P0-02` | Pixel / local Senior Hero demo clips | Gold acceptance and hard-negative fixtures. | MediaPipe landmarks -> native report -> compact evidence ledger; no raw video in fine-tune rows. | Record short clips for sit-to-stand, balance hold, step touch, no-person, low-confidence, and live dual-task gestures. | Local/private video stays ignored unless explicitly approved; commit derived JSON only. |
| `DATA-P0-03` | ETRI-Activity3D elderly daily-activity dataset: https://ai4robot.github.io/etri-activity3d-en/ | Senior activity-context coverage and ADL priors. | Elderly RGB-D/skeleton action classes, especially standing, sitting, reaching, household ADLs. | Request/download samples first; check full access terms before use. | Use for activity recognition/fixture diversity only; no fall-risk, sarcopenia, or rehab claims. |
| `DATA-P0-04` | Toyota Smarthome / Toyota Smarthome Untrimmed | Real-world senior ADL context and noisy home-environment scenarios. | Activity labels, coarse/fine ADL segments, pose-derived evidence if accessible. | Check dataset access and license; use paper/sample metadata until access is approved. | Research-use dataset; do not redistribute raw clips. Not a clinical dataset. |
| `DATA-P0-05` | NTU RGB+D / NTU RGB+D 120: https://rose1.ntu.edu.sg/dataset/actionRecognition/ | Broad skeleton action, gesture coverage, and motion-intensity proxy coverage for context gates. | Skeleton sequences for stand up, sit down, clapping, hand waving, squat down, stagger/fall, plus derived joint velocity / angular velocity / tempo-band proxies. | Register/request access if needed; skeleton modality is much smaller than RGB/depth. | Academic/non-commercial terms; use fall/stagger classes only for refusal/boundary tests, not prediction. Derived velocities are proxy features, not force or injury labels. |
| `DATA-P0-06` | Public fall datasets for refusal/boundary corpus: UP-Fall, UR Fall, SisFall/KFall | Negative examples and claim-boundary prompts. | Names of unsupported claims, edge-case motion contexts, sensor limitations. | Use literature/dataset descriptions first; only download if needed for explicit no-claim benchmark. | GemmaFit must not output fall-risk score or fall prediction from these datasets. |
| `DATA-P0-07` | App-generated care-log templates | High-quality Data-to-Text examples for family/caregiver logs. | Synthetic JSON -> five-section care log in en-US and zh-TW. | Generate controlled rows from product templates; add reviewer-authored gold examples. | Logs are wellness activity summaries, not medical notes or rehab progress reports. |
| `DATA-P0-08` | Dual-task prompt templates | Low-impact cognitive/motor prompt selection rows. | Bounded A/B, yes/no, 1-4 options; gesture and voice fallback cases. | Generate from safe templates: memory recall, category sorting, attention switching, arithmetic, orientation. | Do not train dementia risk, cognitive impairment, or cognitive screening outputs. |
| `DATA-P0-14` | AddBiomechanics Dataset: https://addbiomechanics.org/download_data.html | Ground-truth biomechanics reference for motion-intensity boundary setting and claim limits. | Optical mocap, ground reaction force, joint torque, center-of-mass kinematics, and derived kinematics/kinetics where license permits. | Use for research notes and calibration design first; only derive aggregate thresholds/proxy sanity checks if terms allow. | This is not a single-camera dataset. Do not train GemmaFit to claim phone-only force, torque, GRF, ligament load, or clinical risk. |
| `DATA-P0-15` | OpenCap validation literature / available exports: https://pmc.ncbi.nlm.nih.gov/articles/PMC10586693/ | Markerless biomechanics reference for what video-based systems can and cannot claim. | Reported kinematic/kinetic validation ranges, task coverage, camera requirements, and optional public example exports if available. | Use as Product Claims and calibration-path support before using any generated rows. | OpenCap is not equivalent to GemmaFit single-camera BlazePose; do not transfer force/muscle activation claims. |
| `DATA-P0-16` | AMASS + BABEL: https://amass.is.tue.mpg.de/ and https://babel.is.tue.mpg.de/ | Large-scale mocap + language labels for activity-aware motion-intensity distributions. | 3D motion sequences, action labels, phase/window labels where available, derived velocity/angular-velocity/ROM/smoothness proxies. | Request/download after source/licensing note is written; start with a small subset. | Use for context and expected-movement distributions only; not medical, force, or injury supervision. |
| `DATA-P0-17` | AIST++ Dance Motion Dataset: https://google.github.io/aistplusplus_dataset/ | High-rotation / high-ROM motion examples to reduce false positives in dance-like tasks. | 3D dance motion, rotation speed, asymmetry, airborne/turning phase proxies, activity labels. | Use as motion-intensity stress data after `motion_context` schema is finalized. | Dance intensity is task context, not automatic safety warning; no joint-force or injury claim. |
| `DATA-P0-18` | GemmaFit motion-intensity synthetic rows | Bridge public motion datasets into the FunctionGemma router schema. | `motion_context`, `momentum_proxy`, `tempo_band`, `phase`, `rule_interpretation`, `capability_contract`, evidence refs. | Add after native `motion_intensity` evidence ids exist. | The model learns interpretation states, not true momentum measurement. Deterministic gates remain authoritative. |

Immediate data tasks:

- [ ] `DATA-P0-09` Add a `finetune/data_sources/senior_v4_sources.md` file with
  source URL, access status, license/terms, intended use, excluded claims, and
  citation for every external dataset above.
- [ ] `DATA-P0-10` Add `test_assets/benchmarks/senior_v4/manifest.json` with
  local Pixel clips and public/sample clips mapped to expected evidence ids.
- [ ] `DATA-P0-11` Add 20 native-report-derived rows to the v4 generator from
  real app reports before training the final FunctionGemma v4 artifact.
- [ ] `DATA-P0-12` Add 50 adversarial caregiver prompts covering fall risk,
  sarcopenia, rehab progress, diagnosis, clinical improvement, joint force,
  EMG, and muscle activation.
- [ ] `DATA-P0-13` Add 50 zh-TW care-log rows and 50 zh-TW dual-task rows after
  English schema gates remain green.
- [ ] `DATA-P0-19` Add a `motion_intensity_sources` subsection to
  `finetune/data_sources/senior_v4_sources.md` covering AddBiomechanics,
  OpenCap, AMASS/BABEL, AIST++, NTU, license/access status, and excluded claims.
- [ ] `DATA-P0-20` Add motion-intensity row families to the v4 generator:
  controlled senior fast motion -> monitor pace, sport explosive movement ->
  expected movement, airborne COM offset -> not applicable/expected, landing
  stabilization instability -> monitor/warning, and force/ACL/EMG questions ->
  refusal.
- [ ] `DATA-P0-21` Add evaluator checks for motion-intensity outputs:
  no true momentum, GRF, joint force, ACL load, ligament strain, EMG, or muscle
  activation claims from single-camera pose.

### Next Slice - Senior Hero v4 (P0)

Senior Hero is now the primary demo storyline: offline older-adult home
movement support, care activity logs, and low-impact dual-task prompts. General
Fitness remains the shared biomechanics foundation. The claim boundary stays
non-diagnostic wellness and movement quality.

- [x] `SENIOR-P0-01` Define public Senior contracts: `CareLogContext`,
  `CareActivityLog`, `DualTaskSessionPlan`, `DualTaskAttempt`,
  `activity_context`, and `motion_context`.
- [x] `SENIOR-P0-02` Extend FunctionRegistry with v4 senior tools:
  `create_care_activity_log`, `select_dual_task_prompt`, and
  `record_dual_task_result`.
- [x] `SENIOR-P0-03` Extend memory policy to allow
  `CARE_ACTIVITY_LOG` and `DUAL_TASK_RESULT` writes only with evidence ids.
- [x] `SENIOR-P0-04` Add deterministic care-log fallback renderer that keeps
  fall-risk, sarcopenia, rehab, force, EMG, and clinical claims out of the log.
- [x] `SENIOR-P0-05` Add deterministic dual-task gesture detector for
  left-hand A, right-hand B, clap confirm, and two-hand skip/cancel.
- [x] `SENIOR-P0-06` Add bounded voice answer parser with low-confidence
  gesture fallback.
- [x] `SENIOR-P0-07` Add debug endpoints for
  `content://com.gemmafit.debug/care_log` and
  `content://com.gemmafit.debug/dual_task`.
- [ ] `SENIOR-P0-08` Wire Senior screens to live view-model state for dual-task
  prompt card, gesture/voice status, and care-log summary.
- [ ] `SENIOR-P0-09` Add native Senior evidence ids:
  `metric.senior.reps`, `metric.senior.tempo`,
  `metric.senior.trunk_control`, `metric.senior.stability_events`,
  `metric.dual_task.gesture.left_arm_raise`, and
  `metric.dual_task.gesture.right_arm_raise`.
- [x] `SENIOR-P0-10` Add v4 synthetic generator and evaluator for
  FunctionGemma senior evidence routing.
- [x] `SENIOR-P0-11` Add Colab notebook skeleton for resumable FunctionGemma
  v4 senior-router fine-tune.
- [ ] `SENIOR-P0-12` v4 dataset/eval now passes; next smoke a real
  `gemmafit-v4-senior-router.litertlm` before enabling the backend by default.
- [ ] `SENIOR-P0-13` Pixel acceptance: clean sit-to-stand care log, 2 stability
  proxy events without fall-risk claim, gesture A/B, low ASR confidence
  fallback, caregiver fall-risk question refusal.

### Next Slice - Senior Hero v4.1 Subjective Check-in (P0)

v4.1 avoids camera-only momentum, heart-rate, force, or clinical claims by
adding bounded self-report evidence and persona-specific activity reports.

- [x] `SENIOR41-P0-01` Define subjective check-in contracts:
  `SubjectiveCheckIn`, `SubjectiveLevel`, self-report evidence refs, and JSON
  serialization.
- [x] `SENIOR41-P0-02` Add persona report contract:
  `PersonaActivityReport` with `senior`, `caregiver`, and
  `professional_share` personas.
- [x] `SENIOR41-P0-03` Extend FunctionRegistry with
  `ask_subjective_checkin`, `record_subjective_checkin`, and
  `create_persona_activity_report`.
- [x] `SENIOR41-P0-04` Add deterministic persona report fallback that combines
  objective movement evidence with self-report while rejecting heart-rate,
  fall-risk, sarcopenia, rehab, force, EMG, and diagnosis claims.
- [x] `SENIOR41-P0-05` Update v4 generator/evaluator to v4.1 row families:
  care log, subjective prompt, subjective record, persona report, memory,
  unsupported, and adversarial rows.
- [x] `SENIOR41-P0-06` Add debug sections/endpoints for
  `subjective_checkin` and `persona_report`.
- [ ] `SENIOR41-P0-07` Wire the post-session large-button/TTS check-in card
  into the live Senior state flow.
- [ ] `SENIOR41-P0-08` Pixel acceptance: clean sit-to-stand + mild RPE
  generates all three persona reports; discomfort/strong breathlessness gives
  stop/rest boundary without diagnosis.

### Next Slice - Evidence Contract Hardening (P0)

Roadmap boundary: the literature supports claim boundaries, calibration paths,
selective abstention, and provenance design. It does **not** mean the current
prototype thresholds are clinically validated.

- [x] `EVID-P0-01` Native evidence id stabilization: every `template_metric`,
  `quality_flag`, `not_applicable`, and `capability` has a stable
  `evidence_id`.
- [x] `EVID-P0-02` Evidence node fields: include `id`, `type`, `metric`,
  `value`, `source`, `confidence`, `evidence_level`, and `reason`.
- [x] `EVID-P0-03` Evidence edge validation: all `derived_from`, `gated_by`,
  `thresholded_by`, `supports`, and `blocks` endpoints exist.
- [x] `EVID-P0-04` Capability Contract view gating: side-view squat can judge
  `depth`, `tempo`, and `trunk_lean`, but cannot judge
  `frontal_knee_valgus`.
- [x] `EVID-P0-05` Confidence boundary: low confidence blocks hard judgment but
  still emits evidence nodes and blocked reasons.
- [x] `EVID-P0-06` Android compact ledger: Gemma prompts include compact
  evidence refs and capability contract only; no raw landmarks or raw video.
- [x] `EVID-P0-07` Gemma validation hardening: missing `evidence_refs` or
  `cannot_judge` metric tool calls must use deterministic fallback.
- [x] `EVID-P0-08` Summary UI/debug: expose backend, function, evidence refs,
  and fallback status.
- [x] `EVID-P0-09` Native tests: cover side-view, frontal-view, low-confidence,
  edge endpoint, and quality-flag evidence-node cases.
- [x] `EVID-P0-10` Android tests: cover missing DAG compatibility, full DAG
  parse, invalid evidence refs fallback, and summary-only trigger behavior.
- [ ] `EVID-P0-11` Video acceptance: export debug JSON for
  correct/wrong/view-limited/no-person clips and confirm `capability_contract`,
  `evidence_dag`, and `coach_summary` are inspectable.
- [x] `EVID-P0-12` Research extraction backlog: list only FPPA, confidence
  ceiling, and coverage-risk benchmark work; do not label prototype thresholds
  as validated. See `docs/papers/research_extraction_backlog.md`.

### Next Slice - Realtime Person Detection and Evidence Sampling (P0)

Design source: `docs/design/realtime_person_detection_and_finetune_plan.md`.
The mobile path is MediaPipe-first. YOLO is a burst fallback for subject loss,
multi-person ambiguity, or ROI drift, not an always-on realtime dependency.

- [ ] `REALTIME-P0-01` MediaPipe primary tracking state: emit
  `PersonTrackingState` with selected subject, ROI confidence, pose confidence,
  `observed/predicted/lost/multi_person_ambiguous` state, and hard-judgment
  eligibility.
- [ ] `REALTIME-P0-02` Kalman ROI/landmark smoother: smooth ROI and landmark
  trajectories while marking predicted points as UI-only and ineligible for
  hard evidence refs.
- [ ] `REALTIME-P0-03` YOLO burst fallback design: define the fallback trigger
  policy, subject reacquisition behavior, multi-person handling, and mobile
  performance budget before adding any always-on detector.
- [ ] `REALTIME-P0-04` MotionFeatureWindow builder: aggregate pose frames into
  compact event windows with hip displacement, knee angle min/max, rep duration,
  velocity proxy, stabilization time, confidence floor, and estimated phase
  sequence.
- [ ] `REALTIME-P0-05` Visual Evidence Packet exporter: produce optional
  RGB keyframes, ROI contact sheets, pose overlays, and pose-flow images for
  event-level model review without making images authoritative.
- [ ] `REALTIME-P0-06` EventTriggerPolicy: call E2B only for rep completion,
  persistent warning/monitor windows, subject lost/recovered summaries,
  session end, user questions, or care-log/persona-report events.
- [ ] `REALTIME-P0-07` E2B function-call validator: reject invalid JSON,
  unknown tools, missing evidence refs, `cannot_judge` violations, and medical,
  force, GRF, EMG, fall-risk, heart-rate, or clinical claims.
- [ ] `REALTIME-P0-08` Offline video capability benchmark: run the local E2B
  capability notebook on representative clips and store pass/fail summaries for
  tool choice, evidence refs, refusal behavior, and forbidden-claim checks.
- [ ] `REALTIME-P0-09` Pixel realtime acceptance test: verify MediaPipe primary
  tracking, no-person handling, multi-person abstention or stable selection,
  event-only E2B triggering, and debug output for backend/function/evidence
  refs/fallback/tracking state.

### Next Slice - E2B Evidence-to-Function Fine-tune (P0)

Fine-tune E2B only on compact evidence-to-function/report behavior. Do not train
raw video, raw skeleton math, force, GRF, EMG, heart-rate, clinical labels,
fall-risk prediction, or medical conclusions.

- [x] `FT-E2B-P0-01` Evidence-to-function dataset schema: define the v5 row
  format for `activity_context`, `person_tracking_state`,
  `motion_feature_window`, `visual_summary`, `capability_contract`,
  `evidence_ledger`, and one expected function-call output.
- [x] `FT-E2B-P0-02` v5 dataset generator: create row families for clean care
  logs, observation logs, subjective check-ins, persona reports, dual-task
  prompts/results, activity uncertainty, unsupported questions, adversarial
  boundaries, and memory trends.
- [x] `FT-E2B-P0-03` Public dataset source mapping: map ETRI Activity3D,
  Toyota Smarthome, NTU RGB+D, AddBiomechanics, AMASS/BABEL, AIST++, and fall
  datasets to allowed uses, extracted features, license status, and excluded
  claims.
- [x] `FT-E2B-P0-04` E2B eval gates: require tool accuracy, args schema
  validity, evidence-ref validity, refusal rate, persona schema validity, and
  `forbidden_claim_rate = 0` before any LiteRT export or Android backend enable.
- [x] `FT-E2B-P0-05` 270M necessity decision gate: keep FunctionGemma 270M out
  of the main path unless E2B fails function-call/refusal stability or latency
  targets; if added, use 270M for fast routing and E2B for richer reports.
- [x] `FT-E2B-P0-06` v5.1 hard-case generator: add schema fuzz, tracking
  uncertainty, parent-task uncertainty, sub-action fallback, conflicting
  evidence, memory-policy, and zh-TW refusal families behind `--hard-cases`.
- [x] `FT-E2B-P0-07` v5.1 hard-case eval gates: add
  `judgment_disallowed_hard_coaching_rate`,
  `parent_task_uncertain_hard_warning_rate`, `schema_fuzz_schema_rate`, and
  `hard_case_pass_rate` checks.
- [x] `FT-E2B-P0-08` Colab v5.1 dataset switch: update
  `finetune/train_e2b_v5_evidence_router.ipynb` to default to the 50k
  hard-case dataset while preserving `DATASET_VARIANT=v5` for old smoke runs.
- [ ] `FT-E2B-P1-01` Real app event-packet mining: export anonymized Pixel
  debug packets from Senior clips and convert failures into regression rows.
- [ ] `FT-E2B-P1-02` External-source conversion prototype: convert one
  BABEL/FineGym-style phase sample into `parent_task_uncertain` or
  `sub_action_fallback` evidence packets without committing raw data.

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
| P0 | Fine-tune v3 evidence-router dataset | Train/evaluate safe Gemma routing from Capability Contract + Evidence DAG, not raw pose. | `finetune/data/gemmafit_v3_evidence_router.json` | v3 generator/evaluator exists; next add native report fixtures on top of synthetic edge cases. |
| P0 | No-person / occlusion / multi-person fixtures | Guard against stale skeletons, false candidates, and overconfident warnings. | `test_assets/videos/`, `prototype/outputs/`, native JSON fixtures | Add expected `LOW_CONFIDENCE`, `VIEW_LIMITED`, or `SUBJECT_LOST` assertions. |
| P1 | Zenodo Squat static image benchmark | Static threshold sanity check for squat back/heel proxies. Not valid for timing or Rule 6. | `test_assets/datasets/zenodo_squat_good_bad_back_bad_heel/` | Finish checksum/status cleanup and document which metrics are valid vs invalid. |
| P1 | Kaggle Squat Pose Dataset | Extra squat landmark diversity and threshold sensitivity tests. | `test_assets/datasets/` or ignored local cache | Use only if license and schema are documented; do not let it dominate multi-exercise claims. |
| P1 | Exercise Recognition Time Series | Temporal smoothing / rep-count / Rule 6 stress tests. | `test_assets/datasets/` or ignored local cache | Map columns to GemmaFit motion traces; use for timing behavior, not medical or force claims. |
| P1 | Senior-mode public clips | Chair sit-to-stand, supported squat, balance hold, and step-touch demo coverage. | `test_assets/videos/internet_public*/` | Keep as product demo and accessibility validation, not sarcopenia/fall-risk evidence. |
| P2 | User-recorded optional clips | Robustness and UX tuning after MVP. | Ignored local cache by default | Store raw video only with explicit consent; commit derived landmarks/reports when useful. |

### Dataset Size Budget

Measured sizes are from the local checkout and attached Pixel 8 Pro on
2026-05-05. Planned external datasets that are not present locally are listed
with a project budget instead of a claimed exact size.

| Dataset / Asset Set | Current measured size | Project budget / cap | Notes |
| --- | --- | --- | --- |
| Safety & Trust video benchmark v1 | Missing | <= 250 MiB local video set; <= 20 MiB committed derived reports | Prefer short, compressed public or Pixel clips plus JSON reports. |
| Current public demo clips | 169.45 MiB in `test_assets/videos` | <= 250 MiB | Includes public, phone-ready, synthetic, and small preview clips. Avoid keeping duplicate encodes unless needed for phone playback. |
| Public source clips | 69.04 MiB in `test_assets/videos/internet_public` | Keep canonical public sources | WebM originals for attribution and reproducibility. |
| MP4 converted public clips | 35.07 MiB in `test_assets/videos/internet_public_mp4` | Keep only if app/device tests require MP4 | Derived convenience copies; can be regenerated from manifest. |
| Phone-ready public clips | 43.65 MiB in `test_assets/videos/internet_public_phone` | Keep only final demo subset | Used for `/sdcard/DCIM/GemmaFitTest/` acceptance runs. |
| Pixel 8 Pro acceptance clips | 44 MiB on `/sdcard/DCIM/GemmaFitTest/` | <= 100 MiB on device; commit derived reports only | Current phone set: chair stand, balance, lunge, deadlift, short squat preview. |
| Fine-tune v2 data | 2.98 MiB in `finetune/data` | Keep as immutable benchmark | `fc_training_data_chat.json` is 2.09 MiB. |
| Fine-tune v3 evidence-router data | 24.36 MiB in `finetune/data/gemmafit_v3_evidence_router.json` | <= 25 MiB for JSON train/validation | Generated from synthetic evidence-router edge cases; next layer native report fixtures. |
| No-person / occlusion / multi-person fixtures | Partly present; `synthetic_two_person_squat.mp4` is 2.71 MiB; `prototype/outputs` is 98.33 KiB | <= 50 MiB | Add only short clips and compact expected-output fixtures. |
| Zenodo squat static image benchmark | 785.89 MiB zip | <= 1 GiB local cache; commit summaries only | Static images only; not valid for Rule 6 timing. |
| Kaggle Squat Pose Dataset | 13.01 MiB local cache, including 2.27 MiB zip | <= 500 MiB local cache | Downloaded from Kaggle; use only after license/schema check. |
| Exercise Recognition Time Series | 204.89 MiB local cache, including 64.59 MiB zip | <= 250 MiB local cache | Downloaded from Kaggle; use for temporal smoothing and rep-count stress tests only. |
| Senior-mode public clips | Present inside public clip folders; current senior phone copies are about 31.68 MiB | <= 100 MiB final demo subset | Product/accessibility demo data, not clinical evidence. |
| User-recorded optional clips | None committed | Case-by-case ignored cache | Commit only derived landmarks/reports unless explicitly approved. |
| Benchmark reports | 1.07 MiB in `docs/benchmark` | <= 20 MiB committed | Good place for final tables, screenshots, and compact JSON evidence. |

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

- [x] Keep `fc_training_data_chat.json` as the immutable v2 benchmark file.
- [x] Add `gemmafit_v3_evidence_router.json` with separate run suffix
  `gemmafit_v3_evidence_router`.
- [x] Include positive tool-call rows and refusal rows in the same schema.
- [x] Include view-limited, low-confidence, not-applicable, and unsupported
  medical/force/muscle-activation prompts.
- [x] Validation set must include exact evidence-ref checks and Traditional
  Chinese prompt wrappers.
- [x] Do not include raw user video, private filenames, or free-form medical
  labels in fine-tune data.
- [ ] Add post-training invalid tool rejection tests against real model output.

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
