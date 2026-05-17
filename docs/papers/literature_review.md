# GemmaFit Literature Review

Last updated: 2026-05-05

GemmaFit's strongest defensible claim is not that an AI coach can diagnose
injury, clinical biomechanics, or disease from a phone video. The defensible
claim is narrower and stronger: under single-camera, on-device conditions,
GemmaFit provides bounded, non-diagnostic movement-quality coaching only when
pre-inference evidence supports the specific metric being discussed.

This document is the English canonical source for writeup claims, product copy,
and benchmark interpretation. The full Chinese working review is kept in
[`literature_review_zh.md`](literature_review_zh.md). Thresholds remain
`prototype_threshold` unless they are locally calibrated or directly supported
by a study in the same metric, population, camera view, and task context.

## Executive Summary

The current literature supports three product principles:

1. AI outputs should be traceable to data, model context, measurement
   conditions, and known limits.
2. When a metric is uncertain or unobservable, the system should abstain or
   narrow the judgment instead of forcing a full verdict.
3. Coaching feedback helps learning only when its timing, frequency,
   specificity, and task relevance are controlled.

For GemmaFit, this supports a **Capability-Bounded Evidence Router**:

- C++/Kotlin computes pose-derived evidence and deterministic safety gates.
- The app declares a `capability_contract` before local Gemma runs.
- Gemma may choose a function call only for metrics in `can_judge`.
- Every model `evidence_refs` value must point to an existing Evidence DAG or
  Evidence Ledger id.
- `VIEW_LIMITED`, `LOW_CONFIDENCE`, and `NOT_APPLICABLE` block only the affected
  metric, not the entire coaching session when other evidence is usable.

The same literature also sets hard limits. GemmaFit should not claim phone-only
injury diagnosis, clinical-grade biomechanics, muscle activation measurement,
rehabilitation efficacy, fall-risk scoring, sarcopenia detection, or proven
injury-risk reduction.

## Evidence Provenance in AI Systems

### Core Sources

- W3C PROV family: provenance is information about entities, activities, and
  agents that helps assess data quality, reliability, and trustworthiness
  (Groth & Moreau, 2013).
- Model Cards: model releases should document intended use, evaluation
  procedures, context, and subgroup performance (Mitchell et al., 2019).
- Datasheets for Datasets: datasets should document motivation, composition,
  collection process, recommended uses, and limitations (Gebru et al., 2021).
- NIST AI RMF 1.0: validation means objective evidence that a system satisfies
  requirements for a specified intended use, with limitations documented
  (NIST, 2023).
- NIST AI 600-1: generative AI governance should include provenance,
  pre-deployment testing, incident disclosure, and recorded limitations
  (Autio et al., 2024).

### Relevance to GemmaFit

The useful product move is metric-level provenance, not generic "explainable
AI" prose. Each coaching output should be traceable to the camera/view
condition, visible landmarks, metric formula, confidence gate, threshold label,
and refusal reason. This supports an auditable coaching story while preserving
the distinction between evidence-backed output and prototype heuristics.

GemmaFit can credibly claim evidence provenance when each suggestion is linked
to observable pose features, deterministic rules, and bounded function calls.
That does not prove the metrics are valid across every body, camera angle, and
environment.

### Gaps

There is little direct evidence on whether per-output provenance in consumer
fitness apps reduces over-trust or unsafe action. GemmaFit can contribute here
by measuring whether Evidence Cards and skipped-judgment explanations improve
user calibration.

## Selective Prediction and Reject Option

### Core Sources

- Selective Classification for Deep Neural Networks: systems can trade
  coverage for risk by rejecting examples outside an acceptable confidence
  region (Geifman & El-Yaniv, 2017).
- SelectiveNet: reject behavior can be integrated into model training rather
  than added only as post-hoc confidence thresholding (Geifman & El-Yaniv,
  2019).
- Ovadia et al.: uncertainty and calibration degrade under dataset shift, so
  reject policies cannot be trusted solely from IID validation (Ovadia et al.,
  2019).
- NIST AI RMF 1.0: AI systems should fail safely when they exceed knowledge
  limits, especially in higher-risk contexts (NIST, 2023).

