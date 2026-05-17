# Realtime Person Detection and Official E2B Runtime Plan

This document is the design note for GemmaFit's realtime evidence pipeline and
the official E2B runtime direction. It complements
`docs/design/gemmafit_realtime_evidence_architecture.drawio`.

Senior Layer 2 activity, phase, event, and judgeability are specified in
`docs/design/layer2_senior_activity_model.md`.

The official E2B + MotionZip runtime redesign is specified in
`docs/design/official_e2b_motionzip_runtime_architecture.md`.

The core rule is:

```text
Tools compute evidence first. The model explains or routes only after evidence
and capability gates are known.
```

GemmaFit does not ask the local model to perform frame-by-frame biomechanics,
medical diagnosis, force estimation, GRF estimation, EMG interpretation, fall
risk prediction, or clinical status assessment.

## 1. Realtime Pipeline

Target flow:

```text
CameraX
-> MediaPipe Pose primary tracking
-> Kalman ROI / landmark smoothing
-> optional YOLO burst fallback
-> Senior Layer 2 activity / phase / event
-> Motion Feature Window
-> Evidence Ledger
-> Capability Contract
-> SeniorInteractionPolicy
-> ModelInvocationScheduler
-> compact E2B event packet
-> E2B function-call router
-> validator / deterministic fallback
-> UI, TTS, care log, debug report
```

The phone should not run a heavy vision or language model on every frame.
High-frequency work stays in deterministic pose and feature tools. E2B is
called only for selected events, summaries, reports, or bounded user questions.
`ModelInvocationScheduler` decides whether a call should happen; E2B still
chooses the final function name, args, and evidence refs.

Detailed scheduler contract:
`docs/design/model_invocation_scheduler.md`.

Detailed Senior Layer 2 contract:
`docs/design/layer2_senior_activity_model.md`.

For dementia-friendly self-guided Senior Mode, the realtime path inserts a
`SeniorInteractionPolicy` after Layer 2 and before scheduler/UI/TTS. This layer
does not classify cognition or diagnose dementia. It only converts observable
states such as `NO_PERSON`, `LOST`, `MULTI_PERSON_AMBIGUOUS`, low confidence,
or no user response after a cue into deterministic actions: continue, repeat a
short cue, pause for setup, pause for support, or end with a non-clinical
summary.

### 1.1 ModelInvocationScheduler vs E2B FC Router

The scheduler and E2B router are intentionally different layers.

```text
Deterministic evidence event
-> ModelInvocationScheduler decides call / skip / defer
-> Context compiler builds the existing compact E2B packet
-> E2B chooses one legal function + args + evidence_refs
-> Android validator accepts or uses deterministic fallback
```

The scheduler owns:

- whether to call E2B now, skip the call, defer it to session end, or render a
  deterministic fallback;
- backend eligibility and context budget;
- latency, privacy, confidence, and missing-evidence blocks.

The scheduler must not choose final function names, function args, evidence
refs, refusal wording, memory writes, or safety verdicts. Those remain owned by
official E2B and the Android validator. This preserves the current output
contract without requiring GemmaFit v5 fine-tuning: scheduler-only changes do
not require retraining when tool names, arg schemas, evidence-ref format, and
`can_judge` / `cannot_judge` semantics stay stable.

## 2. Person Detection, Subject Lock, and Judgment Eligibility

GemmaFit treats "person judgment" as three separate tool decisions:

1. Is there a renderable person-like pose candidate?
2. Which candidate is the selected workout subject?
3. Is this frame allowed to support hard coaching?

E2B does not decide whether a person is visible or which person is the subject.
It receives a deterministic `PersonTrackingState` and must follow its
`judgment_allowed` gate. Older prompt packets may mirror this field as
`hard_judgment_allowed` during migration.

### 2.1 What person judgment means

MediaPipe Pose is the primary realtime tracker. It produces pose landmarks,
visibility, presence, and ROI state. The app then applies three gates:

