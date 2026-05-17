# Training Summary Screen

## Purpose

The summary screen turns a workout into a clear review: total work, form quality, common safety events, movement pattern distribution, and pose-estimated muscle focus.

## Wireframe

```text
+------------------------------------------------+
| back   Workout Summary                  share  |
+------------------------------------------------+
| +-------------+ +-------------+ +------------+ |
| | Total reps  | | Quality     | | Duration   | |
| | 28          | | 83%         | | 08:42      | |
| +-------------+ +-------------+ +------------+ |
|                                                |
| +--------------------------------------------+ |
| | Form Score Trend                           | |
| |        line chart over reps                | |
| +--------------------------------------------+ |
|                                                |
| +----------------------+ +-------------------+ |
| | Safety Events        | | Movement Timeline | |
| | Knee alignment 4     | | Knee dominant 42% | |
| | Spinal flexion 2     | | Push pattern 34%  | |
| | COM offset 3         | | Core support 24%  | |
| +----------------------+ +-------------------+ |
|                                                |
| +--------------------------------------------+ |
| | Muscle Focus                               | |
| | Quadriceps 42%                             | |
| | Glutes 28%                                 | |
| | Hamstrings 18%                             | |
| | Core 12%                                   | |
| | Pose-estimated, not direct activation.     | |
| +--------------------------------------------+ |
|                                                |
| +--------------------------------------------+ |
| | Coach Tips                                 | |
| | Keep knees tracking over toes.             | |
| | Slow the descent when warned.              | |
| +--------------------------------------------+ |
| [       New Session       ] [   All History   ] |
+------------------------------------------------+
```

## Data Binding Plan

| UI element | Source |
| --- | --- |
| Total reps | `WorkoutSession` |
| Quality | Average form score |
| Duration | Session timer |
| Trend chart | Per-rep score history |
| Safety events | Aggregated `SafetyRuleViolation` list |
| Movement timeline | `movement_classifier.cpp` pattern segments |
| Muscle focus | `muscle_focus.cpp` aggregate |
| Coach tips | FunctionRegistry summary templates |

## Safety Language

Use coaching guidance:

- `Keep knees tracking over toes during knee-dominant reps.`
- `Slow the descent when the velocity warning appears.`
- `Depth and hip-knee timing stayed consistent.`
- `Side view: judging depth and tempo only.`
- `Tracking was unstable; re-record before judging alignment.`

Avoid diagnostic statements:

- Do not say a muscle is weak, inactive, or dysfunctional.
- Do not describe safety events as injuries.

## Local AI Coach Summary Policy

The summary screen shows one `Local AI Coach Summary` panel after the workout.
Live cues remain deterministic and short; Gemma is summary-only. The panel
always uses this order:

1. `What I saw`
2. `Why it matters`
3. `What I did not judge`
4. `Next focus`

Evidence comes first. Limitations are visible, but they should not cover useful
metrics that were actually judgeable. For example, a side-view squat can still
show depth, tempo, and trunk evidence while listing frontal knee valgus as
`NOT_APPLICABLE`.

Claim language follows `docs/papers/literature_review.md`: summary copy may say
that depth, tempo, trunk lean, and consistency were readable from the current
camera evidence, but must not turn those proxies into injury diagnosis,
clinical biomechanics, joint-force estimates, or muscle activation percentages.
Use `pose-estimated load focus` for muscle wording.

The panel should expose backend status (`litert-lm`, `llama.cpp`, or
deterministic fallback), selected function, and compact `evidence_refs`. If the
model cites a missing evidence id or selects a tool outside the Capability
Contract, the UI must label the result deterministic fallback instead of local
Gemma.

## Current Compose Implementation

- `app/src/main/kotlin/com/gemmafit/ui/screens/SummaryScreen.kt`
- `app/src/main/kotlin/com/gemmafit/ui/components/GemmaFitComponents.kt`

The current implementation uses mock values and a Canvas line chart. It is ready to wire to session data once the native bridge is available.