### Relevance to GemmaFit

The key safety pattern is **partial judgment**. A side-view squat may support
depth, tempo, and trunk lean while blocking frontal knee valgus. This is more
useful than a binary full-session refusal and safer than a forced all-metric
verdict.

Metric-level abstention should be treated as a first-class product feature:
`can_judge` metrics remain available, `cannot_judge` metrics are explicitly
listed with reasons, and Gemma can cite only the evidence attached to
`can_judge`.

### Gaps

The literature is strong on sample-level abstention but thinner on
metric-level abstention for structured coaching outputs. A GemmaFit benchmark
could report coverage-risk curves by exercise, view angle, occlusion, clothing,
distance, and lighting.

## Confidence Calibration for Pose-Estimation Metrics

### Core Sources

- BlazePose GHUM Holistic: single RGB, on-device, 3D landmarks, and real-time
  fitness-tracking use cases (Grishchenko et al., 2022).
- On Calibration of Modern Neural Networks: modern neural networks are often
  poorly calibrated; temperature scaling is a simple useful correction
  (Guo et al., 2017).
- Accurate Uncertainties for Deep Learning Using Calibrated Regression:
  regression credible intervals also need calibration (Kuleshov et al., 2018).
- Ovadia et al.: dataset shift can break uncertainty quality (Ovadia et al.,
  2019).

### Relevance to GemmaFit

The calibration target should be the final coaching metric, not only a raw
keypoint confidence score. Users do not need to know only that `left_knee`
visibility is `0.83`; the product needs to know whether depth, tempo, trunk
lean, or knee alignment is reliable under the current view.

Visibility, landmark jitter, frame dropout, and view quality are practical
inputs to a metric confidence estimate, but they are engineering proxies until
calibrated on real phone videos. Product language should say "readable from
this view" or "camera-limited" rather than "clinically certain."

### Gaps

Evidence is thin for BlazePose-derived squat depth, FPPA, trunk lean, tempo, and
similar fitness metrics under real consumer recording conditions. GemmaFit can
contribute a metric-level calibration protocol reporting accuracy, expected
calibration error, confidence ceilings, and coverage-risk by view condition.

## Augmented Feedback and Motor Learning

### Core Sources

- Sigrist et al.: visual, auditory, haptic, and multimodal augmented feedback
  generally helps motor learning, but effectiveness depends on modality,
  timing, frequency, and task complexity (Sigrist et al., 2013).
- Winstein: knowledge-of-results scheduling is a foundational motor-learning
  concern (Winstein, 1991).
- Wulf & Shea: principles from simple skills do not automatically generalize to
  complex motor skills (Wulf & Shea, 2002).
- Wulf, Shea, & Lewthwaite: motor skill learning is affected by attentional
  focus, task complexity, and feedback design (Wulf et al., 2010).

### Relevance to GemmaFit

The literature supports task-linked movement feedback but does not support
constant, exhaustive, overconfident commentary. GemmaFit should separate live
deterministic cues from post-workout summary coaching:

- Live UI: short, safe, high-confidence cues only.
- Summary UI: explain what was observed, why it matters, what was not judged,
  and what to focus on next.

This is especially important for older adults and beginners, where too much
feedback can increase cognitive load and reduce autonomy.

### Gaps

Randomized or retention-focused studies for consumer, single-camera,
LLM-mediated exercise feedback are still scarce. GemmaFit can compare immediate
feedback vs set-level summary, low-frequency vs high-frequency cues, and
baseline-relative vs absolute coaching language.

## On-Device LLM Function-Calling Safety

### Core Sources

- Toolformer: language models can learn when to call APIs, what arguments to
  pass, and how to integrate tool outputs (Schick et al., 2023).
- Indirect prompt injection: LLM-integrated applications can be manipulated via
  natural-language inputs that affect tool use and API calls (Greshake et al.,
  2023).
- NIST AI 600-1: generative AI claims need empirical evaluation, provenance,
  testing, and limitation tracking (Autio et al., 2024).
