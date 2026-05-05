# GemmaFit Literature Review

Last updated: 2026-04-30

This document tracks sources that can be cited in the Kaggle writeup, synthetic
function-calling dataset, and implementation comments. It separates strong
evidence from heuristic thresholds so the project does not over-claim clinical
validity.

## Evidence Levels

| Level | Meaning | How to use in GemmaFit |
|---|---|---|
| A | Primary paper, official documentation, or government/standards source | Safe to cite in writeup and module docs |
| B | Peer-reviewed clinical/biomechanics study with narrower population or task | Use as support, but note population/task limits |
| C | Coaching guideline or practical screening framework | Use for coaching language and UX, not hard diagnostic claims |
| H | Heuristic derived for this prototype | Must be validated on local data before claiming performance |

## Evidence Backbone

These sources support the system architecture and policy boundary rather than
any single threshold. Current implementation maps this layer into a
Capability Contract and an Evidence DAG: the app declares `can_judge` and
`cannot_judge` before Gemma runs, then requires model `evidence_refs` to point
back to structured evidence ids.

| Topic | Current anchor | Project use |
|---|---|---|
| Evidence provenance | W3C PROV-DM / PROV-O (deep review pending) | Model outputs should be traceable to metric, gate, and source module nodes instead of post-hoc prose. |
| Model/data documentation | Model Cards and Datasheets (deep review pending) | Document what the model can and cannot support; do not hide missing provenance behind broad claims. |
| Selective prediction / reject option | Geifman & El-Yaniv, SelectiveNet, conformal prediction for NLP (deep review pending) | Treat unsupported metrics as metric-level abstention, not full-session failure when other evidence is reliable. |
| Pose confidence calibration | BlazePose / MediaPipe confidence docs plus biomechanical validation papers | Confidence gates and confidence ceilings prevent precise-sounding unsupported metric claims. |
| Health-AI transparency | WHO AI health ethics guidance | Users should see useful evidence first and skipped judgments second; GemmaFit remains non-diagnostic. |

## Prototype Thresholds

The following values are implementation thresholds or proxy metrics until
GemmaFit has local calibration evidence. They may appear in debug evidence and
demo explanations, but not as clinically validated claims.

| Metric / rule | Current threshold | Label |
|---|---:|---|
| Knee/ankle ratio | `0.8` | `prototype_threshold` |
| FPPA warning | `10 deg` | `prototype_threshold` |
| Trunk / body-line deviation | exercise-specific `15-55 deg` ranges | `prototype_threshold` |
| Bilateral asymmetry | `10 deg` legacy / template-gated | `prototype_threshold` |
| Rapid movement | `600 deg/s` | `prototype_threshold` |
| ROM insufficient | `<50% expected ROM` | `prototype_threshold` |

## Product Claims

Use the literature to justify a safety-bounded coaching product, not a clinical
diagnostic tool. Product copy should say:

- `camera-limited evidence should abstain from unsupported metrics`
- `pose-only muscle focus is an estimate, not activation percentage`
- `local AI does not override deterministic gates`
- `single-camera metrics are movement-quality proxies`

Do not claim:

- validated injury prediction
- precise joint force or lumbar loading
- EMG-style muscle activation
- clinical improvement, diagnosis, fall-risk score, or sarcopenia detection

## Research Gaps To Fill After Deep Review

- Metric-level abstention is less mature than sample-level selective
  classification; GemmaFit can contribute a practical contract for partial
  movement judgments.
- Pose visibility does not automatically calibrate downstream biomechanical
  error; GemmaFit should treat confidence ceilings per metric as an engineering
  safeguard until validated.
- Quantization-aware tool-call safety for small on-device LLMs is thin; v3
  benchmarks should compare Q4/Q5 schema validity, forbidden-claim rate, and
  evidence-ref validity.
- Augmented feedback evidence supports specificity and timing, but health and
  older-adult claims need conservative non-clinical wording.

## High-Priority Sources

