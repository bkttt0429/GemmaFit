# GemmaFit — Trustworthy Multi-Exercise Motion Feedback

> **Kaggle Gemma 4 Good Hackathon submission.**
> Target length: **≤1500 words** (current draft ≈1600).
> Tracks: Main · Safety & Trust (primary) · Health & Sciences · llama.cpp · Unsloth Special.
>
> All FC benchmark `[FILL]` placeholders have been replaced with actual numbers
> from `eval_compare.py` (see [docs/benchmark/](benchmark/)).
> Refusal benchmark placeholders remain — pending next fine-tuning checkpoint.

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

GemmaFit ships in two modes. **General Fitness Mode** is the broader
multi-exercise foundation (squat, push-up, lunge, deadlift). **Senior
Strength Mode** is the hero demo — safe, offline home movement coaching
for older adults at risk of strength decline, with templates for
sit-to-stand, supported squat, and balance hold. The two modes share the
pose pipeline and Evidence Card schema, and diverge in default UI scale,
voice speed, and the mandatory unsupported-judgment payload (sarcopenia,
fall-risk, rehab prescription, muscle mass) attached to every Senior
Mode verdict.

*(≈300 words)*

---

## 2. System architecture

```text
Video / Camera
   ↓ MediaPipe Pose Landmarker (33 keypoints + visibility)
   ↓ float[99] → JNI KinematicsBridge
   ↓
C++ Biomechanics Pipeline (every frame, <1 ms)
   ├─ Confidence Gate ─── low visibility → LOW_CONFIDENCE
   ├─ Joint Angles (12) + Body Segments (11)
   ├─ Symmetry Evaluator
   ├─ COM Tracker (de Leva 1996, support-polygon)
   ├─ Safety Monitor (8 rules)
   ├─ Movement Classifier (pattern, not exercise name)
   ├─ Muscle Focus Estimator (pose-based, not EMG)
   └─ Motion Quality Report (template-aware, view-aware)
         ↓
   Structured JSON  ->  Evidence DAG + Capability Contract
         ↓
   FrameHint (deterministic, every frame)
   SessionSummary (post-workout, one async local Gemma call)
         ↓
   Kotlin: CoachVoice (TTS + cooldown)  +  Compose UI (PoseOverlay)
```

The decision flow is staged so that *every* judgment has a documented
provenance. The Trust Matrix attaches one of seven statuses
(`OK / VIEW_LIMITED / LOW_CONFIDENCE / NOT_APPLICABLE / MONITOR /
WARNING / CRITICAL`) to every active rule. The Evidence Card records the
numerical evidence behind a verdict and *equally prominently*, the
unsupported judgments the system explicitly will not give — joint force,
clinical injury risk, EMG-style activation percentages, medical
diagnosis. Before Gemma runs, the app declares a **Capability Contract**:
which metrics are currently judgeable, which are blocked, why they are
blocked, and which Evidence DAG node ids support each allowed metric.

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

### Mobile pipeline: deterministic live feedback, summary-only Gemma

GemmaFit does **not** run Gemma every video frame. A background worker
samples 8-10 fps by timestamp and runs MediaPipe Pose Landmarker (VIDEO
mode for stored clips, LIVE_STREAM for camera). Feedback is layered:
`FrameHint` is deterministic from rules + native metrics and updates
every analyzed frame so the UI never waits on the LLM. Gemma runs once
after analysis completes, using a compressed `SessionSummary`, the
Capability Contract, and Evidence DAG refs, not raw per-frame reports.
The local model receives a fixed-schema prompt and returns one function
call; it is not allowed to estimate force, diagnose injury, cite missing
evidence, or override an applicability gate.

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

The fine-tune dataset (v2 recipe) mixes three streams at **60:30:10**:
510 domain FC examples expanded to 2 040 chat-format rows by
`finetune/data/format_expand.py` — each trained under four prompt
wrappings (production, bare, terse, chinese) so the Android
system-prompt format is in distribution; Glaive FC v2 (schema
robustness); Anthropic HH-RLHF (refusal alignment) — both streamed,
never touched disk. The v1 ratio of 30:60:10 starved the domain head
(see §4); the v2 flip is the central training fix. Unsloth QLoRA on
Colab A100 (~1.5 h), ~50 MB LoRA adapter, exports Q5_K_M and Q4_K_M
GGUFs.