| Gate | Decision | Output |
| --- | --- | --- |
| `PosePresenceGate` | Candidate is person-like enough to render or track. | `presence_state` |
| `SubjectIdentityGate` | Candidate is the selected workout subject. | `subject_state`, `identity_confidence` |
| `JudgmentEligibilityGate` | Frame can support hard coaching. | `judgment_allowed`, `judgment_block_reason` |

Stable `track_id` is not proof of stable identity. A track can follow motion
while accidentally attaching to the wrong person after overlap. Identity
stability requires motion, keypoint, appearance, and ambiguity evidence.

### 2.2 Presence gate

`PosePresenceGate` is a lightweight O(33) landmark gate. It runs before
candidate creation, subject selection, and overlay rendering.

Visibility policy:

- Missing optional visibility is always treated as `0.0`.
- Bbox area is supporting evidence only. It cannot let a low-confidence pose
  pass by itself.
- A candidate passes only when all conditions are true:
  - average visibility `>= 0.18`
  - at least `8` landmarks have visibility `>= 0.25`
  - normalized bbox area `>= 0.01`
  - torso has at least `2` visible points, or upper body has at least `4`
    visible points

When no candidate passes, the frame state is `no_person`; the app clears
rendered landmarks, trajectory, warnings, metrics, and hard judgment outputs.
The user-facing status is `LOW_CONFIDENCE:no_person_detected`.

### 2.3 Subject lock and identity

Subject selection is deterministic and local:

| Situation | Behavior |
| --- | --- |
| One visible candidate | Auto-lock as `single_auto`. |
| Multiple visible candidates | Require stable auto-lock or manual tap. |
| Manual tap | Lock the tapped candidate and store a short-lived appearance anchor. |
| Short unmatched gap | Hold the lock internally for up to `5` frames, but do not draw stale skeletons. |
| Overlap or ambiguous identity | Hold; do not switch to another candidate. |
| Lost beyond threshold | Clear lock and require reacquisition or tap. |

Identity matching combines:

| Signal | Weight |
| --- | ---: |
| Kalman / motion prediction | `0.45` |
| Landmark keypoint distance | `0.25` |
| Upper-body HSV appearance histogram | `0.25` |
| Bbox area and visibility | `0.05` |

The appearance signature is an in-memory color histogram over the selected
upper body or torso crop. It is not a biometric identity feature, is not stored
in long-term memory, and is not sent to E2B as raw image or histogram data.

If candidate boxes overlap, centers are close, top-2 scores are close, or
appearance similarity is weak, the correct behavior is `hold`. Hold means:

- keep the internal lock for reacquisition;
- mark `subject_identity_uncertain` or `subject_temporarily_occluded`;
- block hard coaching;
- do not draw the wrong person's skeleton as the selected subject.

### 2.4 Judgment eligibility

Hard coaching is allowed only when the selected subject is observed and the
pose evidence is sufficient:

```text
presence_state == candidate_visible
subject_state in {single_auto, auto_locked, manual_locked}
state == observed
pose_confidence is sufficient for the target metric
```

All other states must downgrade to repositioning, visibility, or wait cues:

| State | Judgment behavior |
| --- | --- |
| `no_person` | No hard judgment; ask the user to step into frame. |
| `candidate_rejected` | No hard judgment; report low confidence. |
| `needs_selection` | No hard judgment; ask the user to tap themselves. |
| `hold` | No hard judgment; wait or request repositioning. |
| `lost` | No hard judgment; clear selected skeleton and reacquire. |
| `predicted` | UI continuity only; no warning or critical coaching. |

Predicted points may keep ROI state smooth, but predicted-only frames cannot
support warnings, critical verdicts, rep-quality scoring, or memory writes.

### 2.5 PersonTrackingState packet

`PersonTrackingState` is the authoritative packet passed downstream:

