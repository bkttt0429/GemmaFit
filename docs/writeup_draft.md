# GemmaFit — Trustworthy Multi-Exercise Motion Feedback

> **Kaggle Gemma 4 Impact Challenge submission draft.**
> Target length: **≤1500 words** (current draft ≈1500).
> Tracks: Main · Safety & Trust (primary) · Health & Sciences · LiteRT · llama.cpp · Unsloth Special.
>
> **Placeholders marked `[FILL: …]` need numbers from the trained model.**
> Replace them after `eval_compare.py` and `refusal_eval.py` produce results
> (see [docs/benchmark/](benchmark/)).

---

## 1. Problem — pose apps overclaim

Single-camera fitness apps habitually overclaim. They report *knee valgus
angles* from side-view footage where the metric is geometrically
undefined. They show *muscle activation percentages* that no pose model
can possibly measure. They predict *injury risk* without an MRI, an EMG,
or a clinician. Users see clean dashboards and start to believe the
numbers — until the numbers contradict their bodies.

The honest move is not to silence these systems. Pose-based feedback
genuinely helps people who cannot afford a coach. The honest move is to
make the system *know what it cannot say*. **GemmaFit's central claim is
trust:** the system gives feedback when evidence supports it, and
*refuses* when evidence does not — out loud, with reason, and visibly to
the user. Demoing both halves is harder than demoing one, but only the
combination is responsibly deployable on a Pixel-class phone in a real
gym.

This positioning matches Gemma 4's edge profile (E4B at Q4_K_M ≈ 1.5 GB,
< 100 ms tokenisation latency on Tensor G3) and the Safety & Trust
track's core question: *how do we ship local AI that is helpful without
pretending?* GemmaFit's answer is the **Correct judgment + correct
refusal** dual demo.

*(≈210 words)*

---

## 2. System architecture

```text
Video / Camera
   ↓ MediaPipe Pose Landmarker (33 keypoints + visibility)
   ↓
Confidence Gate ─── low visibility → LOW_CONFIDENCE / VIEW_LIMITED
   ↓ usable
Motion Trace Builder (joint angles, COM, tempo, ROM)
   ↓
Heuristic Exercise Classifier ─→ Squat / Push-up / Lunge / Deadlift / Unknown
   ↓
Exercise Template Selector  (only relevant rules activated)
   ↓
Template-specific Metric Extractor
   ↓
Applicability + Quality Gates
   ↓
Structured Motion Report  →  Trust Matrix  →  Evidence Card
   ↓
Safe Gemma 4 prompt  →  llama.cpp / LiteRT inference
   ↓
Coaching feedback (text + TTS)
```

The decision flow is staged so that *every* judgment has a documented
provenance. The Trust Matrix attaches one of seven statuses
(`OK / VIEW_LIMITED / LOW_CONFIDENCE / NOT_APPLICABLE / MONITOR /
WARNING / CRITICAL`) to every active rule. The Evidence Card records the
numerical evidence behind a verdict and *equally prominently*, the
unsupported judgments the system explicitly will not give — joint force,
clinical injury risk, EMG-style activation percentages, medical
diagnosis.

Four exercise templates ship in the MVP:

| Exercise | Active metrics | Refused metrics |
| --- | --- | --- |
| **Squat** | depth, knee/hip angle, trunk lean, tempo, COM monitor | knee valgus on side view; precise joint force |
| **Push-up** | elbow angle, body line, hip sag, depth, tempo | knee valgus, COM/BoS (not load-bearing on floor) |
| **Lunge** | front knee angle, step length, trunk uprightness | single-frame bilateral asymmetry (intentionally unilateral) |
| **Deadlift** | hip hinge, trunk angle, bar/body path proxy, tempo | lumbar disc pressure, force-plate-equivalent load |