- OWASP GenAI Top 10: prompt injection, improper output handling, and excessive
  agency are major LLM application risks (OWASP, 2025).

### Relevance to GemmaFit

The safety case should not rely on "Gemma is obedient." It should rely on a
hard capability space:

- fixed function allowlist
- schema validation
- parameter bounds
- deterministic view preconditions
- evidence-ref validation
- no arbitrary tool execution
- deterministic fallback on invalid output

Gemma's role is bounded selection and explanation, not movement metric
calculation, thresholding, diagnosis, or policy override.

### Gaps

There is little formal safety evaluation for small on-device health or fitness
LLMs using bounded function calls. GemmaFit can build a red-team corpus covering
prompt injection, background text, odd poses, unsupported view conditions,
cross-template contamination, and tool misuse.

## AI Ethics and Regulatory Framing for Non-Diagnostic Health Coaching

### Core Sources

- FDA General Wellness guidance: low-risk software intended to maintain or
  encourage a healthy lifestyle can fall outside device-function enforcement
  when it does not diagnose, cure, mitigate, prevent, or treat disease
  (FDA, 2026).
- WHO AI for Health ethics guidance: AI for health should prioritize ethics,
  human rights, transparency, safety, responsibility, and human autonomy
  (WHO, 2021).
- NIST AI RMF 1.0: trustworthy AI is valid and reliable, safe, secure,
  accountable and transparent, explainable and interpretable, privacy-enhanced,
  and fair with harmful bias managed (NIST, 2023).
- EU AI Act: the framework is risk-based; high-risk use cases require stronger
  documentation, logging, human oversight, accuracy, and robustness
  obligations (European Commission, 2026).

### Relevance to GemmaFit

Intended use determines the boundary. GemmaFit should stay in
non-diagnostic movement-quality coaching and general wellness language. UI and
docs should say that pose-based feedback is limited by view angle, lighting,
clothing, occlusion, and camera setup, but the deeper safety control is the
capability contract itself.

### Gaps

Wellness, fitness, rehabilitation, and medical-purpose boundaries vary by
jurisdiction. This document supports product and research framing, not legal
advice.

## Product Claims Matrix

| Claim | Supported wording | Required qualifier | Avoid |
| --- | --- | --- | --- |
| Movement feedback | Single-camera pose landmarks can support non-diagnostic movement-quality cues such as depth, tempo, trunk lean, and consistency. | Only under readable view and confidence conditions. | Clinical biomechanics or medical assessment. |
| Partial judgment | GemmaFit judges supported metrics and abstains from unsupported ones. | `cannot_judge` reasons must be visible. | "The app can judge all form issues from any angle." |
| Evidence provenance | Coaching outputs cite evidence ids from deterministic metrics, gates, and capability items. | Provenance supports auditability, not universal validity. | "Every number is clinically validated." |
| Local AI | Local Gemma routes evidence into bounded function calls and summary explanations. | Deterministic gates remain authoritative. | Free-form diagnosis, force estimates, or injury prediction. |
| Muscle focus | Pose-estimated load focus can describe likely movement emphasis. | It is not EMG and not activation percentage. | "Your glutes activated 30%." |
| Calibration | Baselines can personalize coaching thresholds after repeated high-confidence clean reps. | Calibration proposals require evidence and user/app policy approval. | Automatic medical personalization or rehabilitation prescription. |
| Senior care log | App-provided senior session evidence can be summarized into a caregiver-readable activity log. | The log is a structured activity record, not a medical assessment. | Fall-risk score, sarcopenia detection, rehab progress, or clinical improvement. |
| Dual-task prompt | Low-impact gesture/voice prompts can combine simple cognitive targets with supported movement tasks. | Results are bounded attempt records only. | Cognitive diagnosis, dementia screening, or clinical interpretation. |
| Voice answer | Bounded ASR answers can support A/B, yes/no, 1-4, or short options. | Low confidence or out-of-set answers fall back to gesture. | Free-form medical conversation through ASR. |

## Citation-Ready Claims

- GemmaFit is an on-device, non-diagnostic movement-quality coaching system.
- The system uses a capability contract to pre-declare which metrics are
  judgeable before local Gemma inference.