```json
{
  "schema_version": "person_tracking_v1",
  "frame_ts_ms": 123456,
  "primary_source": "mediapipe_pose",
  "fallback_source": "none",
  "state": "observed",
  "presence_state": "candidate_visible",
  "subject_state": "manual_locked",
  "selected_subject_id": "subject_0",
  "track_id": "track_0",
  "bbox_norm": [0.18, 0.08, 0.72, 0.94],
  "roi_confidence": 0.91,
  "pose_confidence": 0.88,
  "identity_confidence": 0.79,
  "appearance_similarity": 0.63,
  "match_margin": 0.14,
  "missed_frames": 0,
  "judgment_allowed": true,
  "judgment_block_reason": "",
  "reason": "manual_subject_locked"
}
```

Allowed values:

| Field | Values |
| --- | --- |
| `fallback_source` | `none`, `yolo_person`, `yolo_pose` |
| `state` | `observed`, `predicted`, `lost`, `multi_person_ambiguous` |
| `presence_state` | `no_person`, `candidate_visible`, `candidate_rejected` |
| `subject_state` | `none`, `single_auto`, `auto_locked`, `manual_locked`, `hold`, `lost`, `needs_selection` |
| `judgment_block_reason` | `no_person_detected`, `multi_person_selection_required`, `subject_identity_uncertain`, `subject_temporarily_occluded`, `subject_lost`, `low_pose_confidence` |

### 2.6 Active focus v2 and detector fallback policy

The hard overlap case in `pixel_line_error_1774202137014.mp4` shows the
boundary of geometry-only tracking. Kalman, keypoints, and HSV appearance can
prevent a confident wrong-person switch, but they may fall back to long `hold`
instead of "always active" focus. Classical ROI trackers are not accepted as an
identity source: a tracker box can stay active while drifting onto the wrong
person after overlap. ROI tracking may only be used as a short-term proposal
for where to search next.

The active-focus v2 target is a mobile MOT-lite stack:

```text
MediaPipe Pose candidates
-> PosePresenceGate
-> Kalman / keypoint / color identity gate
-> if ambiguous: low-frequency person detector burst
-> lightweight ReID embedding on 2-4 person crops
-> fused identity score
-> observed selected subject OR hold
```

Normal realtime tracking still runs without a detector. The detector and ReID
model are triggered only when the identity gate cannot make a safe decision.

Trigger conditions:

| Trigger | Action |
| --- | --- |
| `subject_hold` for `>= 2` sampled frames | Run one person-detection burst in the last known ROI plus margin. |
| `subject_identity_uncertain` after overlap | Score detector boxes with ReID before accepting a candidate. |
| ROI drift suspected | Refresh candidate boxes, but do not accept without ReID or clear pose evidence. |
| `subject_lost` before clearing lock | Run one burst if the last lock is recent; otherwise require tap/re-entry. |
| Blank frame vs false pose conflict | Sanity-check that a person is actually present. |

Fused identity score for v2:

| Signal | Weight |
| --- | ---: |
| Lightweight ReID embedding similarity | `0.35` |
| Pose keypoint distance / topology continuity | `0.25` |
| Detector bbox IoU with predicted ROI | `0.20` |
| Part-based HSV color histogram | `0.10` |
| Kalman / motion prediction | `0.10` |

Accept a reacquired candidate only when:

```text
identity_score >= 0.62
reid_similarity >= 0.55
top2_identity_margin >= 0.12
PosePresenceGate passes
```

If those conditions are not true, the state remains `hold`; the overlay must
not draw another person's skeleton as selected.

Mobile performance policy:

- No every-frame detector in v1 or v2.
- Detector burst is throttled to roughly `<= 2 Hz` during ambiguity, then
  disabled once tracking is stable.
- Detector input should be cropped or downscaled (`192-320 px`) and quantized
  where possible.
