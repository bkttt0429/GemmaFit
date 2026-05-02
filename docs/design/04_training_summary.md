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

Avoid diagnostic statements:

- Do not say a muscle is weak, inactive, or dysfunctional.
- Do not describe safety events as injuries.

## Current Compose Implementation

- `app/src/main/kotlin/com/gemmafit/ui/screens/SummaryScreen.kt`
- `app/src/main/kotlin/com/gemmafit/ui/components/GemmaFitComponents.kt`

The current implementation uses mock values and a Canvas line chart. It is ready to wire to session data once the native bridge is available.