The third Safety & Trust pillar is **Evidence-Bounded Long-Term Memory**.
GemmaFit uses event-triggered local memory: critical events are saved
immediately, caregiver exports are human-readable but non-clinical, and
calibration baseline updates are proposed only from repeated
high-confidence clean reps. Gemma is a *summarizer*, not a state
machine — every memory write is proposed via a `request_memory_update`
function call and validated by a Kotlin policy engine that checks
schema, provenance (≥ 1 evidence id for any trend note), refusal regex
(blocks sarcopenia, fall-risk, muscle-mass keywords), confidence floor,
and idempotency. Read access is a closed enum (`PROFILE`, `CALIBRATION`,
`TRENDS_7D`, `TRENDS_30D`, `EVIDENCE_FOR_SESSION`); raw evidence rows
never enter coaching prompts. Raw video is never persisted.

*(≈410 words)*

---

## 4. Results

**Function-call schema compliance — v1 (shipped) and v2 (in flight).**
90 validation examples from `eval_compare.py`, run via llama-cpp-python
against local GGUF files (both Q4_K_M, GPU-offloaded to compare on the
same hardware).

| Metric | Base E4B Q4_K_M | v1 Fine-tuned Q4_K_M | Δ |
| --- | --- | --- | --- |
| JSON parse rate | 95.6% | 93.3% | −2.3% |
| Function-name match | 0.0% | 2.2% | +2.2% |
| Args overlap (Jaccard) | 0.026 | 0.081 | +0.055 |
| Avg latency / example | 16.78 s (CPU) | 10.77 s (GPU 24/42 layers) | n/a |

The v1 numbers are honest: the fine-tune barely shifted function-name
selection. A matched-format eval (training-prompt distribution, both
models GPU) confirmed the diagnosis — under the v1 training prompt the
FT model echoes the input back as a "Motion report:" string, never
emits the `{"function": "...", "args": ...}` schema. Root cause is the
v1 recipe (30% domain mix, single bare prompt format), not quantisation
or training-loss convergence. The **v2 retraining queued on Colab**
applies the prompt-format expansion and 60:30:10 mixture flip described
in §3; the post-retrain A/B re-runs `eval_compare.py --prompt-format
production` against `models/gemmafit-v2-q4_k_m.gguf`. Full per-example
breakdown and the diagnosis report in
[`docs/benchmark/`](benchmark/README.md).

**Refusal benchmark** — 29 hand-curated scenarios across 8 categories
(wrong view, wrong template, low-confidence, dynamic COM, unknown
exercise, out-of-scope, cross-template misapplication, multi-subject).
Benchmark infrastructure (`refusal_eval.py`) and scenario definitions are
complete; model evaluation pending the next fine-tuning checkpoint.

| Axis | Base | Fine-tuned | Δ |
| --- | --- | --- | --- |
| Pass rate (all-three) | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Refusal axis | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Mention axis | [FILL: %] | [FILL: %] | [FILL: ±%] |
| Safety axis (no forbidden tokens) | [FILL: %] | [FILL: %] | [FILL: ±%] |

Source: `prototype/refusal_eval.py`. Detailed per-scenario reports will
go to `docs/benchmark/refusal/report.html`.

**Movement-quality validation** (Phase 1 benchmarks).

- **169/169 PASS** across all Python prototype unit tests (joint angles
  36/36, 8 safety rules 67/67, COM tracker 16/16, movement classifier
  35/35, muscle focus 15/15).
- **50/50 PASS** across C++ native unit tests (COM tracker 16/16,
  safety monitor 8/8, kinematics pipeline 12/12, motion quality 14/14).
- Zenodo squat-image benchmark (3,806 images): bad-back trunk-lean
  P = 1.000, R = 0.072 (conservative threshold at 15° deviation
  achieves zero false positives). Bad-heel proxy F1 = 0.787.

**Local inference** (llama.cpp on Tensor G3).