- ReID runs only on visible person crops, usually `2-4` crops per burst.
- Target budget for the fallback path is amortized, not per frame: detector
  `<= 15 ms` with accelerator or `<= 35 ms` CPU burst, ReID `<= 5 ms` per crop.
- If the fallback exceeds budget or device acceleration is unavailable, degrade
  to `hold` rather than increasing frame latency.

Candidate detector families for later evaluation are EfficientDet-Lite /
MobileNet-SSD / YOLO-nano-class int8 person detectors. Candidate ReID families
are MobileNetV3-small / OSNet-like / Siamese embedding models with a 64- or
128-d normalized vector. These are implementation candidates, not required v1
dependencies.

Privacy and memory policy:

- ReID embeddings are temporary session features, like HSV histograms.
- Do not store embeddings in long-term memory.
- Do not send raw crops, histograms, or embeddings to E2B.
- The feature is for workout subject continuity only, not biometric identity.

Acceptance gates for active-focus v2:

- `pixel_line_error_1774202137014.mp4`: identity switch count `0`.
- Wrong-person selected frames `0`.
- Selected target coverage after manual tap `>= 0.75`, unless the target is
  fully occluded; occluded frames must be marked `hold`.
- Overlay never shows stale or wrong selected skeleton during hold/lost states.

### 2.7 YOLO fallback policy

YOLO is not part of the v1 always-on mobile path. MediaPipe Pose plus
lightweight gates are the normal realtime path because they avoid extra model
size, latency, and battery cost.

Future YOLO use is low-frequency burst fallback only:

| Trigger | YOLO behavior |
| --- | --- |
| Subject lost for several frames | Re-check person bbox for reacquisition. |
| ROI drift suspected | Refresh person bbox, then return to MediaPipe. |
| Multi-person disagreement | Run a burst only if deterministic evidence cannot resolve the subject. |
| Blank frame vs false pose conflict | Sanity-check that a person is actually present. |

YOLO output may help reacquire a person bbox, but it does not bypass
`PosePresenceGate`, `SubjectIdentityGate`, `JudgmentEligibilityGate`, or the
v2 ReID margin requirements above.

Android implementation status:

- `PersonDetector` is an optional runtime interface. If no mobile detector
  asset is packaged, the app records `person_detector_unavailable` and
  continues with the MediaPipe-only hold behavior.
- The current loader accepts YOLO26 ONNX and YOLO-style TFLite assets in
  `app/src/main/assets/`, preferring names such as
  `yolo26n_person_384.onnx`, `yolo26n_person_384_float32.tflite`,
  `yolo26n_person_384_fp16.tflite`, or `yolo26n.tflite`.
- Detector output is treated as `PersonProposal` bbox evidence only. It can
  help select a predicted ROI for `SubjectIdentityMatcher`, but it does not
  draw skeletons, write memory, trigger coaching, or bypass confidence gates.

### 2.8 Optional fine-tune dataset requirements

P0 does not depend on a fine-tuned model. If GemmaFit v5 or a smaller router is
trained later, every example that depends on realtime vision must include
`person_tracking_state`. The model is trained to route or explain from this
state, not to infer person visibility from raw video.

Positive examples:

- single person, `judgment_allowed=true`, selected subject observed;
- manual tap followed by stable identity;
- clean reacquisition after a short hold with high appearance similarity.

Negative / abstention examples:

- no person or blank frame: only visibility or repositioning cue;
- false skeleton candidate: no hard coaching;
- multiple people before selection: ask for tap or wait for stable auto-lock;
- overlap or weak identity after overlap: hold, no hard judgment;
- wrong-identity candidate: refuse hard coaching even if the pose looks valid;
- lost subject: clear selected overlay and ask for reacquisition.

When `judgment_allowed=false`, the model must not emit knee, spine, ROM,
tempo, asymmetry, or other movement-error coaching. It may only choose a
low-confidence / view-limited / repositioning cue or a safe refusal.

## 3. Motion Feature Window

