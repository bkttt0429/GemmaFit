# Research Extraction Backlog

This backlog tracks literature items that may later become algorithms,
calibration protocols, or benchmark claims. It is intentionally narrow:
prototype thresholds remain prototype thresholds until GemmaFit has either
direct literature support for the exact use case or its own phone-video
validation data.

## 1. FPPA And View-Conditioned Knee Valgus

- Research question: under which camera views can 2D FPPA or knee-valgus proxy
  metrics be considered observable enough for coaching feedback?
- Extraction target: view preconditions, required landmarks, known 2D/3D error
  limits, and language for `cannot_judge = frontal_knee_valgus`.
- Implementation target: Capability Contract gating for front-view-only knee
  valgus feedback.
- Claim boundary: do not claim clinical knee-injury risk or precise 3D knee
  mechanics from a side-view phone video.

## 2. Confidence Ceiling Per Metric

- Research question: what is the maximum trustworthy confidence for each
  pose-derived metric under single-camera conditions?
- Extraction target: calibration methods, expected calibration error patterns,
  landmark visibility failure modes, and dataset-shift caveats.
- Implementation target: metric-level `confidence_ceiling` values in the
  Capability Contract, not raw landmark confidence copied into UI claims.
- Claim boundary: confidence means "readable from this view," not clinical
  certainty.

## 3. Coverage-Risk Benchmark

- Research question: how much coaching coverage should GemmaFit trade away to
  reduce unsupported or hard judgments?
- Extraction target: selective prediction, reject option, conformal prediction,
  and metric-level abstention methods that can produce coverage-risk curves.
- Implementation target: benchmark tables by exercise, view angle, occlusion,
  lighting, distance, and clothing; output should include `can_judge`,
  `cannot_judge`, and skipped metric reasons.
- Claim boundary: benchmark results can support schema compliance, abstention
  behavior, and metric readability; they do not validate medical outcomes or
  long-term injury prevention.