Pose extraction runs on MediaPipe Pose Landmarker (Lite). Joint angles
and 600 °/s rapid-movement detection (Rule 6) use Savitzky-Golay smoothing
to avoid one-frame outliers. The same Structured Motion Report is
consumed by both the Streamlit prototype dashboard and the Android app's
Compose UI — the two surfaces never disagree.

*(≈320 words)*

---

## 3. Innovation — Correct refusal as a hero feature

The most differentiated design choice is *making refusal visible*. Most
fitness apps hide their limits in a tiny disclaimer at the bottom of a
splash screen. GemmaFit elevates the refusal:

- The Workout screen has a dedicated **"Cannot judge from this view"**
  card that lists every rule the system declined to apply, with a
  one-line reason for each.
- Every Evidence Card contains an explicit `unsupported_judgments` array
  shown to the user — not just `joint_force` and `clinical_injury_risk`,
  but also the *reason* (single-camera proxy, not measured force).
- The Gemma 4 system prompt enforces a refusal vocabulary at generation
  time: phrases like *"I cannot judge X from this view"* and *"this
  rule does not apply to <exercise>"* are reinforced through fine-tuning.
- The benchmark suite ([docs/benchmark/refusal/](benchmark/refusal/))
  scores 29 hand-curated refusal scenarios across 8 categories
  (wrong-view, wrong-template, low-confidence, dynamic-COM,
  unknown-exercise, out-of-scope query, cross-template
  misapplication, multi-subject). Each scenario tests three
  axes: did the model **refuse**, did it **mention** the right boundary,
  did it **avoid** forbidden tokens like `Newtons`, `EMG`, `bpm`,
  `your fppa reading`?

Concretely, this turns the project's safety story from a checkbox into a
measurable competitive metric. *Correctly refusing a side-view FPPA query*
is a feature with a number attached, not just rhetoric.

The fine-tune dataset (≈600 hand-curated FC examples + 40 K Glaive FC
streamed from HuggingFace + 200 hand-written refusal pairs) is mixed
30 / 60 / 10. The Glaive component teaches schema robustness; the domain
seeds teach GemmaFit's specific function vocabulary; the refusal pairs
teach the *language* of safe boundaries. The Unsloth QLoRA pipeline runs
on Colab Pro A100 in ≈1.5 hours per checkpoint; the LoRA adapter is
≈50 MB; the merged Gemma 4 E4B is exported as Q5_K_M (desktop) and
Q4_K_M (Android, Pixel 8 Pro deployment).

*(≈300 words)*

---

## 4. Results

**Function-call schema compliance** (90 validation examples).

| Metric | Base Gemma 4 E4B | Fine-tuned GemmaFit | Δ |
| --- | --- | --- | --- |
| JSON parse rate | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Function-name match | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Args overlap (Jaccard) | [FILL: float] | [FILL: float] | [FILL: ±float] |
| Avg latency / example | [FILL: s] | [FILL: s] | — |

Source: `prototype/eval_compare.py` over `finetune/data/fc_training_data.json` validation split.

**Refusal benchmark** (29 scenarios across 8 categories).

| Axis | Base | Fine-tuned | Δ |
| --- | --- | --- | --- |
| Pass rate (all-three) | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Refusal axis | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Mention axis | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Safety axis (no forbidden tokens) | [FILL: %] | [FILL: %] | [FILL: ±%] |

Source: `prototype/refusal_eval.py`. Detailed per-scenario reports in
`docs/benchmark/refusal/report.html`.

**Movement-quality validation** (Phase 1 dataset benchmarks).

- 202/202 PASS on the integrated `test_phase1_showcase.py` suite.
- 67/67 PASS on the 8-rule biomechanics gate suite.
- Zenodo squat-image benchmark: trunk-lean detection P = 1.000, R = 0.072
  (high precision, low recall — used as conservative threshold validation, not as ground truth).
- Bad-heel proxy F1 = 0.787.

**Local inference**.

- Gemma 4 E4B Q4_K_M GGUF: ≈3.5 GB on disk, ≈1.5 GB allocated on
  Pixel 8 Pro Tensor G3 NPU via llama.cpp.