Frame-level pose values are converted into compact event windows before they
reach the model. The LLM receives derived evidence, not raw landmarks by
default.

`MotionFeatureWindow`:

```json
{
  "schema_version": "motion_feature_window_v1",
  "window_id": "window.rep.0007",
  "trigger": "REP_COMPLETED",
  "window_ms": 3200,
  "source": "mediapipe_pose",
  "features": {
    "hip_vertical_displacement": 0.13,
    "knee_angle_min": 78,
    "knee_angle_max": 166,
    "rep_duration_ms": 3200,
    "velocity_peak": "low",
    "stabilization_ms": 800,
    "confidence_floor": 0.82
  },
  "derived_labels": {
    "tempo_band": "controlled",
    "phase_sequence_estimate": [
      "sit_low",
      "rising",
      "standing_stabilized"
    ],
    "rep_completed": true
  },
  "evidence_refs": [
    "metric.senior.reps",
    "metric.senior.tempo",
    "metric.senior.stability_events"
  ],
  "limits": [
    "single_camera_pose_only",
    "no_force_or_grf",
    "no_joint_moment",
    "no_medical_risk_prediction"
  ]
}
```

`derived_labels` are estimates with method and confidence. They are not ground
truth activity labels. If the task or phase is uncertain, downstream rule
outputs must downgrade to `monitor_only`, `not_applicable`, or `abstain`.

### 3.1 Layer 2 Activity / Phase / Event Sequence Model

Layer 2 is the temporal interpretation layer between raw motion features and
E2B. It should not be a language model and should not replace deterministic
rule gates. Its job is to convert pose and feature sequences into bounded
activity, phase, and event estimates:

The senior-only implementation contract, taxonomy, FSM baseline, tiny-model
roadmap, and dataset strategy are defined in
`docs/design/layer2_senior_activity_model.md`. This section keeps the realtime
E2B packet relationship only.

```text
landmark sequence + feature sequence
-> activity hypothesis
-> phase sequence
-> sub-action segments
-> event triggers
-> confidence / abstain reason
```

Recommended rollout:

| Stage | Method | Use |
| --- | --- | --- |
| MVP | Deterministic finite-state machines | Senior tasks with predictable phases, such as chair sit-to-stand, seated leg raise, balance hold, and step touch. |
| v2 mobile model | TCN / 1D-CNN over derived features | Low-latency phase and event detection from MediaPipe feature windows. |
| v2 fallback | Small GRU | Only if temporal hysteresis is hard to model with TCN. |
| Research path | ST-GCN / skeleton transformer | Offline benchmark or future model exploration, not the first Pixel realtime path. |

The first trainable model should use derived sequences, not raw video:

```json
{
  "window_ms": 3200,
  "sample_rate_hz": 15,
  "features_t": {
    "hip_y_norm": [0.42, 0.41, 0.39],
    "knee_angle_deg": [91, 104, 132],
    "trunk_angle_deg": [12, 13, 10],
    "wrist_y_norm": [0.62, 0.61, 0.60],
    "velocity_norm": [0.12, 0.18, 0.09],
    "confidence": [0.88, 0.86, 0.84]
  },
  "labels": {
    "activity_hypothesis": "chair_sit_to_stand",
    "phase_sequence": ["sit_low", "rising", "standing_stabilized"],
    "event": "REP_COMPLETED"
  }
}
```

Composite movements must be represented as phase graphs rather than a single
flat label. A basketball jump shot is not just "squat" and not just "arm
raise"; it is a task with sub-actions and a sport-specific goal:

```json
{
  "activity_hypothesis": "basketball_jump_shot",
  "task_goal": "project_object",
  "open_skill": true,
  "sub_actions": [
    {"label": "countermovement", "phase": "loading", "body_region": "lower_body"},
    {"label": "triple_extension", "phase": "propulsion", "body_region": "lower_body"},
    {"label": "arm_raise", "phase": "ball_lift", "body_region": "upper_body"},
    {"label": "release", "phase": "terminal", "body_region": "upper_body"},
    {"label": "landing_stabilization", "phase": "recovery", "body_region": "whole_body"}
  ],
  "rule_policy": {
    "do_not_apply_strength_squat_depth_score": true,
    "allow_landing_stabilization_monitor": true,
    "allow_general_visibility_and_confidence_gates": true
  }
}
```