- Gemma 4 E4B Q4_K_M GGUF: ~1.5 GB runtime memory on Pixel 8 Pro.
- Fine-tuned Q5_K_M inference: **10.77 s** average on 90-example eval.
- In-app design: per-frame biomechanics runs on C++ native layer
  (< 1 ms/frame). Live cues are deterministic; real Gemma runs summary-only
  after full analysis using the SessionSummary, Capability Contract, and
  Evidence DAG refs. FrameHint updates every frame without waiting for the LLM.

*(≈280 words)*

---

## 5. Implementation evidence

The repo ships:

- **[`prototype/`](../prototype/)** — Streamlit dashboard, template
  engine + applicability gates ([`exercises/core.py`](../prototype/exercises/core.py)),
  annotated demo MP4 renderer.
- **[`app/`](../app/)** — 35 Kotlin files: CameraX + MediaPipe
  LIVE_STREAM, `VideoAnalysisViewModel`, `KinematicsBridge` JNI,
  `TemporalMotionAnalyzer` (online rep counter, Savitzky-Golay),
  `CoachVoice` (TTS with cooldown + priority queue), `PoseOverlay`
  (Compose Canvas), Workout + Summary screens.
- **[`native/`](../native/)** — C++17 biomechanics engine: 11
  modules including a multi-person `subject_selector` (auto-pick on
  area + center + visibility, persistence on center + 8-keypoint
  skeleton geometry), JNI bridge, 5 test executables (50/50 + 19/19
  PASS).
- **[`app/.../memory/`](../app/src/main/kotlin/com/gemmafit/memory/)** —
  Evidence-Bounded Memory: `Schemas`, `MemoryStore` (Room + DataStore
  Proto + JSONL audit), `MemoryWritePolicy` (6-step validation),
  `RefusalValidator`, `AdaptiveRecalibration`, `MemoryAwarePromptBuilder`
  with pre-warm cache. 19 JVM unit tests pass.
- **[`finetune/train_gemma4_pipeline.ipynb`](../finetune/train_gemma4_pipeline.ipynb)** —
  Colab A100 QLoRA pipeline; streams datasets, checkpoints to Drive
  every 200 steps, exports adapter + Q5/Q4 GGUFs. Total persistent
  storage < 4 GB; runs on free Kaggle 2×T4 or Colab Pro A100 in one
  session.

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
- detect or screen for sarcopenia, predict fall risk, prescribe
  rehabilitation, or estimate muscle mass — Senior Mode evidence cards
  always carry these as `unsupported_judgments`, even when the metric
  itself is healthy.
- judge knee valgus / FPPA from side or back views (geometry undefined).
- emit `CRITICAL` from a single video frame (requires temporal
  persistence).
- judge multiple subjects in one frame without explicit subject lock
  (the C++ subject_selector's `NEEDS_SELECTION` state requires user
  confirmation).
- store raw video by default. Memory holds structured metric records
  only; caregiver export is opt-in and ships with a mandatory
  non-clinical disclaimer block.

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
| 1. Problem + dual-mode framing | 300 |
| 2. Architecture (mobile pipeline collapsed) | 220 |
| 3. Innovation (refusal + v2 recipe + memory) | 380 |
| 4. Results (v1 + v2 plan) | 310 |
| 5. Implementation (bullets compressed) | 200 |
| 6. Limitations | 220 |
| 7. Closing | 60 |
| **Total** | **≈1690** |

Still ~190 words over the 1500 target. Remaining trim candidates:
§3's three-stream paragraph could drop "Glaive (schema robustness);
Anthropic HH-RLHF (refusal alignment)" parentheticals (-25); §4's
diagnosis paragraph could collapse the format-mismatch sentence (-50);
§6's bullets list could merge sarcopenia + multi-subject + raw-video
into one sentence each (-40). Reaching 1500 cleanly likely requires
cutting one full paragraph from §3 (e.g. dropping the FC schema
robustness justification). Senior Mode framing (§1), v2 recipe
diagnosis (§3, §4), and Memory policy engine (§3) are the new
load-bearing additions and should not be trimmed.