- E2B Q4_K_M: ≈1.8 GB on disk, ≈800 MB allocated. Used for fast iteration.
- Per-frame coaching latency: mock_gemma_feedback **<1 ms**, real Gemma
  E2B **≈5 s** (CPU), Gemma E4B **≈[FILL] s**. We therefore call the
  real model only on **verdict-change events** (per-rep), keeping the
  per-frame UX responsive.

*(≈300 words)*

---

## 5. Implementation evidence

The repo ships:

- **[`prototype/exercises/core.py`](../prototype/exercises/core.py)** —
  template engine, applicability gates, mock_gemma_feedback (13/13 tests
  pass).
- **[`prototype/dashboard_v3.py`](../prototype/dashboard_v3.py)** —
  Streamlit dashboard: skeleton overlay, joint trails, Evidence Card,
  unsupported judgments. Matches the Android UI exactly.
- **[`prototype/render_demo_video.py`](../prototype/render_demo_video.py)** —
  produces the four annotated demo MP4s used in the YouTube cut
  (squat, push-up, lunge, deadlift).
- **[`app/`](../app/)** — Android app: CameraX live feed, native
  KinematicsBridge JNI, Trust-first WorkoutScreen with Verdict Pill +
  Cannot-Judge section.
- **[`native/`](../native/)** — C++17 kinematics core; ctest 3/3 pass.
- **[`finetune/train_gemma4_pipeline.ipynb`](../finetune/train_gemma4_pipeline.ipynb)** —
  cloud QLoRA pipeline. Streams datasets from HuggingFace
  (zero local download), checkpoints to Google Drive every 200 steps,
  auto-resumes after disconnect, exports adapter + Q5/Q4 GGUFs.

Total persistent training storage: < 4 GB (LoRA adapter + two GGUF
quantisations); the source datasets never touch disk. The whole pipeline
runs on a free Kaggle Notebook (2 × T4) or Colab Pro A100 in one session.

*(≈210 words)*

---

## 6. Limitations and boundaries

GemmaFit is a movement-quality feedback system, not a clinical tool.
The system explicitly does **not**:

- estimate joint force, lumbar disc pressure, or torque (single-camera
  pose has no force ground truth).
- estimate muscle activation percentage (no EMG sensor).
- predict injury risk (not a diagnostic device, not validated for
  clinical outcomes).
- give medical diagnoses of any kind.
- judge knee valgus / FPPA from side or back views (geometry undefined).
- emit `CRITICAL` from a single video frame (requires temporal
  persistence).
- judge multiple subjects in one frame (refuses until single-subject).

Threshold values in [`finetune/data_collection/knowledge_base/
thresholds_curated.json`](../finetune/data_collection/knowledge_base/thresholds_curated.json)
each carry an explicit citation
(NSCA, Hewett 2005, Powers 2010, Schoenfeld 2010, etc.). Thresholds
labelled `prototype_threshold` are conservative defaults; thresholds
labelled `validated` are calibrated against open datasets and noted
as such in code comments.

*(≈170 words)*

---

## 7. Closing

GemmaFit ships local AI feedback that knows its limits. It runs offline
on Pixel 8 Pro, refuses unsupported judgments out loud, and produces an
evidence card for every verdict. The Correct judgment + correct refusal
demo is the project's core impact claim — and the only honest way to put
fitness AI in front of a user without a clinician in the loop.

*(≈60 words)*

---

## Appendix — Word count check

| Section | Approx words |
| --- | --- |
| 1. Problem | 210 |
| 2. Architecture | 320 |
| 3. Innovation | 300 |
| 4. Results | 300 |
| 5. Implementation | 210 |
| 6. Limitations | 170 |
| 7. Closing | 60 |
| **Total** | **≈1570** |

If overshooting, trim §2's exercise table or §5's repo enumeration
first — those are reference material, not load-bearing argument.