This avoids false positives from treating a normal sport skill as a failed
strength exercise. The model should output both a parent task and sub-action
segments, with confidence. If parent task confidence is low, rules should fall
back to generic motion-quality monitoring and avoid hard warnings.

Expected mobile performance envelope:

| Model class | Input | Target latency | Notes |
| --- | --- | ---: | --- |
| FSM / rules | Derived features | `< 1 ms` | First senior-mode implementation. |
| Tiny TCN int8 | 1-6 s feature window | `1-4 ms` | Preferred trainable Layer 2 model. |
| Small GRU int8 | 1-6 s feature window | `2-6 ms` | Use when sequence order/hysteresis matters. |
| ST-GCN int8 | Skeleton graph window | `6-20 ms` | More expressive but heavier; benchmark before mobile use. |

Accuracy gates should be task-specific:

| Gate | Senior controlled tasks | Open-skill sport tasks |
| --- | ---: | ---: |
| Rep completion F1 | `>= 0.95` | Not primary unless task has repetitions. |
| Phase boundary tolerance | `<= 200 ms` | `<= 300-500 ms` depending on speed. |
| Activity / task top-1 | `>= 0.90` for supported tasks | `>= 0.80` plus abstain and sub-action fallback. |
| Abstain correctness | `>= 0.95` | `>= 0.95` |
| False hard-warning rate from task mismatch | `0` target in safety clips | `0` target in safety clips |

Training data for Layer 2 should be built from pose/feature sequences and
phase labels. BABEL / AMASS-like motion datasets are useful for offline
pretraining of action and phase segmentation, but they must be adapted to
MediaPipe-style 2D/3D features before deployment because mocap/SMPL data does
not match phone-camera noise, occlusion, or viewpoint. For Senior Hero, the
more important data is project-owned MediaPipe clips of chair sit-to-stand,
seated leg raise, balance hold, and step touch with phase/event annotations.

Layer 2 outputs are still evidence, not final coaching. They should become
`activity_context`, `motion_context`, `event_trigger`, and evidence refs in
the E2B packet. E2B may cite and explain them, but cannot rewrite their labels
or override their confidence gates.

## 4. Visual Evidence Packet

For event-level model calls, visual data may be compressed into inspectable
assets:

| Asset | Purpose |
| --- | --- |
| `rgb_keyframes` | Scene and activity context: chair, wall, single person, object cues. |
| `roi_contact_sheet` | Cropped body sequence with less background noise. |
| `pose_overlay` | Pose skeleton over selected frames for readable posture context. |
| `pose_flow` | Joint trajectories over time for temporal interpretation. |

The JSON evidence ledger remains authoritative. Visual assets are context for
explanation and review, not permission to invent unsupported measurements.

## 5. LLM Event Packet

E2B receives compact event packets:

```json
{
  "trigger": "REP_COMPLETED",
  "activity_context": {
    "task_label": "chair_sit_to_stand",
    "confidence": 0.91,
    "source": [
      "user_selected_mode",
      "pose_sequence",
      "rgb_scene_cues"
    ]
  },
  "person_tracking_state": {
    "state": "observed",
    "selected_subject_id": "subject_0",
    "pose_confidence": 0.88,
    "hard_judgment_allowed": true
  },
  "motion_feature_window": {
    "window_id": "window.rep.0007",
    "features": {
      "hip_vertical_displacement": 0.13,
      "knee_angle_min": 78,
      "knee_angle_max": 166,
      "rep_duration_ms": 3200,
      "velocity_peak": "low",
      "stabilization_ms": 800,
      "confidence_floor": 0.82
    }
  },
  "visual_summary": {
    "scene_cues": [
      "chair_visible",
      "single_person"
    ],
    "visual_assets_available": [
      "rgb_keyframes",
      "roi_contact_sheet",
      "pose_overlay",
      "pose_flow"
    ]
  },
  "capability_contract": {
    "can_judge": [
      "rep_completion",
      "tempo_consistency"
    ],
    "cannot_judge": [
      "fall_risk_prediction",
      "heart_rate",
      "joint_force"
    ]
  },
  "evidence_refs": [
    "metric.senior.reps",
    "metric.senior.tempo",
    "metric.senior.stability_events"
  ],
  "llm_contract": {
    "must_return_one_json_function_call": true,
    "must_cite_existing_evidence_refs": true,
    "may_explain": true,
    "may_override_deterministic_gates": false
  }
}
```

Output must be a single function-call JSON object. Android validation rejects:

- missing or unknown function names
- invalid args schema
- nonexistent `evidence_refs`
- tool calls that contradict `cannot_judge`
- unsupported medical, force, EMG, fall-risk, or clinical claims

Invalid model output falls back to deterministic rendering.

### 5.1 Function-call flexibility policy

Function calling constrains responsibility boundaries, not every sentence.
GemmaFit should avoid template-only tools such as
`say_good_job_template_03`. Instead, output tools may include bounded natural
language fields that the Android validator checks before rendering.

Three decision layers are intentionally separate:

| Layer | Owner | Purpose |
| --- | --- | --- |
| Motion / rule tools | C++ / Kotlin | Compute angles, ROM, tempo, stability proxies, phase estimates, confidence, and capability gates. |
| Senior interaction policy | Kotlin runtime | In dementia-friendly self-guided mode, choose continue/repeat/pause/end from observable support states only. |
| Model invocation scheduler | Kotlin runtime | Decide whether a model call should happen and which compact context budget is allowed. It does not choose a function. |
| LLM function tools | E2B | Choose a legal next action: report, care log, subjective check-in, memory request, dual-task prompt, or refusal. |

Good E2B tool output:

```json
{
  "function": "create_persona_activity_report",
  "args": {
    "persona": "senior",
    "report_text": "Today you completed 12 controlled sit-to-stand reps. You reported mild breathlessness, so next time keep the same slow pace and rest when needed.",
    "objective_evidence_refs": ["metric.senior.reps", "metric.senior.tempo"],
    "subjective_evidence_refs": ["subjective.breathlessness"],
    "boundary_note": "This is an activity summary, not a medical diagnosis.",
    "selection_basis": "Completion and tempo came from pose evidence; breathlessness came from self-report."
  }
}
```

The validator must still reject:

- nonexistent evidence refs;
- report text that contradicts `cannot_judge`;
- medical, force, GRF, EMG, heart-rate, fall-risk, sarcopenia, or clinical
  claims;
- hard coaching when `PersonTrackingState.state` is `predicted`, `lost`, or
  `multi_person_ambiguous`;
- memory writes without evidence ids.

This keeps outputs from becoming too rigid while preserving the safety
contract:

```text
FC limits what the model is allowed to do.
Natural language args let the model decide how to say it.
The app validator decides whether the result is safe to render.
```

## 6. Official E2B Baseline and Optional Fine-tune Target

The P0 model path is official `Gemma-4-E2B-it` LiteRT with app-side schema
control. The model receives evidence-to-function and evidence-to-report
prompts:

```text
activity_context
+ motion_feature_window
+ MotionZip packet
+ capability_contract
+ evidence_ledger
-> one legal function call
```

The official model is not treated as a trusted raw coach. Android owns JSON
cleanup, schema validation, evidence-ref validation, deterministic fill,
forbidden-claim rejection, and deterministic fallback.

The model should perform:

- tool selection
- evidence citation
- refusal behavior
- persona-specific report tone
- summary wording inside capability boundaries
- uncertainty-aware language

The model should not learn:

- raw video interpretation as ground truth
- raw skeleton math
- biomechanics thresholds
- clinical labels
- force, torque, GRF, joint moment, ligament load, EMG, heart-rate, fall-risk,
  sarcopenia, diagnosis, or rehabilitation conclusions

Optional fine-tune row families remain useful only if official E2B fails a
concrete quality gate or if GemmaFit v5 demonstrably improves schema fidelity,
evidence-ref precision, refusal stability, or zh-TW senior wording:

| Row family | Purpose |
| --- | --- |
| `senior_care_log_clean` | Clean evidence-to-care-log outputs. |
| `senior_care_log_observation` | Stability proxy, missed reps, low confidence, view limits. |
| `subjective_checkin_prompt` | Ask bounded RPE / breathlessness / soreness / discomfort questions. |
| `subjective_checkin_record` | Record self-report evidence without diagnosis. |
| `persona_report` | Senior, caregiver, and professional-share wording from the same evidence. |
| `dual_task_prompt` | Select bounded low-impact gesture/voice prompts. |
| `dual_task_result` | Record bounded answer and movement completion. |
| `activity_uncertain` | Downgrade judgment when task or phase is uncertain. |
| `unsupported_question` | Refuse fall risk, sarcopenia, diagnosis, rehab progress, force, EMG, or heart-rate claims. |
| `adversarial_boundary` | Resist prompt injection and missing-evidence requests. |
| `memory_trend` | Summarize app-provided aggregates only. |

Evaluation gates:

| Metric | Required |
| --- | ---: |
| `tool_name_accuracy` | `>= 95%` |
| `args_schema_valid_rate` | `>= 98%` |
| `evidence_ref_valid_rate` | `>= 99%` |
| `unsupported_refusal_rate` | `>= 95%` |
| `persona_schema_valid_rate` | `>= 98%` |
| `forbidden_claim_rate` | `0` |

## 7. 270M Function Router Decision

FunctionGemma 270M is not required by default.

Use official E2B as the P0 path if:

- E2B passes the function-call and refusal gates above.
- On-device latency is acceptable for event-level calls.
- Deterministic fallback remains available.

Add 270M only if:

- E2B produces unstable JSON function calls.
- E2B latency is too high for event-level routing.
- A smaller router improves evidence-ref validity or refusal reliability.

If 270M is added, it should own fast function routing while E2B owns richer
persona wording and session-level summaries.

## 8. Acceptance Tests

Offline video tests:

- clean chair sit-to-stand -> care log with reps and tempo
- stability proxy event -> monitor wording, no fall-risk claim
- no person -> subject lost, no judgment
- multi-person -> stable subject selection or abstain
- dementia-friendly no-response timeout -> deterministic pause, no E2B call
- dementia-friendly left activity area -> deterministic pause, no E2B call
- low confidence -> no hard coaching judgment
- activity uncertain -> monitor or abstain, not warning

Pixel realtime tests:

- MediaPipe primary path works without YOLO during normal tracking.
- YOLO burst refreshes ROI only after subject lost or multi-person ambiguity.
- Kalman predicted points are excluded from hard evidence refs.
- `ModelInvocationScheduler` skips E2B for normal live frames and blocked
  tracking states.
- E2B is called only on approved event/session/export triggers.
- Official `Gemma-4-E2B-it` LiteRT prewarm succeeds before summary/export.
- Product runtime uses compressed MotionZip prompts only; dense-vs-MotionZip
  comparison remains a debug benchmark.
- Debug report exposes backend, function, evidence refs, fallback, and
  `PersonTrackingState`.

Model tests:

- function-call schema passes
- evidence refs exist
- `cannot_judge` metrics are not corrected
- unsupported claims route to `refuse_unsupported_question`
- persona reports keep the same facts and boundaries across tones