- Correct abstention is an intended safety behavior, not a fallback error.
- Pose-only muscle focus is a movement-pattern estimate, not a physiological
  activation measurement.
- Senior care logs summarize activity completion and visible movement-quality
  observations; they do not assess fall risk, sarcopenia, rehabilitation
  progress, or clinical improvement.
- Dual-task results record bounded answers and movement completion only; they
  do not diagnose cognition or dementia risk.
- Function-calling benchmarks measure schema compliance, allowed-tool routing,
  refusal behavior, evidence-ref validity, and local inference status. They do
  not validate clinical thresholds.

## Consolidated References

Autio, C., Schwartz, R., Dunietz, J., Jain, S., Stanley, M., Tabassi, E., Hall,
P., & Roberts, K. (2024). *Artificial Intelligence Risk Management Framework:
Generative Artificial Intelligence Profile (NIST AI 600-1).*

European Commission. (2026). *AI Act.*

FDA. (2026). *General Wellness: Policy for Low Risk Devices.*

Gebru, T., Morgenstern, J., Vecchione, B., Wortman Vaughan, J., Wallach, H.,
Daume III, H., & Crawford, K. (2021). *Datasheets for Datasets.*

Geifman, Y., & El-Yaniv, R. (2017). *Selective Classification for Deep Neural
Networks.*

Geifman, Y., & El-Yaniv, R. (2019). *SelectiveNet: A Deep Neural Network with an
Integrated Reject Option.*

Greshake, K., Abdelnabi, S., Mishra, S., Endres, C., Holz, T., & Fritz, M.
(2023). *Not what you've signed up for: Compromising Real-World LLM-Integrated
Applications with Indirect Prompt Injection.*

Grishchenko, I., Bazarevsky, V., Zanfir, A., Bazavan, E. G., Zanfir, M., Yee,
R., Raveendran, K., Zhdanovich, M., Grundmann, M., & Sminchisescu, C. (2022).
*BlazePose GHUM Holistic: Real-time 3D Human Landmarks and Pose Estimation.*

Groth, P., & Moreau, L. (2013). *PROV-Overview: An Overview of the PROV Family
of Documents.*

Guo, C., Pleiss, G., Sun, Y., & Weinberger, K. Q. (2017). *On Calibration of
Modern Neural Networks.*

Kuleshov, V., Fenner, N., & Ermon, S. (2018). *Accurate Uncertainties for Deep
Learning Using Calibrated Regression.*

Mitchell, M., Wu, S., Zaldivar, A., Barnes, P., Vasserman, L., Hutchinson, B.,
Spitzer, E., Raji, I. D., & Gebru, T. (2019). *Model Cards for Model Reporting.*

NIST. (2023). *Artificial Intelligence Risk Management Framework (AI RMF 1.0).*

OWASP. (2025). *Top 10 Risks & Mitigations for LLMs and Gen AI Apps.*

Ovadia, Y., Fertig, E., Ren, J., Nado, Z., Sculley, D., Nowozin, S., Dillon,
J. V., Lakshminarayanan, B., & Snoek, J. (2019). *Can You Trust Your Model's
Uncertainty? Evaluating Predictive Uncertainty Under Dataset Shift.*

Schick, T., Dwivedi-Yu, J., Dessi, R., Raileanu, R., Lomeli, M., Zettlemoyer,
L., Cancedda, N., & Scialom, T. (2023). *Toolformer: Language Models Can Teach
Themselves to Use Tools.*

Sigrist, R., Rauter, G., Riener, R., & Wolf, P. (2013). *Augmented visual,
auditory, haptic, and multimodal feedback in motor learning: A review.*

Winstein, C. J. (1991). *Knowledge of results and motor learning: implications
for physical therapy.*

WHO. (2021). *Ethics and governance of artificial intelligence for health.*

Wulf, G., & Shea, C. H. (2002). *Principles derived from the study of simple
skills do not generalize to complex skill learning.*

Wulf, G., Shea, C., & Lewthwaite, R. (2010). *Motor skill learning and
performance: A review of influential factors.*
