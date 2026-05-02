# Main Workout Screen

## Purpose

The workout screen is the primary real-time coaching surface. It combines camera preview, MediaPipe skeleton overlay, form score, rep count, COM/BoS visualization, and immediate safety feedback.

## Wireframe

```text
+------------------------------------------------+
| menu  Free Mode                         03:42  |
+------------------------------------------------+
| +--------------------------------------------+ |
| | REP 12          Form score              85 | |
| | Current set     [====================]      | |
| +--------------------------------------------+ |
|                                                |
|             Camera preview area                |
|                                                |
|             skeleton overlay                   |
|             red affected limbs                 |
|             BoS outline + COM dot              |
|                                                |
|                         +--------------+       |
|                         | 3D View      |       |
|                         | mini pose    |       |
|                         +--------------+       |
|                                                |
| +--------------------------------------------+ |
| | Knee alignment                             | |
| | Point both knees toward your toes.         | |
| +--------------------------------------------+ |
|                                                |
| +--------------------------------------------+ |
| | Primary load area                          | |
| | quadriceps, gluteus maximus                | |
| | Secondary: hamstrings, calves, core        | |
| | Pose-based estimate, not EMG.              | |
| +--------------------------------------------+ |
| [ pause ] [ flip ] [       Summary        ]    |
+------------------------------------------------+
```

## Layout Rules

- Camera preview fills the screen behind overlays.
- Top bar is fixed at 52dp.
- HUD score panel sits below the top bar with 16dp side margins.
- Correction prompt is centered only when a safety event is active.
- Muscle focus panel stays above controls and remains readable on small screens.
- Control bar is bottom aligned with three actions: pause, flip camera, summary.

## Data Binding Plan

| UI element | Source |
| --- | --- |
| Rep count | `WorkoutSession` rep counter |
| Form score | Safety event score aggregation |
| Skeleton | MediaPipe pose landmarks |
| Red hotspots | `safety_monitor.cpp` rule violations |
| COM / BoS | `com_tracker.cpp` result |
| Correction text | FunctionRegistry / TTS selected rule |
| Muscle panel | `muscle_focus.cpp` pose-based estimate |

## Current Compose Implementation

- `app/src/main/kotlin/com/gemmafit/ui/screens/WorkoutScreen.kt`
- `app/src/main/kotlin/com/gemmafit/ui/components/GemmaFitComponents.kt`

The current implementation uses synthetic HUD values and a Canvas skeleton so the layout can be reviewed before CameraX/JNI data is connected.
