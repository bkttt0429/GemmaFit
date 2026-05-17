# GemmaFit - Trustworthy Offline Motion Feedback

> Kaggle Gemma 4 Good Hackathon submission writeup draft.
> Target tracks: Main, Safety & Trust, Health & Sciences, Digital Equity &
> Inclusion, Google AI Edge / LiteRT. This draft is intentionally evidence-bound
> and avoids clinical claims.

## Summary

GemmaFit is an offline Android movement coach for older adults and beginners.
It uses single-camera pose evidence, deterministic safety gates, MotionZip
temporal evidence compression, and local Gemma 4 E2B on LiteRT to produce
bounded coaching summaries. The core idea is simple: the app should help people
move with better awareness, but it must also know when the camera evidence is
not enough.

The hero demo is Senior Strength Mode: chair sit-to-stand, supported squat, and
balance hold support for older adults practicing at home or in community care.
The mode uses large controls, short TTS cues, conservative pauses, and
caregiver-readable summaries. It is dementia-friendly in interaction design,
but it does not screen for dementia, infer cognitive decline, predict fall
risk, diagnose, or prescribe rehabilitation.

## Problem

Single-camera fitness systems often overclaim. They may infer knee valgus from
a side view, imply muscle activation without EMG, or turn a phone video into a
clinical-looking risk score. Those mistakes are especially harmful in senior
care, where a false warning can stop useful activity and a false reassurance can
hide uncertainty.

GemmaFit treats refusal as a product feature. If the user is partially out of
frame, if the selected subject is ambiguous, if a metric cannot be judged from
the view, or if the user asks for diagnosis or fall-risk prediction, the app
shows that boundary directly instead of pretending.

## Architecture

```text
CameraX / Video
-> MediaPipe Pose
-> presence, subject identity, confidence, and judgeability gates
-> derived motion features
-> Senior Layer 2 FSM + ActivityContextTracker
-> MotionFeatureWindow + MotionZip packet
-> SeniorInteractionPolicy
-> ModelInvocationScheduler
-> official Gemma-4-E2B-it LiteRT streaming session
-> Android parser, validator, deterministic fill, fallback
-> UI, TTS, care log, caregiver export, debug receipt
```

The live loop is deterministic. MediaPipe and Kotlin/C++ tools compute pose
confidence, joint-angle proxies, range of motion, tempo, velocity, stabilization
and subject state. Senior Layer 2 converts those features into
`activity_hypothesis`, `phase`, `event`, `judgeability`, and `evidence_refs`.
It can emit `judgeable`, `monitor_only`, or `abstain`, but it does not produce
medical, fall-risk, sarcopenia, rehabilitation, force, GRF, EMG, or diagnosis
claims.

`ActivityContextTracker` is separate from Layer 2. It helps avoid confidently
choosing the wrong activity when motions look similar, such as chair
sit-to-stand versus supported squat. It locks only after repeated consistent
evidence and emits `AMBIGUOUS` when the score is too close.

MotionZip is the evidence compression layer. It preserves event boundaries,
angle extrema, velocity peaks, confidence floors, low-confidence spans,
subject-tracking state, unsupported-claim boundaries, and evidence refs. It
does not store raw video, full landmark streams, ReID embeddings, or clinical
labels. The claim is not lossless video compression; it is task-preserving
compression for the movement facts GemmaFit is allowed to use.

`ModelInvocationScheduler` decides whether to call E2B, skip, defer, or render a
deterministic fallback. Normal live frames, blocked tracking states, user-left
states, no-response states, and multi-person ambiguity do not call E2B. Model
calls are reserved for approved event explanations, session summaries,
caregiver export, and bounded refusal wording.

Gemma 4 E2B is used as a local evidence writer, not as the live safety engine.
It receives compact MotionZip evidence and a strict output contract. Android
then performs JSON cleanup, schema validation, evidence-ref whitelist checks,
forbidden-claim rejection, deterministic fill, and deterministic fallback. A
100-run constrained smoke test did not observe native LiteRT tool-call objects,
so product safety does not depend on native tool-call enforcement.

## Why Gemma 4

Gemma 4 E2B is the P0 baseline because it runs locally through Google AI Edge /
LiteRT and is strong enough to turn compact evidence into useful summaries and
refusals. GemmaFit v5 fine-tuning remains an optional quality layer for future
schema fidelity and wording, not a deadline dependency. `llama.cpp` vision
sidecar experiments are kept as P3 benchmark work because Pixel memory pressure
was too high for the main flow. The submitted product path is therefore simpler
and safer: deterministic live coaching plus official E2B summaries.

## Results

Current Pixel evidence supports the architecture, not clinical efficacy.

| Gate | Result |
| --- | ---: |
| Official E2B artifact size | 2,538,766,336 bytes |
| Official E2B 100-run JSON gate | 100/100 endpoint, generation, and JSON parse success |
| Official E2B generation latency | avg 24.9s, p50 24.8s, p95 26.5s |
| Warm streaming first token | 0.96s to 3.14s after generation start |
| MotionZip dense-vs-compressed key checks | 8/8 pass |
| MotionZip peak velocity difference | 1.89% |
| MotionZip event-frame tolerance | within 6 frames |
| Live camera image path audit | accepted-frame p95 about 10.2ms |

The MotionZip equivalence run compared dense frame-derived evidence against a
compressed MotionZip prompt. The model preserved the tested activity, state set,
event count, event timing, velocity band, peak velocity, confidence floor, and
low-confidence reason. That is the central technical claim: GemmaFit can avoid
feeding every frame to the model while preserving the key facts needed for a
bounded summary.

## Safety And Trust

GemmaFit's trust layer is visible to the user. The UI shows source badges such
as `Pose rules`, `Local Gemma`, `Template fallback`, and `Abstained`. Evidence
cards show what was observed, what was judged, and what was not judged. Debug
reports include backend, model file, scheduler decision, fallback reason,
evidence refs, stream phase, first-token time, thermal status, and per-stage
timings.

Memory is structured and app-owned. Allowed records include session summaries,
calibration baselines, bounded preferences, care logs, dual-task attempts, and
evidence-ledger refs. Blocked records include raw video by default, raw full
landmark streams, free-form model memory, biometric identity, medical labels,
fall-risk scores, sarcopenia scores, force, GRF, and EMG claims.

## Limitations

GemmaFit is not a medical device and does not validate clinical outcomes. It
does not estimate joint force, GRF, EMG, true muscle activation, injury risk,
fall risk, dementia status, sarcopenia, rehabilitation progress, or clinical
improvement. It also does not claim that MotionZip preserves every visual fact
from a video. When evidence is insufficient, the correct behavior is to
abstain, pause, ask for setup, or provide a non-clinical activity summary.

## Closing

GemmaFit's contribution is a practical Safety & Trust pattern for local AI:
deterministic evidence first, compact auditable model context, local Gemma only
when useful, and visible refusal when the camera cannot support a claim. The
result is an offline motion coach that helps older adults and beginners
practice movement while keeping every output inside the evidence boundary.
