# Layer 2 Senior Activity Model A/B Report

Date: 2026-05-16  
Workspace: `D:\GemmaFit`  
Benchmark type: deterministic derived-feature unit A/B  
Primary code gate: `Layer2SeniorArchitectureABTest`

## Executive Summary

The senior-scoped Layer 2 implementation is more controlled and more stable
than the previous mixed-activity Layer 2 behavior for the demo target.

The key result is not higher free-form recognition coverage. It is tighter
runtime control:

| Metric | Legacy A | Current B | Result |
|---|---:|---:|---|
| Risky hard-judgment frames | 13 | 0 | 100% reduction |
| Non-senior demoted frames | 0 | 10 | Explicitly bounded |
| Basketball sub-actions exposed | 4 | 0 | Removed from senior path |
| Balance stability events | 8 | 4 | 50% reduction |
| All non-none events | 24 | 9 | 62.5% reduction |
| Setup-transition events | 5 | 0 | 100% reduction |
| Rep-completed events | 2 | 2 | Chair/support rep coverage preserved |

Interpretation: the current architecture preserves the two useful completion
signals in the test set while removing risky hard judgments and reducing event
surface area. That is the right tradeoff for a senior-safe demo path.

## What Was Compared

### A: Legacy behavior

The baseline is represented by `LegacyLayer2Probe` inside:

`app/src/test/kotlin/com/gemmafit/video/Layer2SeniorArchitectureABTest.kt`

It captures the important pre-change behavior:

- `lunge` remains a Layer 2 activity and can expose hard judgment policy.
- `supported_squat` normalizes to generic `squat` and can expose hard judgment.
- `basketball_jump_shot` emits composite sub-actions such as
  `countermovement`, `triple_extension`, `arm_lift`, and `release_like`.
- `balance_hold` emits `stability_monitor` every frame.
- Unknown/setup-like activity emits `monitor_only` every frame.

### B: Current senior-scoped behavior

The current implementation is `Layer2TemporalInterpreter`:

`app/src/main/kotlin/com/gemmafit/video/Layer2TemporalInterpreter.kt`

Runtime scope is deliberately small:

- `chair_sit_to_stand`
- `supported_squat`
- `balance_hold`
- `setup_transition`
- `unknown`

Non-senior labels are demoted to `unknown` with monitor-only policy and no
event emission. This keeps debug evidence without turning every unsupported
frame into an event.

## Test Set

The A/B test uses 45 deterministic derived-feature frames across eight cases:

| Case | Purpose |
|---|---|
| `clean_chair_sit_to_stand` | Preserve hero rep-completed behavior |
| `partial_chair_sit_to_stand` | Avoid false completion |
| `non_senior_lunge` | Confirm non-senior demotion |
| `non_senior_basketball` | Confirm composite sport path removal |
| `supported_squat` | Count rep but keep monitor-only policy |
| `stable_balance_hold` | Emit start/completed instead of per-frame event spam |
| `unstable_balance_hold` | Surface instability as monitor-only evidence |
| `setup_transition` | Avoid rep/event emission during setup |

The benchmark intentionally uses derived features only. It does not use raw
video, raw landmarks, E2B, or any prompt content.

## Detailed Results

### 1. Risky Hard-Judgment Exposure

Risky hard judgment means a case outside the demo-safe senior path still allows
hard judgment. This is the most important control metric.

| Case | Legacy A | Current B |
|---|---:|---:|
| `non_senior_lunge` | 5 frames | 0 frames |
| `supported_squat` | 8 frames | 0 frames |
| Total | 13 frames | 0 frames |

Result: current B eliminates all risky hard-judgment exposure in this set.

The important product effect is that `supported_squat` can still count a rep,
but it stays `monitor_only`. The app can say a structured movement was observed
without claiming unsupported chair/contact safety assessment.

### 2. Non-Senior Demotion

| Case | Legacy A | Current B |
|---|---:|---:|
| `non_senior_lunge` | 0 demoted frames | 5 demoted frames |
| `non_senior_basketball` | 0 demoted frames | 5 demoted frames |
| Total | 0 | 10 |

Result: current B makes the boundary explicit. The model no longer treats sport
or general-fitness activity names as senior-safe Layer 2 activities.

This reduces accidental demo drift. A user-selected or classifier-derived
`lunge` string no longer unlocks a unilateral judgment path.

### 3. Composite Sport Sub-Actions

| Metric | Legacy A | Current B |
|---|---:|---:|
| Max basketball sub-actions exposed | 4 | 0 |

Legacy A exposed sport-specific sub-actions:

- `countermovement`
- `triple_extension`
- `arm_lift`
- `release_like`

Current B exposes none of these in the senior path. This removes a major source
of explanation drift and keeps the demo centered on chair STS / supported
strength / balance evidence.

### 4. Event Surface Area

| Metric | Legacy A | Current B | Reduction |
|---|---:|---:|---:|
| All non-none events | 24 | 9 | 62.5% |
| Setup-transition events | 5 | 0 | 100% |
| Balance stability events | 8 | 4 | 50% |

This matters because Layer 2 events can be copied into MotionZip, UI state, and
debug reports. Fewer events means less accidental downstream triggering.

The biggest concrete fix is setup transition: legacy A emitted monitor events
for setup-like movement. Current B keeps setup in `phase=setup` with
`event=none`.

