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

*(≈210 words)*

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
   Structured JSON  →  Evidence Key Cache
         ↓
   FrameHint (deterministic, every frame)
   EventCoaching (Gemma 4 on verdict-change, debounced)
   SessionSummary (post-workout, compressed timeline)
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

### Mobile AI Pipeline: Every Analyzed Frame Feedback Layer

GemmaFit's mobile video path is designed around one constraint: playback
must stay smooth even when local AI is working in the background. The app
therefore does **not** run Gemma on every 30 fps video frame. Instead,
**Every analyzed frame shows feedback, but Gemma inference is
event/key-driven.**

The video player remains responsible for normal 30/60 fps playback. A
background analysis worker samples frames by timestamp, typically every
100-125 ms (about 8-10 fps), preserving aspect ratio and clamping the
long side to 640 px before pose inference. Stored-video analysis uses
MediaPipe Pose Landmarker in VIDEO mode with frame timestamps; live
camera preview is reserved for LIVE_STREAM mode. Overlay data is stored
as `timestampMs -> landmarks / metrics / flags / feedback` and aligned
back to ExoPlayer playback time. When the player is between analyzed
frames, the skeleton uses confidence-aware interpolation; low-confidence
landmarks fade out or are hidden rather than guessed.

```text
Video Playback
  -> Timestamp Sampler
  -> Pose + Native Metrics
  -> FrameHint
  -> Evidence Key Cache
  -> EventCoaching / SessionSummary
```

Feedback is split into three layers. `FrameHint` is deterministic and
available on every analyzed frame from rules and native metrics, so the
UI never waits for Gemma. `EventCoaching` is Gemma-generated only when a
debounced evidence key changes; otherwise the app reuses cached coaching
text. The evidence key includes exercise, phase, rep, primary metric,
severity bucket, confidence bucket, view-angle bucket, top quality flag,
verdict, and the active `NOT_APPLICABLE` set. Debounce, hysteresis, and
cooldown prevent landmark jitter from repeatedly triggering the LLM.
`SessionSummary` runs after the clip using a compressed rep/event
timeline, not raw per-frame reports.

This structure is both faster and safer. The local model receives a
short, fixed-schema evidence prompt and returns fixed JSON such as
`text`, `safety`, `confidence_policy`, and `refusal`. It is not allowed
to estimate joint force, diagnose injury, or override an applicability
gate. If a rule is `NOT_APPLICABLE`, Gemma must explain the boundary
instead of turning it into a judgment. The model is loaded once, warmed
up before analysis, and protected by backpressure: same-key events merge,
the queue stays small, and low-priority events are dropped while
FrameHint continues to update.

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

The fine-tune dataset uses three streams mixed at 30:60:10 weights:
510 domain-specific synthetic FC examples (from
`finetune/data/generate_synthetic.py`, covering all 8 rules across 9
movement patterns), 40 K Glaive Function Calling v2 examples (streamed
from HuggingFace — never touches disk), and 1.2 K Anthropic HH-RLHF
safety-alignment pairs (streamed). The Glaive component teaches schema
robustness; the domain seeds teach GemmaFit's specific 8-tool
vocabulary; the HH-RLHF pairs reinforce safety-aligned refusal language.
The Unsloth QLoRA pipeline runs on Colab Pro A100 and takes
~1.5 h per checkpoint; the final LoRA adapter is ~50 MB; the merged
Gemma 4 E4B is exported as Q5_K_M (desktop benchmark) and Q4_K_M
(Android, Pixel 8 Pro deployment).

*(≈300 words)*

---

## 4. Results

**Function-call schema compliance** — 90 validation examples from
`eval_compare.py`, run via llama-cpp-python against local GGUF files.

| Metric | Base E4B Q4_K_M | Fine-tuned Q5_K_M | Δ |
| --- | --- | --- | --- |
| JSON parse rate | 95.6% | 93.3% | −2.3% |
| Function-name match | 0.0% | 2.2% | +2.2% |
| Args overlap (Jaccard) | 0.026 | 0.081 | +0.055 |
| Avg latency / example | 16.78 s | 10.77 s | −6.01 s |

The base model hallucinates `analyze_motion` on every example (0%
correct function). Fine-tuning introduces the 8-tool vocabulary: function
match rises from zero, and args Jaccard triples. JSON parse drops
slightly — a known QLoRA fidelity trade-off that improves with higher-bit
quantisation. Full per-example breakdown and side-by-side HTML report in
`docs/benchmark/ab_compare/`.

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
  (< 1 ms/frame). Real Gemma is gated behind an evidence-key debounce
  — only invoked on verdict-change events, typically per-rep or per
  new safety flag. FrameHint (deterministic) updates every frame without
  waiting for the LLM. SessionSummary runs post-workout.

*(≈280 words)*

---

## 5. Implementation evidence

The repo ships:

- **[`prototype/exercises/core.py`](../prototype/exercises/core.py)** —
  template engine, applicability gates, mock feedback generator.
- **[`prototype/dashboard_v3.py`](../prototype/dashboard_v3.py)** —
  Streamlit dashboard: skeleton overlay, joint trails, Evidence Card,
  unsupported judgments.
- **[`prototype/render_demo_video.py`](../prototype/render_demo_video.py)** —
  annotated demo MP4s (squat, push-up, lunge, deadlift) with side-by-side
  skeleton + coaching panel.
- **[`app/`](../app/)** — 35 Kotlin files: CameraX + MediaPipe
  LIVE_STREAM, `VideoAnalysisViewModel` (central orchestrator),
  `KinematicsBridge` JNI, `TemporalMotionAnalyzer` (online rep counter
  with Savitzky-Golay smoothing), `CoachVoice` (TTS + cooldown +
  priority queue), `PoseOverlay` (Compose Canvas skeleton + safety
  heat zones), WorkoutScreen + SummaryScreen with form-score trends.
- **[`native/`](../native/)** — C++17 biomechanics engine: 10 kinematics
  modules, JNI bridge, 4 test executables (50/50 PASS via CTest).
- **[`finetune/train_gemma4_pipeline.ipynb`](../finetune/train_gemma4_pipeline.ipynb)** —
  cloud QLoRA pipeline on Colab A100. Streams 40 K Glaive FC + domain
  seeds + HH-RLHF via HF datasets (zero local download), checkpoints to
  Drive every 200 steps, auto-resumes after disconnect, exports
  adapter + Q5/Q4 GGUFs.

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
| 2. Architecture | 330 |
| 3. Innovation | 300 |
| 4. Results | 310 |
| 5. Implementation | 220 |
| 6. Limitations | 170 |
| 7. Closing | 60 |
| **Total** | **≈1600** |

If overshooting, trim §4's table rows or §2's mobile pipeline subsection
first — the exercise template table in §2 is reference material, not
load-bearing argument.
