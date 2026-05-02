# GemmaFit Design System

## Product Direction

GemmaFit is an offline form-coaching app for fitness beginners. The interface should feel like a live training HUD: dense enough for repeated workouts, but calm enough that safety feedback is easy to notice.

Core principles:

- Safety first: active violations must dominate the visual hierarchy.
- Real-time clarity: rep count, form score, and correction cue must be readable at a glance.
- Privacy and trust: onboarding copy should emphasize on-device processing without making medical claims.
- Pose-based language: muscle focus is an estimate from movement posture, not EMG or diagnosis.

## Colors

| Token | Hex | Use |
| --- | --- | --- |
| `Background` | `#121212` | App background |
| `Surface` | `#1E1E1E` | Cards, controls, lower panels |
| `OverlayPanel` | `#2C2C2CD0` | Camera HUD panels |
| `Green` | `#00E676` | Normal skeleton, high score, safe state |
| `Red` | `#FF1744` | Safety violation, affected segment |
| `Orange` | `#FF9100` | Warning or borderline state |
| `Blue` | `#448AFF` | COM marker, correction hint, secondary CTA |
| `PurpleHighlight` | `#E040FB` | Muscle-focus accent |
| `TextPrimary` | `#FFFFFF` | Primary text |
| `TextSecondary` | `#B0B0B0` | Labels and helper text |
| `TextHint` | `#757575` | Notes and disclaimers |

## Typography

| Role | Size | Weight | Usage |
| --- | ---: | --- | --- |
| Screen title | 18sp | Bold | Top bars and panel titles |
| HUD value | 32sp | Bold monospace | Rep count |
| Score value | 28sp | Bold monospace | Form score |
| Instruction | 16sp | Medium | Live correction text |
| Label | 12sp | Regular | Metrics and muscle labels |
| Disclaimer | 10sp | Light | "Pose-based, not EMG" notes |

Use `letterSpacing = 0.sp` for display text. Keep panels compact; avoid marketing hero composition inside the app.

## Components

### Camera HUD

- Top bar: mode, live indicator, timer.
- Score panel: rep count and form score.
- Violation prompt: centered correction message with red rule accent.
- Mini 3D window: bottom-right pose/world landmark preview.
- Muscle focus panel: bottom panel with primary and secondary load areas.
- Control bar: pause, flip camera, summary.

### Skeleton Overlay

| State | Line | Joint | Notes |
| --- | --- | --- | --- |
| Normal | Green, 5px | White, 6px | Light enough to keep camera visible |
| Violation | Red, 10px | White, 6px | Affected limb is redrawn on top |
| COM | Blue dot | N/A | Red when outside BoS |
| BoS | Blue outline | N/A | Semi-transparent support area |

### Cards and Panels

- Radius: 8dp.
- Use cards for individual repeated items, not nested page sections.
- Use semi-transparent overlay panels only on the camera screen.
- Use full-width scroll content on summary screens.

## Safety Copy

Allowed:

- "Point both knees toward your toes."
- "Slow the descent when velocity warning appears."
- "Pose-based estimate, not EMG."
- "No video is uploaded for coaching."

Avoid:

- Diagnostic wording such as "weak", "dysfunction", or "injury".
- Claims of direct muscle activation measurement.
- Claims that the app replaces a professional coach or clinician.