| Source | Evidence | Project use | Notes and limits |
|---|---:|---|---|
| Bazarevsky et al., 2020, "BlazePose: On-device Real-time Body Pose tracking" ([arXiv](https://arxiv.org/abs/2006.10204)) | A | MediaPipe rationale, 33-keypoint topology, mobile real-time story | Good for "on-device real-time pose tracking"; not a clinical validation paper |
| Google Research blog, "On-device, Real-time Body Pose Tracking with MediaPipe BlazePose" ([Google Research](https://research.google/blog/on-device-real-time-body-pose-tracking-with-mediapipe-blazepose/)) | A | Writeup narrative: fitness/yoga use cases, mobile inference, 33 landmarks | Blog source; pair with arXiv paper for formal citation |
| ML Kit Pose Detection Android docs ([Google Developers](https://developers.google.com/ml-kit/vision/pose-detection/android)) | A | Android integration, 33 landmarks, stream mode, confidence handling | ML Kit API differs from MediaPipe Tasks, but landmark/confidence concepts are useful |
| PoseLandmark reference ([Google Developers](https://developers.google.com/android/reference/com/google/mlkit/vision/pose/PoseLandmark)) | A | Avoid magic numbers; justify `visibility`/`inFrameLikelihood` confidence gate | Z coordinate is less accurate than X/Y, so avoid precise depth claims |
| NASM Overhead Squat Assessment article ([NASM](https://blog.nasm.org/certified-personal-trainer/how-to-perform-an-overhead-squat-assessment-osa)) | C | Coaching language for knees moving inward, excessive forward lean, low-back arch, cervical/shoulder checkpoints | NASM is a coaching framework, not a quantitative threshold source |
| NASM OHSA/SLS Assessment Form ([NASM PDF](https://www.nasm.org/docs/default-source/PDF/nasm_ohs_-sls_assessmentform_march2013.pdf)) | C | Demo checklist and labels for common compensation patterns | Use as screening vocabulary only |
| Hewett et al., 2005, "Biomechanical measures ... valgus loading ... predict ACL injury risk" ([DOI](https://doi.org/10.1177/0363546504269591), [Mayo record](https://mayoclinic.elsevierpure.com/en/publications/biomechanical-measures-of-neuromuscular-control-and-valgus-loadin)) | B | Rule #1 priority: dynamic knee valgus matters for safety feedback | Prospective female athlete landing study; do not generalize to all users/exercises as direct injury prediction |
| Ugalde et al., 2015, 2D knee motion during single-limb squats ([PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC4275194/)) | B | Rule #1 measurement method: frontal plane projection angle (FPPA) | Supports 2D analysis of knee valgus; better than only knee/ankle distance ratio |
| Willson/Davis-related FPPA literature summarized in knee valgus studies ([PubMed example](https://pubmed.ncbi.nlm.nih.gov/24380805/)) | B | Rule #1 validation direction for single-leg squat and landing tasks | Use for "FPPA is common in research"; avoid using one universal cutoff |
| CDC Normal Joint Range of Motion Study ([CDC Archive](https://archive.cdc.gov/www_cdc_gov/ncbddd/jointrom/index_1715172647.html)) | A | Rule #7 expected ROM baseline; Rule #3 endpoint awareness | Normative ROM varies by age/sex; use per-joint conservative ranges |
| Beighton hypermobility criteria table ([NCBI Bookshelf](https://www.ncbi.nlm.nih.gov/books/NBK557726/table/article-25342.table0/)) | A | Rule #3 hyperextension: elbow/knee >10 degrees beyond neutral is a known screening criterion | Beighton is for generalized hypermobility screening; GemmaFit must not diagnose hypermobility |
| Parkinson et al., 2021, "The Calculation, Thresholds and Reporting of Inter-Limb Strength Asymmetry: A Systematic Review" ([JSSM PDF](https://www.jssm.org/volume20/iss4/cap/jssm-20-594.pdf), [DOI](https://doi.org/10.52082/jssm.2021.594)) | A | Rule #4 asymmetry framing | The review found 10-15% thresholds are common, but often not well-supported and may need task-, metric-, and population-specific interpretation. This supports monitoring asymmetry, not a universal 10-degree cutoff. |
| Schache et al., 2014, "Evidence for Joint Moment Asymmetry in Healthy Populations during Gait" ([PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC4267535/)) | B | Rule #4 cautionary evidence | Healthy people can show meaningful joint-moment asymmetry during gait. This supports treating asymmetry as a form-quality cue, not an automatic pathology signal. |
| de Leva, 1996, "Adjustments to Zatsiorsky-Seluyanov's segment inertia parameters" ([ScienceDirect](https://www.sciencedirect.com/science/article/pii/0021929095001786), [PDF mirror](https://ebm.ufabc.edu.br/wp-content/uploads/2013/12/Leva-1996.pdf)) | A | `com_tracker.cpp`: segment mass fractions and segment COM fractions | Table 4 is used for the C++ weighted COM estimate; MediaPipe landmark endpoints are practical approximations, not the exact anatomical endpoints in the paper |
| Biomechanical aspects of dynamic stability ([BMC/EURAPA](https://eurapa.biomedcentral.com/articles/10.1007/s11556-006-0006-6)) | B | Rule #5 COM vs base of support | Supports COM/BOS balance framing; phone pose landmarks provide approximation only |
| Hof et al., 2005, "The condition for dynamic stability" ([DOI](https://doi.org/10.1016/j.jbiomech.2004.03.025), [PDF mirror](https://braceworks.ca/wp-content/uploads/2016/05/hof-condition-for-dynamic-stability.pdf)) | A | Rule #5 COM projection / BoS stability relation | Supports static COM-in-BoS check and explains why dynamic movement may require XCoM; current MVP implements the static check only |
| Andrew, 1979, "Another efficient algorithm for convex hulls in two dimensions" ([DOI](https://doi.org/10.1016/0020-0190(79)90072-3), [ScienceDirect](https://www.sciencedirect.com/science/article/pii/0020019079900723)) | A | `com_tracker.cpp`: convex hull of foot contact points for support polygon | Algorithmic source for monotone-chain convex hull; support-point quality still depends on pose landmark quality |
| Papagiannis et al., 2019, sEMG methodology in gait analysis ([PubMed](https://pubmed.ncbi.nlm.nih.gov/31074312/)) | B | MuscleFocusEstimator limitation statement | Supports that sEMG is used to assess muscle activity; posture-only estimate is not EMG |
| Frontiers, "Surface Electromyography Applied to Gait Analysis" ([Frontiers](https://www.frontiersin.org/articles/10.3389/fneur.2020.00994/full)) | B | Muscle activation boundary language | Good for explaining timing/action of muscles from EMG; avoid claiming pose equals activation |
| Vigotsky et al.-style sEMG limitations discussion, "Surface Electromyography: What Limits Its Use..." ([PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC7677519/)) | B | Stronger caveat: even sEMG amplitude has interpretation limits | Useful to keep GemmaFit wording conservative |
| WHO AI health ethics guidance ([WHO publication](https://www.who.int/publications-detail-redirect/9789240037403), [WHO news](https://www.who.int/news/item/28-06-2021-who-issues-first-global-report-on-ai-in-health-and-six-guiding-principles-for-its-design-and-use)) | A | Safety & Trust narrative: safety, transparency, human autonomy | Use to justify confidence gate and non-diagnostic scope |
| ACSM, 2009, "Progression Models in Resistance Training for Healthy Adults" ([MSSE](https://journals.lww.com/acsm-msse/Fulltext/2009/03000/Progression_Models_in_Resistance_Training_for.26.aspx), [PDF mirror](https://tourniquets.org/wp-content/uploads/PDFs/ACSM-Progression-models-in-resistance-training-for-healthy-adults-2009.pdf)) | A | Rule #6 rapid movement framing | ACSM recommends slow-to-moderate repetition velocities for novice/intermediate resistance training and varied velocities for advanced training depending on goals. It supports controlled-tempo coaching, not a universal angular velocity cutoff. |
| Wilk et al., 2021, "The Influence of Movement Tempo During Resistance Training on Muscular Strength and Hypertrophy Responses: A Review" ([PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC8310485/)) | B | Rule #6 movement-tempo context | Movement tempo affects training variables and should be controlled/considered. Use as support for tracking tempo/velocity, not as evidence for a fixed injury threshold. |
| Google Gemma function calling docs ([Google AI](https://ai.google.dev/gemma/docs/capabilities/function-calling)) | A | L4 FunctionRegistry design | Official doc says model outputs must be parsed and validated by application safeguards |
| Google Gemma 4 announcement ([Google Blog](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/)) | A | Gemma 4 edge/offline and function-calling claim | Current official Gemma 4 source found on 2026-04-30 |
| llama.cpp function calling docs ([GitHub](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md)) | A | llama.cpp tool-call backend and `--jinja`/template caution | Tool calling quality depends on model template and quantization choices |
| Unsloth saving to GGUF docs ([Unsloth](https://unsloth.ai/docs/basics/inference-and-deployment/saving-to-gguf)) | A | Finetune export path: `save_pretrained_gguf(..., q4_k_m)` | Keep same chat template between training and inference |

## Mapping to the 8 Safety Rules

| Rule | Current implementation-plan trigger | Evidence status | Recommendation |
|---|---|---:|---|
| #1 Knee lateral deviation | `D_knee / D_ankle < 0.8` | B/H | Keep ratio as a fast heuristic, but add FPPA as the literature-aligned metric. Validate 0.8 against local squat/single-leg fixtures before presenting it as a threshold. |
| #2 Spinal alignment deviation | shoulder-hip-knee deviation > 15 degrees | C/H | NASM supports screening for excessive forward lean/low-back arch. The 15-degree trigger should be documented as a prototype threshold requiring calibration. Avoid "spinal injury risk" claims. |
| #3 Joint hyperextension | near 0 or 180 degrees +/- 5 degrees | A/H | Use joint-specific definitions. For elbow/knee hyperextension, Beighton uses >10 degrees beyond neutral; for other joints, use CDC ROM ranges plus local safe ranges. |
| #4 Bilateral asymmetry | left-right same joint angle difference > 10 degrees | A/H | Literature supports monitoring inter-limb asymmetry, and 10-15% thresholds are common in strength/performance literature. However, the current `>10 degrees` joint-angle trigger is a GemmaFit prototype threshold because percent strength asymmetry does not directly translate to video-derived joint-angle difference. |
| #5 COM offset | COM projection outside support polygon | A/B | Use de Leva segment mass/COM fractions for estimated whole-body COM, project it onto the selected ground plane, build the support polygon from foot contact landmarks with Andrew's monotone-chain convex hull, then test COM-in-polygon. Implementation must call it "estimated COM" because MediaPipe landmarks are not force plates or exact anatomical endpoints. |
| #6 Rapid movement | joint angular velocity > 600 degrees/second | A/H | ACSM supports slow-to-moderate repetition velocities for novice/intermediate resistance training. The `600 deg/s` trigger is still a prototype angular-velocity threshold selected to avoid frame-rate dependence and reduce false positives; validate it per movement and FPS pipeline. Use "slow down for control" coaching language, not injury diagnosis. |
| #7 ROM insufficient | ROM < 50% expected safe ROM | A/H | CDC supports normative ROM tables. Expected ROM must be movement-specific, not universal. Use as "increase range if comfortable" rather than pathology. |
| #8 Neck hyperextension | ear-shoulder-hip deviation > 15 degrees | C/H | Use neutral head/neck coaching language. The 15-degree trigger is a UI/coaching heuristic until validated. |

## Implementation Implications

1. `safety_monitor.cpp` should expose evidence labels with each anomaly:
   `evidence = "validated_metric" | "prototype_threshold" | "low_confidence"`.

2. The JSON sent to Gemma should include `measurement_source`, for example:
   `{"rule": 1, "metric": "knee_ankle_ratio", "evidence": "prototype_threshold"}`.
   This helps the LLM choose conservative wording.

3. Rule #1 should eventually compute both:
   - `knee_ankle_ratio = distance(knees) / distance(ankles)`
   - `fppa_deg`, using hip-knee-ankle frontal-plane projection per side

4. Rule #4 should be reported as `metric = "left_right_angle_delta_deg"` and `evidence = "prototype_threshold"`. Cite asymmetry literature to justify monitoring, but do not cite a 10-degree joint-angle cutoff as validated.

5. Rule #6 should be stored as degrees/second:
   `angular_velocity_dps = abs(angle_t - angle_t_minus_1) / dt_seconds`.
   The old `60 degrees/frame` is too frame-rate dependent. The current native/prototype default is `600 deg/s`, with dataset calibration still required.

6. MuscleFocusEstimator wording must stay in this lane:
   - Allowed: "pose-based estimate", "likely primary load area", "may emphasize"
   - Not allowed: "activation percentage", "muscle is not firing", "diagnosis", "weak/disabled muscle"

7. ConfidenceGate is strongly supported by Google landmark confidence docs and WHO health-AI transparency/safety guidance. Low confidence should bypass Gemma and use a deterministic TTS message.

8. `com_tracker.cpp` implements the MVP static stability check:
   - Whole-body COM is the weighted average of segment COMs.
   - Segment masses and segment COM fractions come from de Leva Table 4.
   - BoS is the convex hull of projected heel/toe landmarks.
   - The output is a coaching/safety cue, not force-plate-grade posturography.

## Citation-Ready Claims

Use these claims in the writeup with citations:

- MediaPipe/BlazePose is appropriate for the edge vision layer because it was designed for real-time mobile pose tracking and outputs a 33-keypoint body topology.
- Pose landmark confidence/visibility supports a safety gate that abstains when the skeleton is not reliable.
- NASM movement assessments support beginner-friendly coaching vocabulary such as knees moving inward, excessive forward lean, low-back arch, and neutral alignment cues.
- Dynamic knee valgus and valgus loading are meaningful biomechanical risk markers in sports medicine literature, but GemmaFit should frame this as "safety cueing" rather than individual injury prediction.
- 2D frontal-plane projection angle is a recognized practical measure for dynamic knee valgus during screening tasks; the current knee/ankle distance ratio is a prototype shortcut.
- Normal ROM reference tables can guide conservative per-joint ranges, but ROM is age-, sex-, and task-dependent.
- Inter-limb asymmetry is commonly monitored in strength and conditioning, but published threshold use varies by metric and task; GemmaFit uses asymmetry as a coaching cue, not diagnosis.
- ACSM resistance-training guidance supports controlled repetition velocity for novice/intermediate users; GemmaFit's deg/s threshold is an implementation heuristic that must be calibrated on local pose data.
- EMG is the appropriate measurement family for muscle activation timing/intensity; pose-only muscle focus must be described as an estimate.
- Function calling should be constrained and application-validated. The model should not execute code or free-form medical advice directly.

## Sources to Avoid or Use Carefully

| Source type | Risk |
|---|---|
| Generic fitness blogs without citations | May overstate injury causality |
| Reddit form-check advice | Useful for user language only; do not cite |
| Single exercise datasets as universal proof | Squat datasets support demos, not "any exercise" validity |
| Any source claiming exact muscle activation from video-only pose | Conflicts with the project safety boundary |
| Any claim that neutral spine always prevents low-back pain | Literature is nuanced; use coaching language instead |

## Open Research Tasks

1. Convert Rule #4 from a fixed 10-degree absolute difference into a normalized asymmetry metric for reporting, while keeping the current trigger as a prototype threshold.
2. Calibrate Rule #6 `600 deg/s` using real video/pose time series by movement pattern, because ACSM supports controlled velocity but does not define a universal angular-velocity cutoff.
3. Build a local validation table from `prototype/data/` once datasets are downloaded:
   `metric`, `threshold`, `dataset`, `precision`, `recall`, `notes`.
4. Add BibTeX entries after final paper selection; current links are sufficient for sprint planning and writeup drafting.