### 5. Rep Coverage

| Metric | Legacy A | Current B |
|---|---:|---:|
| Rep-completed events | 2 | 2 |

The current design did not lose the useful completion signals in this A/B set:

- clean chair sit-to-stand still completes
- supported squat still completes

The difference is policy. Chair STS remains judgeable. Supported squat is
monitor-only for demo P0.

## Prompt / Latency Impact

Prompt-facing cost: effectively zero.

The fields added for control are debug/runtime fields, not E2B prompt fields:

- `judgeability`
- `hold_duration_ms`
- `non_senior_label_demoted`
- balance hold event names

The design document explicitly keeps the high-token sections out of the demo
prompt:

- full value/threshold/time-range evidence refs stay internal
- counter-evidence stays internal
- granular 5-claim judgeability stays reduced/debug-only

Therefore this A/B change should not cause the prompt to grow toward the
`~1100 tokens` risk scenario. It is aligned with the Pile A + reduced B scope.

## Verification Commands

Executed with:

```powershell
$env:JAVA_HOME='C:\Users\ken\.jdks\openjdk-23.0.2'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.gemmafit.video.Layer2SeniorArchitectureABTest'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.gemmafit.video.Layer2TemporalInterpreterTest'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.gemmafit.video.MotionZipPacketBuilderTest'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.gemmafit.senior.SeniorInteractionPolicyTest'
.\gradlew.bat :app:compileDebugKotlin
```

Results:

| Test | Tests | Failures | Errors |
|---|---:|---:|---:|
| `Layer2SeniorArchitectureABTest` | 1 | 0 | 0 |
| `Layer2TemporalInterpreterTest` | 12 | 0 | 0 |
| `MotionZipPacketBuilderTest` | 5 | 0 | 0 |
| `SeniorInteractionPolicyTest` | 6 | 0 | 0 |
| `compileDebugKotlin` | pass | 0 | 0 |

Note: Windows file locking occurred when Gradle jobs were run in parallel.
Re-running tests sequentially after stopping daemons resolved it. This is an
environment issue, not a Layer 2 logic failure.

## Pixel 8 Pro Smoke

Device:

```text
Pixel 8 Pro / husky
ADB serial: 3A251FDJG004RX
```

Install command:

```powershell
$env:JAVA_HOME='C:\Users\ken\.jdks\openjdk-23.0.2'
.\gradlew.bat :app:installDebug
```

Debug provider commands:

```powershell
adb shell content delete --uri content://com.gemmafit.debug/events
adb shell content read --uri content://com.gemmafit.debug/layer2_smoke
adb shell content read --uri content://com.gemmafit.debug/model_invocation_smoke
adb shell content read --uri content://com.gemmafit.debug/events
```

Saved artifacts:

- `pixel_layer2_smoke.json`
- `pixel_model_invocation_smoke.json`
- `pixel_layer2_events.jsonl`

Pixel smoke summary:

| Check | Result |
|---|---|
| `layer2_smoke.success` | true |
| `layer2_smoke.cases` | 6 |
| `model_invocation_smoke.success` | true |
| `model_invocation_smoke.cases` | 6 |
| `motion_zip_packet.heavily_compressed_summary.event` | `rep_completed` |
| debug events final message | `layer2_event/smoke_completed` |
| debug events final success | true |

Important on-device observations:

| Case | Pixel result |
|---|---|
| `chair_sit_to_stand_rep` | `rep_completed`, judgeable |
| `supported_squat_rep_monitor_only` | `rep_completed`, but `allow_hard_judgment=false` |
| `balance_hold_completed` | `balance_hold_started` then `balance_hold_completed`, hold duration 5000 ms |
| `non_senior_lunge_demoted` | `event=none`, `non_senior_label_demoted=true` |
| `non_senior_basketball_demoted` | `event=none`, `non_senior_label_demoted=true`, no sub-actions |
| `predicted_tracking_abstain` | `event=abstain`, reason `person_not_observed` |

This confirms the compiled Android code on Pixel matches the unit A/B result:
non-senior labels stay out of the event stream, supported squat remains
monitor-only even when a rep is counted, and chair STS still emits the
judgeable completion event.

## Conclusion

The A/B evidence supports adopting the current senior-scoped Layer 2 design for
the demo path.

It is more controllable because non-senior labels are explicitly demoted and
risky hard-judgment exposure drops from 13 frames to 0 in the benchmark.

It is more stable because event surface area drops from 24 non-none events to 9,
setup transitions stop emitting events, and balance hold emits semantic
start/completed events instead of per-frame stability spam.

It preserves the useful behavior because rep completion remains 2/2 for the
chair STS and supported squat completion cases.

## Remaining Risks

This report validates deterministic derived-feature behavior, not full on-device
camera playback. It does not measure MediaPipe jitter, camera angle variance, or
real Pixel frame timing.

Recommended next validation:

1. Run the same app build on Pixel 8 Pro with the chair STS demo clip.
2. Capture `layer2_event` debug output.
3. Confirm no non-senior demotion event reaches E2B scheduling.
4. Confirm supported squat stays monitor-only even when a rep is counted.
5. Confirm session summary prompt size is unchanged by this Layer 2 patch.
