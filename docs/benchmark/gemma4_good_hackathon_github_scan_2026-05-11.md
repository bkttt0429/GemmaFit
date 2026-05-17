# Gemma 4 Good Hackathon GitHub quick scan

Date: 2026-05-11  
Method: GitHub public repository search through `gh search repos` using:

- `"Gemma 4 Good Hackathon"`
- `"Gemma 4" "Good Hackathon"`
- `"Gemma 4 Impact Challenge"`
- `"Gemma 4" hackathon`

Scope note: this is a quick public GitHub scan, not a full code review. It does not include private repositories, Kaggle-only notebooks, untagged repos, or projects that do not mention these keywords in public metadata. Most summaries below are based on repository name and public description; a few high-signal projects were also checked from visible README/code context in prior review.

## 2026-05-12 follow-up scan

Method: GitHub public repository search through `gh search repos --sort updated`, using the original queries plus broader variants:

- `"Gemma 4 Good Hackathon"`
- `"Gemma 4 Good"`
- `"Gemma 4 Impact Challenge"`
- `"gemma4good"`
- `"gemma-4-good"`
- `"Gemma 4" hackathon`
- `"Gemma 4" Kaggle`
- `"Gemma 4" "on-device"`
- `"Gemma 4" "LiteRT"`
- `"Gemma 4" "health"`

Follow-up result: 121 unique public repositories were found in this broader pass. Compared with the 111 repositories already captured in this document, 65 repository names were not present in the 2026-05-11 table. Most of the new items are sparse shells, generic app scaffolds, or infrastructure demos; the important change is that one direct physiotherapy competitor and several strong on-device / evidence-story competitors are now visible.

### New high-signal repos

| Repo | Why it matters | GemmaFit response |
|---|---|---|
| [birwatkar123-collab/physiogemma](https://github.com/birwatkar123-collab/physiogemma) | Direct health/exercise overlap. It positions as an AI physiotherapy assistant, extracts clinical parameters, assigns levels from guideline-style rules, and produces exercise prescriptions. | Treat this as the clearest new direct competitor. GemmaFit should not compete on clinical prescription. Win on camera-derived movement evidence, senior-only activity events, non-diagnostic boundaries, and on-device Android evidence receipts. |
| [mechramc/Marunthagam](https://github.com/mechramc/Marunthagam) | Strongest new safety architecture story: offline Android, deterministic protocol engine below the LLM, encrypted SQLite logs, LoRA/MoE framing, and asymmetric safety escalation. | Borrow the narrative pattern: deterministic safety floor + schema validation + encrypted / auditable records. Avoid the medical triage claim surface. |
| [anchildress1/vestige](https://github.com/anchildress1/vestige) | Very strong local-only behavioral-record product framing. It emphasizes source-cited patterns, markdown as source of truth, no therapy framing, no mood scoring, local Gemma 4 E4B via LiteRT-LM, and explicit privacy tests. | GemmaFit caregiver logs should cite evidence windows and abstain reasons, not just model prose. Use "patterns with sources" as a report design benchmark. |
| [suzyeth/familiar](https://github.com/suzyeth/familiar) | Strong "verified by observed evidence" story: Gemma 4 watches the screen locally and verifies focus instead of trusting self-report. | GemmaFit's equivalent is verified motion evidence: pose visibility, phase/event, rep/balance window, and source labels for measured vs generated content. |
| [Devank-Garg/gemma-voice-android](https://github.com/Devank-Garg/gemma-voice-android) | Android Gemma 4 E2B voice assistant using LiteRT-LM native audio input plus on-device TTS. This is a strong voice UX and model-readiness reference. | GemmaFit should show a clear voice fallback ladder: Android TTS, bounded prompts/buttons, optional ASR, and model-ready/missing state. Do not make voice the core safety path. |
| [zkproofport/rn-litert-gemma4](https://github.com/zkproofport/rn-litert-gemma4) | React Native LiteRT-LM bindings with Gemma 4-first API, function-calling surface, constrained decoding, speculative decoding, and native memory metrics. More platform/infrastructure than product, but technically relevant. | GemmaFit should keep function-calling validation visible: constrained schema, exact tool selection, event-driven invocation, backend/source label, and latency/memory measurements. |
| [Cyclenerd/android-llm-server](https://github.com/Cyclenerd/android-llm-server) | Mature Android local LLM server story: Gemma 4 E2B/E4B, LiteRT, OpenAI-compatible API, downloadable APK, model management, and performance/battery tradeoff language. | Borrow model-management UX: model installed/missing/downloading, load status, warmup, tokens/sec, battery warning. This does not replace GemmaFit's in-app bounded pipeline. |
| [binh812/LocalAIServer](https://github.com/binh812/LocalAIServer) | Another Android inference server with LiteRT-LM, OpenAI-compatible API, streaming, background service, and multimodal endpoints. | Confirms model-serving UX is becoming table stakes. GemmaFit should not spend P0 building a general server, but should expose debug/readiness states. |
| [z8837/gemma4-on-device](https://github.com/z8837/gemma4-on-device) | Flutter on-device Gemma 4 studio with E2B/E4B presets, `.litertlm` import, text/image/audio chat, streaming, local settings/history. | Useful as a generic on-device multimodal app benchmark. GemmaFit must differentiate with domain evidence, not generic chat. |
| [l69d/healthbridge](https://github.com/l69d/healthbridge) | Multilingual multimodal health literacy and urgency triage, with image input and 31B cloud/API path. High impact but claim-risky and not primarily on-device. | Do not chase broad triage. GemmaFit's safer claim is non-diagnostic movement support and caregiver/professional-share summaries. |

### Updated competitor read

The direct movement/coach competitor set is no longer just FormForward and Maestro. Add **PhysioGemma** as a direct adjacent competitor because it owns "exercise prescription / physiotherapy" language. This is also a useful warning: judges will see exercise + health projects, so GemmaFit's writeup must clearly separate:

- **What GemmaFit measures:** pose-derived motion evidence, phase/event, visibility, confidence, rep/balance windows.
- **What GemmaFit does not claim:** diagnosis, rehab outcome, fall-risk score, sarcopenia, pain treatment, force/EMG/GRF, clinical exercise prescription.
- **What GemmaFit outputs:** bounded coaching, abstain reasons, care logs, and shareable summaries with evidence references.

The strongest non-direct competitors are not those with the biggest model. They are the ones with credible product boundaries: Vestige's source-cited private records, Marunthagam's deterministic safety floor, and Android LLM server projects' model-management clarity. GemmaFit should copy those product discipline patterns while keeping the unique camera-pose evidence path.

### Immediate 2026-05-12 recommendations

1. Add **PhysioGemma** to the competitor slide as the new direct health/exercise adjacent competitor.
2. Add one writeup paragraph contrasting GemmaFit with physiotherapy/triage apps: GemmaFit is wellness and motion-evidence support, not medical prescription.
3. Strengthen the demo UI around evidence provenance:
   - `Measured by pose rules`
   - `Layer 2 event`
   - `Generated by local Gemma`
   - `Template fallback`
   - `Abstained: low confidence`
4. Borrow the Android LLM server projects' model readiness language:
   - model found/missing
   - loading/warmup
   - backend name
   - fallback reason
   - latency / event count
5. Add a small "claims boundary" card in the demo or writeup. The growing number of medical/physio competitors makes GemmaFit's safer boundary more important, not less.

## 2026-05-12 deep competitor feature analysis

This section looks past repository count and focuses on what strong competitors can actually demonstrate: input evidence, model deployment, control layers, proof artifacts, and the feature pattern GemmaFit should copy or avoid.

### Analysis axes

| Axis | What to inspect | Why it matters for GemmaFit |
|---|---|---|
| Domain claim | Wellness, education, triage, rehab, safety audit, caregiver support | High-impact domains are crowded. GemmaFit needs a crisp non-diagnostic movement-support claim. |
| Input evidence | Pose landmarks, photos, text intake, vitals, screen state, uploaded media, voice | Projects with observed evidence are stronger than pure chat apps. GemmaFit's strongest evidence is real-time pose-derived motion. |
| Model deployment | LiteRT-LM on Android, Ollama desktop, hosted API, browser model, server routing | Judges will reward real-device proof. GemmaFit should emphasize Pixel/local execution and disclose fallback states. |
| Control layer | Deterministic rules, protocol engine, function calling, JSON schema, refusal policy | Strong safety projects do not let the model free-run. GemmaFit should show C++ rules, Layer 2 events, and FC validation as the control layer. |
| Proof artifacts | Benchmarks, held-out evals, screenshots, APK, latency, calibration, tests | SiteSafe, SilverAid, and Marunthagam are dangerous because they show proof, not just a demo idea. |
| Weakness | Claim risk, desktop dependency, no realtime path, no eval, broad scope | GemmaFit should attack narrow gaps: realtime senior motion, evidence receipts, and safe claim boundaries. |

### Tier 1: direct and adjacent competitors

| Repo | Strongest feature | Technical pattern | Weak spot / opening for GemmaFit | Practical response |
|---|---|---|---|---|
| [Manutd1234/FormForward](https://github.com/Manutd1234/FormForward) | Running-form analysis with video, Garmin-style context, research extraction, and Gemma-assisted form recommendations. | Browser MediaPipe Pose Landmarker, biomechanical angle extraction, server-side Gemma/Ollama style analysis. | Closest "form analysis" competitor, but it appears running-specific, web/desktop-oriented, and not senior-care or Android realtime first. | Do not pitch as generic form analysis. Pitch as senior-specific realtime pose evidence on Pixel with sit-to-stand, balance, abstain, and care-log outputs. |
| [birwatkar123-collab/physiogemma](https://github.com/birwatkar123-collab/physiogemma) | Physiotherapy assistant: clinical intake, red flags, condition-specific knowledge, exercise prescription. | Google AI Studio Gemma 4 26B, function declarations, keyword red-flag rules, lightweight condition RAG. | It can own the "physio prescription" language, but that is also claim-risky and not camera-evidence based. | Keep GemmaFit out of prescription/rehab claims. Say it supports movement practice and evidence summaries, not diagnosis or treatment planning. |
| [joycezoon/silveraid](https://github.com/joycezoon/silveraid) | Senior independence system with pendant camera, emergency detection, caregiver dashboard, fine-tune/eval evidence. | Pixel 9 pendant with Gemma 4 E2B LiteRT-LM + YOLOv8n; Mac home base with Gemma 4 26B; deterministic parsers first; dual verification for insulin. | Strongest senior-care breadth competitor. However, it is broad monitoring and uses a two-device architecture. | Win with a narrower phone-only movement-quality lane: realtime pose, Layer 2 activity events, exercise-specific evidence windows, and no raw video storage. |
| [jiahknee5/maestro-gemma](https://github.com/jiahknee5/maestro-gemma) | Physical-skill coach for violin practice with on-device small model and larger summary model. | iOS pose/audio signals, E2B on-device feedback cadence, 27B local/server summaries, source badges. | Strong coaching UX, but not health/senior movement and likely not Android/Pixel. | Copy source badges and event cadence. For GemmaFit, every coaching line should show measured/source/fallback provenance. |
| [SAIRAJ41/OfflineMedic](https://github.com/SAIRAJ41/OfflineMedic) | Fully offline medical assistant with text/voice/photo triage and emergency workflow. | Flutter, Gemma 4 E4B LiteRT, Whisper.cpp, local TTS, emergency color triage. | High-impact but medically risky and broad. | Do not compete on triage. Use it as contrast: GemmaFit is non-diagnostic wellness with visible capability limits. |

### Tier 2: high-signal safety, evidence, and product-boundary competitors

| Repo | Strongest feature | Why judges may like it | What GemmaFit should borrow |
|---|---|---|---|
| [SamEthanMathew/sitesafe](https://github.com/SamEthanMathew/sitesafe) | Photo-to-OSHA violation reports with cited regulations, function calling, SQLite knowledge base, calibration, and measured evals. | It has a measurable Safety & Trust story: held-out image set, macro recall, ECE calibration, latency, citations, and abstain/compliant examples. | Add a small but concrete FC benchmark: exact tool selection, evidence-ref coverage, refusal/abstain rate, latency, and examples of blocked unsupported claims. |
| [mechramc/Marunthagam](https://github.com/mechramc/Marunthagam) | Offline Tamil triage with deterministic WHO/IMNCI protocol engine, encrypted records, schema audit, and asymmetric escalation. | It is serious about safety: rules can escalate but not downgrade, confidence below threshold escalates, and outputs are schema constrained. | Frame GemmaFit's safety floor the same way: deterministic pose/safety gates can block or abstain; the LLM cannot override low confidence or invent clinical labels. |
| [Kushalk0677/fieldaid-offline-disaster-copilot](https://github.com/Kushalk0677/fieldaid-offline-disaster-copilot) | Offline disaster workflow that turns notes/photos/videos into action plans and sync-later handoffs. | The workflow is operational, not just chat: incident packet, local knowledge, human verification, export/sync-later. | Use "motion evidence packet" as GemmaFit's equivalent: event window, derived features, judgeability, backend, and care-log export. |
| [anchildress1/vestige](https://github.com/anchildress1/vestige) | Local-only ADHD pattern tracker with source-cited records and explicit no-therapy/no-score boundary. | Product boundary is unusually mature; privacy and "patterns with sources" are core features. | Build caregiver/professional summaries as sourced patterns from motion evidence, not opaque model advice. |
| [suzyeth/familiar](https://github.com/suzyeth/familiar) | Local desktop companion that verifies focus using observed screen state instead of self-report. | Memorable demo idea: the AI observes objective evidence and responds. | GemmaFit should say "verified by observed pose evidence," not "AI guessed your exercise." |
| [codecravings/learnify-offline](https://github.com/codecravings/learnify-offline) | Offline learner twin, mastery path, teach-it-back, accessibility, and personalization. | Strong product polish and a clear private-memory loop. | Borrow private progress memory carefully: store compact training/activity records and personalized preference summaries, not raw video or clinical scores. |

### Runtime and infrastructure competitors

| Repo | Feature signal | Relevance to GemmaFit |
|---|---|---|
| [Devank-Garg/gemma-voice-android](https://github.com/Devank-Garg/gemma-voice-android) | Android voice assistant with LiteRT-LM E2B native audio, Kokoro TTS via ONNX, VAD, WorkManager model download, model status UI. | Good reference for voice UX and model readiness. GemmaFit should keep voice optional and bounded, with Android TTS fallback. |
| [Cyclenerd/android-llm-server](https://github.com/Cyclenerd/android-llm-server) | Android local LLM server with LiteRT, OpenAI-compatible API, foreground service, model management, and battery/thermal tradeoff docs. | Confirms that model management and performance disclosure are becoming table stakes. GemmaFit needs a model-ready/debug card. |
| [zkproofport/rn-litert-gemma4](https://github.com/zkproofport/rn-litert-gemma4) | React Native LiteRT-LM bindings with function calling, constrained decoding, speculative decoding, and memory metrics. | Infrastructure reference for constrained outputs and memory metrics. GemmaFit should show FC schema validation and per-event invocation metrics. |
| [z8837/gemma4-on-device](https://github.com/z8837/gemma4-on-device) | Flutter on-device Gemma 4 studio with `.litertlm` import, text/image/audio, streaming, settings/history. | Generic on-device multimodal chat is not enough. GemmaFit must differentiate through domain evidence and bounded safety logic. |

### Cross-competitor feature patterns to copy

| Pattern | Seen in | GemmaFit implementation target |
|---|---|---|
| Deterministic first, LLM last | SilverAid, Marunthagam, SiteSafe | MediaPipe/C++/Layer 2 decide visibility, safety gates, event windows, and abstain reasons before E2B sees anything. |
| Evidence packet / receipt | FieldAid, SiteSafe, Vestige | Store `MotionFeatureWindow`, `activity_hypothesis`, `phase`, `event`, `judgeability`, `evidence_refs`, backend, fallback, and blocked-claim list. |
| Source badge per output | Maestro, SilverAid, Vestige | UI labels: `pose rule`, `Layer 2 event`, `local Gemma`, `template fallback`, `abstained`. |
| Calibration / abstain metrics | SiteSafe, Marunthagam | Report pose visibility abstain rate, low-confidence abstain rate, FC exact-match, evidence-ref coverage, and latency. |
| Local knowledge or policy table | SiteSafe, PhysioGemma, FieldAid | Add local claim-boundary and senior activity guidance JSON assets; let Gemma cite IDs instead of inventing policy. |
| Model readiness UX | Android LLM Server, Gemma voice Android, z8837 app | Show `.litertlm` found/missing, backend, load state, warmup, last event latency, and fallback reason. |
| Private memory loop | Learnify, Vestige, SilverAid | Keep compact derived records and preference summaries; no raw video, no raw landmarks in memory, no clinical score accumulation. |

### Where GemmaFit can still win

1. **Realtime senior motion evidence.** Most health competitors are text/photo triage or care assistants. GemmaFit can show live pose-derived events: sit-to-stand completed, balance hold started/completed, low-confidence abstain.
2. **Pixel-only bounded pipeline.** SilverAid and Maestro use larger host models for summaries. GemmaFit can be simpler and more contest-aligned if the core loop works offline on Pixel with event-driven E2B.
3. **Non-diagnostic safety boundary.** PhysioGemma/OfflineMedic/Marunthagam are closer to clinical territory. GemmaFit should turn restraint into a trust feature: wellness coaching and shareable evidence, no fall-risk/rehab/sarcopenia claims.
4. **Function-calling plus evidence refs.** SiteSafe is the bar here. GemmaFit needs comparable proof that Gemma outputs bounded tools/templates tied to motion evidence windows.
5. **Caregiver/professional-share reports.** This is a high-impact surface that does not require clinical claims. A report can say what was observed, what was skipped, and what the user reported subjectively.

### Main risks after this competitor review

| Risk | Why it matters | Mitigation before deadline |
|---|---|---|
| No real-device proof | Android runtime competitors can look more credible even with less domain depth. | Record Pixel smoke: model found, backend, one event-triggered response, one abstain case. |
| No eval table | SiteSafe, SilverAid, and Marunthagam will look more rigorous. | Add a small benchmark table: FC exact match, blocked unsupported claims, evidence-ref coverage, event latency. |
| Clinical wording drift | Physio/medical competitors make judges sensitive to unsafe claims. | Put a visible Product Claims Matrix in writeup/demo and enforce blocked labels in prompts/tests. |
| Generic AI coach framing | FormForward/Maestro already cover physical coaching. | Lead with senior motion evidence and care-log workflow, not "AI fitness coach." |
| Overbuilding model training | Training Layer 2 or personalization can consume the sprint without demo proof. | Keep P0 as FSM baseline + recorder + event scheduler; use stored records as future personalization evidence. |

### Recommended slide / writeup positioning

Use this comparison frame:

> Compared with medical triage apps, GemmaFit avoids diagnosis and works from measured movement evidence. Compared with generic form coaches, it targets senior-safe activities and abstains when pose evidence is not judgeable. Compared with generic on-device LLM demos, it shows a bounded realtime pipeline: pose rules, Layer 2 events, function-calling output, and auditable evidence receipts.

For the deck, group competitors into four buckets:

1. **Direct motion coaching:** FormForward, Maestro.
2. **Senior/care health:** SilverAid, PhysioGemma, OfflineMedic, Marunthagam.
3. **Safety/evidence leaders:** SiteSafe, FieldAid, Vestige, Familiar.
4. **On-device runtime proof:** Gemma voice Android, Android LLM Server, RN LiteRT Gemma 4, z8837 Gemma4 on-device.

## Snapshot

GitHub search returned 111 unique public repositories.

| Category | Count | Pattern |
|---|---:|---|
| Health / care | 25 | Offline medical assistants, triage, medication interaction, senior/dementia support, diabetes/wound care |
| Education | 24 | Offline tutors, math/study assistants, classroom fine-tuning, language learning |
| Other / unclear | 24 | Generic hackathon shells, broad tools, sparse descriptions |
| Safety / trust | 13 | Scam protection, abuse documentation, child safety, governance/audit agents |
| Climate / agriculture / resilience | 11 | Disaster response, heatwave, weather, planting, farmers, ecological resilience |
| Accessibility / inclusion | 8 | AAC, voice/interpreter, local-language assistants, digital equity |
| Creative / personal | 3 | Dream journal, artist, game/commentary tools |
| Coaching / movement | 2 | Running/form analysis and coaching-like apps |
| Industrial / engineering | 1 | Production/operations copilot |

## What most teams are doing

The dominant pattern is "offline/local-first assistant for a vulnerable context": health, rural medicine, education, crisis support, disaster response, scam protection, or accessibility. Many teams position Gemma as the natural-language layer over a specialized workflow, but the public metadata often does not show hard validation gates, real-device deployment, or deterministic safety boundaries.

There are many health projects, but most are medical-intake, triage, symptom, medication, or public-health intelligence apps. That is good for impact framing but also riskier from a claims and safety perspective. GemmaFit should avoid drifting into diagnosis and keep the product claim as non-diagnostic movement-quality and care-log support.

Education is crowded. A generic offline tutor is unlikely to stand out unless it has strong deployment proof, personalization, or a novel interface. GemmaFit's senior dual-task mode overlaps education only lightly; the stronger story is health-adjacent wellness plus privacy-preserving on-device evidence.

Direct movement/coach competitors are sparse. The visible projects closest to GemmaFit are FormForward and Maestro. FormForward is about running form analysis; Maestro is an AI violin coach for kids. Neither appears to be an offline Pixel senior movement-quality system with deterministic biomechanics and evidence contracts.

## High-signal competitors to watch

| Repo | Why it matters | GemmaFit response |
|---|---|---|
| [Manutd1234/FormForward](https://github.com/Manutd1234/FormForward) | Direct "form analysis" competitor. Uses video, wearable/Garmin-style context, and Gemma/Ollama style analysis. | Win on real-time Pixel path, senior mode, evidence-first gates, phase/event Layer 2, and explicit non-diagnostic boundaries. |
| [joycezoon/silveraid](https://github.com/joycezoon/silveraid) | Senior independence assistant, E2B Android pendant plus larger local model. Very close in target audience. | Differentiate with camera-based movement evidence, care logs, subjective check-ins, and dual-task motion prompts. |
| [SAIRAJ41/OfflineMedic](https://github.com/SAIRAJ41/OfflineMedic) | Strong offline health framing: LiteRT, Whisper, Flutter, zero server. | Avoid competing on medical triage; position GemmaFit as safer wellness/activity support with visible evidence. |
| [jiahknee5/maestro-gemma](https://github.com/jiahknee5/maestro-gemma) | On-device coaching for a physical skill, with small + large model routing. | GemmaFit needs concrete Pixel evidence, motion feature windows, and false-positive reduction for physical movement. |
| [codecravings/learnify-offline](https://github.com/codecravings/learnify-offline) | Fully on-device multi-agent learning app. Strong offline education framing. | Keep dual-task as a senior wellness feature, not a general tutor. |
| [Abhi183/lumen](https://github.com/Abhi183/lumen) | Browser-based eye-gaze AAC; strong accessibility story and client-side claim. | GemmaFit should show privacy and accessibility through senior-friendly UI, voice, large buttons, and offline reports. |
| [Kushalk0677/fieldaid-offline-disaster-copilot](https://github.com/Kushalk0677/fieldaid-offline-disaster-copilot) | Strong offline crisis workflow: photos/videos to cited action plans. | GemmaFit should make evidence packets and auditability equally visible in demo. |
| [mhamedtabout/mediboussole](https://github.com/mhamedtabout/mediboussole) / [Sinabro-wooseok/villagedoc](https://github.com/Sinabro-wooseok/villagedoc) | WHO IMCI / CHW medical assistant framing. High impact, but medical-risky. | Stay wellness-only and use product-claims matrix as a strength, not a limitation. |
| [SamEthanMathew/sitesafe](https://github.com/SamEthanMathew/sitesafe) | On-device safety violation detector using Gemma E4B + Unsloth + Ollama. | Comparable safety/evidence narrative; GemmaFit should emphasize real-time mobile performance and deterministic gates. |
| [achbj/SilentWitness](https://github.com/achbj/SilentWitness) | Safety & Trust with on-device privacy for abuse documentation. Strong social-impact story. | GemmaFit needs a crisp senior-care story: private in-home movement support and caregiver-ready logs. |

## GemmaFit positioning after this scan

The strongest path is not "generic AI fitness coach." Many repos already claim local-first, vulnerable populations, health, and education. GemmaFit should be framed as:

> A privacy-preserving, on-device senior movement-support system that converts camera pose evidence and self-reported exertion into bounded coaching, care logs, and caregiver/professional-share summaries without making medical claims.

The win condition should be concrete demo evidence:

- Real Android/Pixel path, not only desktop Python or web demo.
- Deterministic evidence gates before model output.
- Layer 2 temporal phase/event evidence, not single-frame template judgment.
- Function-call/router validation with refusal and evidence-ref checks.
- Senior Hero flows: sit-to-stand, balance/step touch, dual-task prompt, subjective check-in, persona reports.
- Clear Product Claims Matrix: no fall-risk score, diagnosis, force, EMG, GRF, heart-rate status, sarcopenia, or rehab progress.

## Architecture patterns worth borrowing without changing the model

These are implementation patterns seen across high-signal competitors that GemmaFit can use without retraining, replacing, or enlarging the model.

| Pattern to borrow | Seen in | How to apply in GemmaFit | Why it helps |
|---|---|---|---|
| Deterministic parser first, model last | SilverAid, FormForward, SiteSafe | Keep C++/Kotlin motion features, phase FSM, subjective check-in parser, and refusal regex authoritative. Call E2B only for bounded report/function selection. | Improves safety and latency without model changes. |
| Tool-backed local knowledge base | SiteSafe, FieldAid | Add local `ProductClaimsMatrix`, `care_log_templates`, `senior_activity_guidance`, and `unsupported_claims` tables or JSON assets. E2B can cite IDs, not invent policy. | Makes answers auditable and less generic. |
| Evidence receipt / audit trail | HAIC governance agent, FieldAid, SilentWitness | For every care log/report, store `evidence_refs`, `capability_contract`, model backend, fallback flag, and refused claims. | Turns safety constraints into a visible product strength. |
| Source badge for every AI output | Maestro, SilverAid | UI should show `deterministic`, `litert-lm`, `fallback`, or `template` on debug and caregiver views. | Judges can see what was measured versus generated. |
| Sync-later export | FieldAid, SilentWitness | Export caregiver/professional-share summary as JSON/PDF/text when offline, with later share intent. | Fits home-care workflows and avoids cloud dependency. |
| Encrypted local records | SilentWitness | Keep Room DB, but add optional encrypted export or Android Keystore-backed sensitive notes for senior logs. | Stronger privacy story for in-home care. |
| Offline STT/TTS fallback ladder | OfflineMedic, Learnify, SilverAid | Voice is optional: Android TTS always available; ASR only for bounded choices; gesture/buttons are fallback. | Avoids demo failure when speech/noise is poor. |
| In-app model/artifact install flow | Learnify | Add a visible "model ready / missing / installing" state and resumable sideload/download instructions for `.litertlm`. | Prevents setup ambiguity in judging and Pixel demo. |
| Cheap detector plus heavy reasoning only on events | SilverAid, FieldAid, SiteSafe | MediaPipe pose every frame, optional YOLO burst only for subject/object confirmation, E2B only on Layer 2 events/session summaries. | Better battery and latency without changing model. |
| Calibration and confidence language | SiteSafe, FormForward | Show confidence bands for pose visibility, phase confidence, evidence reliability, and abstain reasons. | Reduces overclaiming and false positives. |
| Persona-specific report renderer | Learnify, FieldAid, care/health projects | Same evidence packet renders senior, caregiver, and professional-share versions with bounded templates. | Adds perceived intelligence without free medical generation. |
| Local network caregiver dashboard | SilverAid | Keep as P1: phone can expose a local debug/caregiver summary page or shareable export, not cloud. | Good demo story, but should not block P0. |

### P0 recommendations for GemmaFit

1. Add an `EvidenceReceipt` object to every LLM-visible output:
   - `session_id`
   - `trigger`
   - `activity_context`
   - `motion_feature_window`
   - `layer2_event`
   - `capability_contract`
   - `evidence_refs`
   - `backend`
   - `fallback`
   - `unsupported_claims_blocked`

2. Add visible backend/source labels in debug and caregiver reports:
   - `Measured by pose rules`
   - `Generated by local Gemma`
   - `Template fallback`
   - `Abstained: low confidence`

3. Add local template/RAG assets before adding more model training:
   - `senior_activity_templates.json`
   - `unsupported_claims_matrix.json`
   - `caregiver_report_templates.json`
   - `dual_task_prompt_bank.json`

4. Add an offline export path:
   - caregiver text summary
   - professional-share structured summary
   - raw evidence JSON for debugging

5. Add a model readiness screen or debug card:
   - `.litertlm` found/missing
   - backend load status
   - last smoke result
   - fallback reason

### Patterns to avoid for this deadline

- Full medical triage wording from OfflineMedic/VillageDoc-style projects. This increases risk and does not fit GemmaFit's evidence boundary.
- Always-on YOLO or always-on E2B video reasoning. It will hurt latency/battery and is unnecessary if Layer 2 event sampling works.
- Two-device large-model routing as a P0 feature. SilverAid and Maestro use this effectively, but GemmaFit should first prove the Pixel-only path.
- Raw "model sees video and decides" architecture. GemmaFit should keep pose-derived evidence and capability gates in front of all generation.

## Category scan table

| Category | Repository | Public description / rough read | Language | Stars |
|---|---|---|---|---:|
| Accessibility / inclusion | [Abhi183/lumen](https://github.com/Abhi183/lumen) | Free browser-based eye-gaze AAC with on-device Gemma word prediction. Runs entirely client-side. | TypeScript | 0 |
| Accessibility / inclusion | [AlexiosBluffMara/mercury](https://github.com/AlexiosBluffMara/mercury) | Local-first multimodal AI agent for digital equity, positioned for Gemma 4 Good and Nous Mercury. | Python | 2 |
| Accessibility / inclusion | [cbrethick/Vaanisetu](https://github.com/cbrethick/Vaanisetu) | Tamil AI assistant. | Dart | 0 |
| Accessibility / inclusion | [hyeminss11/true-voice-gemma4](https://github.com/hyeminss11/true-voice-gemma4) | TrueVoice, likely voice/accessibility oriented. | Jupyter Notebook | 0 |
| Accessibility / inclusion | [Khalenanassers/Chispa](https://github.com/Khalenanassers/Chispa) | Multilingual AI companion for working adults. | Jupyter Notebook | 0 |
| Accessibility / inclusion | [mintypizza/GeneSight](https://github.com/mintypizza/GeneSight) | Genetic variant interpreter using Gemma 4. | Python | 0 |
| Accessibility / inclusion | [prathik-anand/vaani](https://github.com/prathik-anand/vaani) | Voice-first offline interpreter for low-literacy adults using Gemma 4 E4B. | Python | 0 |
| Accessibility / inclusion | [umerkhan95/agent-readiness-optimizer](https://github.com/umerkhan95/agent-readiness-optimizer) | Helps small merchants become visible to AI shopping agents. | Python | 0 |
| Climate / agriculture / resilience | [altugikiz/TarlAI](https://github.com/altugikiz/TarlAI) | AI-powered agricultural assistant. | Python | 0 |
| Climate / agriculture / resilience | [BathSalt-2/meridian](https://github.com/BathSalt-2/meridian) | Multimodal ecological resilience intelligence. | Kotlin | 0 |
| Climate / agriculture / resilience | [dp-web4/gemma4-good-submission](https://github.com/dp-web4/gemma4-good-submission) | Attested resilience and self-governing AI in constrained environments. | Python | 0 |
| Climate / agriculture / resilience | [genyarko/cocoaguard](https://github.com/genyarko/cocoaguard) | Cocoa/agriculture guard project. | Dart | 0 |
| Climate / agriculture / resilience | [jaeyow/weatherspeak-ph](https://github.com/jaeyow/weatherspeak-ph) | Multilingual severe-weather communications for the Philippines. | Jupyter Notebook | 0 |
| Climate / agriculture / resilience | [Kushalk0677/fieldaid-offline-disaster-copilot](https://github.com/Kushalk0677/fieldaid-offline-disaster-copilot) | Offline disaster response copilot for shelter notes, photos, videos, action plans, and sync-later handoffs. | Python | 1 |
| Climate / agriculture / resilience | [NehaShukla161/Mandi-Mate](https://github.com/NehaShukla161/Mandi-Mate) | Offline Marathi decision agent for smallholder tomato farmers. | Dart | 0 |
| Climate / agriculture / resilience | [seanesla/moraine](https://github.com/seanesla/moraine) | Offline glacial lake outburst flood warning / arrival-time tool. | TypeScript | 0 |
| Climate / agriculture / resilience | [tarun-rai21/aegis-gemma](https://github.com/tarun-rai21/aegis-gemma) | Offline heatwave forecaster plus Gemma agentic survival advice. | Python | 0 |
| Climate / agriculture / resilience | [tkaushik015/climate-calendar](https://github.com/tkaushik015/climate-calendar) | Climate-adapted planting companion for smallholder farmers. | Python | 0 |
| Climate / agriculture / resilience | [uzma-a/carbonmirror](https://github.com/uzma-a/carbonmirror) | AI climate future simulator. | JavaScript | 0 |
| Coaching / movement | [JJRPF/dreamlit](https://github.com/JJRPF/dreamlit) | Private on-device dream journal and lucid dreaming coach. | Kotlin | 2 |
| Coaching / movement | [Manutd1234/FormForward](https://github.com/Manutd1234/FormForward) | Form-analysis hackathon project; prior quick review found running/video + Gemma/Ollama style workflow. | JavaScript | 0 |
| Creative / personal | [Aayush-coder1/Gemma-Live-Artist](https://github.com/Aayush-coder1/Gemma-Live-Artist) | Live artist / creative Gemma 4 hackathon project. | Python | 0 |
| Creative / personal | [debjganguly/game-design-kaggle](https://github.com/debjganguly/game-design-kaggle) | Game design brainstorming using Gemma. | Jupyter Notebook | 1 |
| Creative / personal | [kim-jaedeok/gemma4-hackathon](https://github.com/kim-jaedeok/gemma4-hackathon) | Korean gameplay to English highlight commentary. | Python | 0 |
| Education | [Aayush-gdeveloper/EduLite-AI](https://github.com/Aayush-gdeveloper/EduLite-AI) | Offline AI-powered study assistant. | Python | 0 |
| Education | [adindamochamad/codebuddy](https://github.com/adindamochamad/codebuddy) | AI coding tutor for Indonesian students. | Python | 1 |
| Education | [amritansh005/The-Gemma-4-Good-Hackathon-writeup-](https://github.com/amritansh005/The-Gemma-4-Good-Hackathon-writeup-) | Future of Education writeup/project shell. | Python | 0 |
| Education | [bartek-ogorkiewicz/math-tutor-gemma](https://github.com/bartek-ogorkiewicz/math-tutor-gemma) | On-device math tutor with Manim animations. | Python | 0 |
| Education | [bill-lipeprotocol/gemmaedu-companion](https://github.com/bill-lipeprotocol/gemmaedu-companion) | Offline multimodal AI tutor. | TypeScript | 0 |
| Education | [carlkrott/localmind-education-ai](https://github.com/carlkrott/localmind-education-ai) | Offline-first AI education companion. | Python | 0 |
| Education | [codecravings/learnify-offline](https://github.com/codecravings/learnify-offline) | Fully on-device multi-agent learning app powered by Gemma 4 E2B. | Dart | 0 |
| Education | [EliseuODaniel/eduassist-gemma-good](https://github.com/EliseuODaniel/eduassist-gemma-good) | Local-first school assistance demo. | Python | 0 |
| Education | [ExtinctEvil69/SmartStudyAI](https://github.com/ExtinctEvil69/SmartStudyAI) | Learning ecosystem with shared memory layer. | Python | 0 |
| Education | [fagiteemmanuel4-bit/Luma](https://github.com/fagiteemmanuel4-bit/Luma) | Adaptive AI learning engine. | TypeScript | 0 |
| Education | [FranJGT/EduBridge](https://github.com/FranJGT/EduBridge) | Offline-first AI math tutor. | TypeScript | 0 |
| Education | [Hastws/eduedge-gemma4](https://github.com/Hastws/eduedge-gemma4) | Offline AI tutor powered by Gemma 4 E4B. | Python | 0 |
| Education | [jackasser/tsumugiya](https://github.com/jackasser/tsumugiya) | Local AI tutor for code onboarding. | Python | 0 |
| Education | [jadhavroshani713-sys/EduLens-AI](https://github.com/jadhavroshani713-sys/EduLens-AI) | Multilingual learning assistant. | - | 0 |
| Education | [janvi-t11/NativeMinds](https://github.com/janvi-t11/NativeMinds) | Offline Indian regional-language tutor for grades 1-8 using Ollama. | Dart | 0 |
| Education | [jtmuller5/gemmacademy](https://github.com/jtmuller5/gemmacademy) | Fine-tune Gemma 4 E2B per classroom and ship to phones for offline tutoring via LiteRT-LM. | Python | 0 |
| Education | [KDLearner123/Edge-Responder](https://github.com/KDLearner123/Edge-Responder) | Offline-first emergency intelligence for first responders, powered by E2B via llama.cpp. | Python | 0 |
| Education | [pannonia-dao/adaptive-social-translator](https://github.com/pannonia-dao/adaptive-social-translator) | Social translator / compatibility layer to reduce misunderstanding. | Python | 0 |
| Education | [PerzivaL099/Nexus-Learner](https://github.com/PerzivaL099/Nexus-Learner) | Edge-AI academic success and wellness counselor with GraphRAG. | Python | 0 |
| Education | [SamEthanMathew/sitesafe](https://github.com/SamEthanMathew/sitesafe) | On-device OSHA construction safety violation detector using Gemma 4 E4B, Unsloth, Ollama. | Python | 0 |
| Education | [Sayemnyc/teachmate](https://github.com/Sayemnyc/teachmate) | Offline-first AI tutor. | Python | 0 |
| Education | [shrikrishnadevkar/gemma-4-good-hackathon](https://github.com/shrikrishnadevkar/gemma-4-good-hackathon) | Personalized learning platform with AI tutor, quizzes, guidance. | CSS | 0 |
| Education | [U2SY26/gemma4-particle-edu](https://github.com/U2SY26/gemma4-particle-edu) | Gemma plus 3D particle physics simulator for science education. | JavaScript | 0 |
| Education | [Vibeaman/studybuddy](https://github.com/Vibeaman/studybuddy) | Offline AI tutor. | JavaScript | 0 |
| Health / care | [5seoyoung/woundwatch](https://github.com/5seoyoung/woundwatch) | Diabetic foot ulcer tracking and amputation-risk early warning. | JavaScript | 1 |
| Health / care | [AlexiosBluffMara/cortex](https://github.com/AlexiosBluffMara/cortex) | Brain-response prediction in real-time 3D, with AI personas explaining video response. | Python | 1 |
| Health / care | [bhuvaneshwari-99/SwasthyaSetu-AI-](https://github.com/bhuvaneshwari-99/SwasthyaSetu-AI-) | Offline multilingual rural health assistant with pharmacy connectivity. | - | 1 |
| Health / care | [brenio55/medlens](https://github.com/brenio55/medlens) | Prescription and exam-result interpretation support. | Dart | 0 |
| Health / care | [dsremo/mediscan](https://github.com/dsremo/mediscan) | Offline medical triage assistant for underserved communities. | Python | 0 |
| Health / care | [elisa-fa/dAIbetes](https://github.com/elisa-fa/dAIbetes) | Diabetes management app with AI assistant. | Python | 0 |
| Health / care | [farmountain/carevoice-gemma4](https://github.com/farmountain/carevoice-gemma4) | Offline multilingual clinical intake assistant. | Python | 0 |
| Health / care | [Hem810/mediedge](https://github.com/Hem810/mediedge) | Offline AI health assistant for rural India. | HTML | 0 |
| Health / care | [hssling/tb-sentinel-ai](https://github.com/hssling/tb-sentinel-ai) | Public-health intelligence for TB burden and hotspot detection. | Python | 0 |
| Health / care | [infohalaplume-creator/bassira-nafudh-gemma4](https://github.com/infohalaplume-creator/bassira-nafudh-gemma4) | Medical cognitive safety assistant that asks a diagnostic challenge question; says it does not diagnose. | Python | 0 |
| Health / care | [jarvasai/-Sahaya-Ai](https://github.com/jarvasai/-Sahaya-Ai) | Offline-first multilingual AI companion for dementia caregivers. | - | 0 |
| Health / care | [Javierg720/gemma-remember-apk](https://github.com/Javierg720/gemma-remember-apk) | On-device dementia care app powered by Gemma 2B. | JavaScript | 0 |
| Health / care | [joycezoon/silveraid](https://github.com/joycezoon/silveraid) | Voice-first elderly independence assistant using E2B Android pendant plus larger local model. | Kotlin | 0 |
| Health / care | [Luiznunes13/3notes](https://github.com/Luiznunes13/3notes) | Hospital maintenance RAG system with Gemma via Ollama. | HTML | 0 |
| Health / care | [marbatis/carebridge-gemma4good](https://github.com/marbatis/carebridge-gemma4good) | Local-first maternal and family resilience assistant. | Python | 0 |
| Health / care | [mhamedtabout/mediboussole](https://github.com/mhamedtabout/mediboussole) | Offline IMCI triage assistant for community health workers, multimodal and RAG-grounded on WHO IMCI. | Python | 0 |
| Health / care | [prakash023-hub/ams-gemma4](https://github.com/prakash023-hub/ams-gemma4) | Offline antibiotic stewardship AI for Indian hospitals. | Python | 0 |
| Health / care | [Rawia337/MedShield](https://github.com/Rawia337/MedShield) | AI medical assistant for drug interaction detection. | - | 0 |
| Health / care | [Ritabanm/pulse-neo](https://github.com/Ritabanm/pulse-neo) | Offline multimodal neonatal resuscitation triage. | - | 0 |
| Health / care | [SAIRAJ41/OfflineMedic](https://github.com/SAIRAJ41/OfflineMedic) | Offline medical assistant for rural communities using LiteRT, Whisper, Flutter; zero internet/server. | Dart | 0 |
| Health / care | [SankarSubbayya/sentinel-health](https://github.com/SankarSubbayya/sentinel-health) | Offline-first clinical decision support. | Python | 0 |
| Health / care | [Sinabro-wooseok/villagedoc](https://github.com/Sinabro-wooseok/villagedoc) | CHW medical assistant with Gemma 4 E4B and WHO IMCI. | Python | 0 |
| Health / care | [swaritbkp/medi-guide-ai](https://github.com/swaritbkp/medi-guide-ai) | Multimodal health assistant for rural India. | - | 0 |
| Health / care | [worthyfarmstead-rgb/medcheck](https://github.com/worthyfarmstead-rgb/medcheck) | Multilingual medication interaction checker using Gemma multimodal AI. | - | 0 |
| Health / care | [worthyfarmstead-rgb/medcheck-gemma](https://github.com/worthyfarmstead-rgb/medcheck-gemma) | Offline health symptom checker powered by Gemma 4. | Python | 0 |
| Industrial / engineering | [Whyme-Labs/gemma-4-hack](https://github.com/Whyme-Labs/gemma-4-hack) | BakerySense production copilot for retail chains and forecasting. | TypeScript | 0 |
| Other / unclear | [AndrewGossenPerez/The-Gemma-4-Good-Hackathon](https://github.com/AndrewGossenPerez/The-Gemma-4-Good-Hackathon) | Generic competition entry shell. | - | 0 |
| Other / unclear | [aniruddhgoteti/idiotypeforge](https://github.com/aniruddhgoteti/idiotypeforge) | Personalized lymphoma therapy design with Gemma, AlphaFold, RFdiffusion. | Python | 0 |
| Other / unclear | [bwbayu/gemma_project](https://github.com/bwbayu/gemma_project) | Generic Gemma 4 Hackathon 2026 repo. | - | 0 |
| Other / unclear | [chiwonseuh-hub/Kaggle-Hackathon](https://github.com/chiwonseuh-hub/Kaggle-Hackathon) | Generic Gemma 4 Good Hackathon repo. | Python | 0 |
| Other / unclear | [Code-for-Sydney/penpal](https://github.com/Code-for-Sydney/penpal) | Note-taking Android app. | Kotlin | 0 |
| Other / unclear | [cybort360/groundtruth](https://github.com/cybort360/groundtruth) | Offline-first situational awareness engine. | TypeScript | 0 |
| Other / unclear | [DenizErenArici/llm-gemma4good](https://github.com/DenizErenArici/llm-gemma4good) | Generic Gemma 4 Good repo. | Python | 1 |
| Other / unclear | [drthgz/GeneScribe](https://github.com/drthgz/GeneScribe) | Sparse description; likely niche/topic exploration with Gemma 4. | Python | 0 |
| Other / unclear | [Fusion831/Gemma4Hackathon](https://github.com/Fusion831/Gemma4Hackathon) | Source repo for track 2 submission. | - | 0 |
| Other / unclear | [hakanberkiten/food-safe](https://github.com/hakanberkiten/food-safe) | Food-safe project shell. | Dart | 0 |
| Other / unclear | [Isat-3am/agente-jubilado](https://github.com/Isat-3am/agente-jubilado) | Spanish voice assistant for older adults. | - | 0 |
| Other / unclear | [jrippert-hub/kaggle_gemma4_submission](https://github.com/jrippert-hub/kaggle_gemma4_submission) | Generic competition repo. | Python | 0 |
| Other / unclear | [Krl05oP11/ChatVida](https://github.com/Krl05oP11/ChatVida) | Local crisis support and suicide-prevention chatbot for Latin America. | Python | 0 |
| Other / unclear | [NALIBAK/management-system_gemma4](https://github.com/NALIBAK/management-system_gemma4) | Generic management-system submission. | Python | 0 |
| Other / unclear | [Njeru58/Gemma4Hackathon](https://github.com/Njeru58/Gemma4Hackathon) | Generic repository submit. | JavaScript | 0 |
| Other / unclear | [osama907/gemma-4-good-hackathon](https://github.com/osama907/gemma-4-good-hackathon) | Generic competition repo. | Python | 0 |
| Other / unclear | [rb125/gatepass_gemma_4](https://github.com/rb125/gatepass_gemma_4) | GatePass project. | Python | 0 |
| Other / unclear | [sanjidaakter877/yourOwn_Gemma4_Good_Hackathon-](https://github.com/sanjidaakter877/yourOwn_Gemma4_Good_Hackathon-) | Generic Gemma 4 Good project. | JavaScript | 1 |
| Other / unclear | [shreesha345/Emergency-management-System](https://github.com/shreesha345/Emergency-management-System) | Emergency management and 112 dispatch system with audio, maps, alerts. | TypeScript | 0 |
| Other / unclear | [somayaowazwaz-dotcom/G4WDB](https://github.com/somayaowazwaz-dotcom/G4WDB) | War survival tool. | - | 0 |
| Other / unclear | [Two-Weeks-Team/he-was-socrates](https://github.com/Two-Weeks-Team/he-was-socrates) | macOS Socratic bust with on-device Gemma and lip sync. | HTML | 0 |
| Other / unclear | [wanderduck/northstar_navigator](https://github.com/wanderduck/northstar_navigator) | Generic competition repo. | Python | 0 |
| Other / unclear | [wordingone/gemma-architect](https://github.com/wordingone/gemma-architect) | Browser-native parametric architecture from natural-language prompts. | TypeScript | 0 |
| Other / unclear | [zainsubhani/Hackathon_Gemma4_BlackoutCare](https://github.com/zainsubhani/Hackathon_Gemma4_BlackoutCare) | BlackoutCare competition repo with sparse public description. | TypeScript | 0 |
| Safety / trust | [abkmystery/Genie](https://github.com/abkmystery/Genie) | Privacy-first desktop AI companion for understanding websites, forms, portals, and local files. | Python | 0 |
| Safety / trust | [achbj/SilentWitness](https://github.com/achbj/SilentWitness) | On-device AI that documents abuse without internet while preserving privacy. | Python | 0 |
| Safety / trust | [AndrewGossenPerez/Axiom](https://github.com/AndrewGossenPerez/Axiom) | Multi-agent AI auditor that verifies information. | Python | 0 |
| Safety / trust | [credwine/beacon](https://github.com/credwine/beacon) | Privacy-first scam protection for vulnerable communities. | Python | 0 |
| Safety / trust | [deebaby001/KidAssure](https://github.com/deebaby001/KidAssure) | On-device AI companion for child safety. | Jupyter Notebook | 0 |
| Safety / trust | [Delphineuzoeto/nairawise](https://github.com/Delphineuzoeto/nairawise) | Local-first scam detector for Nigerians. | Python | 0 |
| Safety / trust | [govindrathore27/gemma4-engineering-diagrams](https://github.com/govindrathore27/gemma4-engineering-diagrams) | P&ID safety copilot. | Python | 0 |
| Safety / trust | [humanaiconvention/gemma4good](https://github.com/humanaiconvention/gemma4good) | Governance agent with function-calling tools, cryptographic alignment receipts, and operationalized viability condition. | Python | 0 |
| Safety / trust | [jiahknee5/maestro-gemma](https://github.com/jiahknee5/maestro-gemma) | AI violin coach for kids with E2B on-device plus larger local routing. | C++ | 1 |
| Safety / trust | [Noptus/gemma-4-good-hackathon](https://github.com/Noptus/gemma-4-good-hackathon) | SafeGuard AI local AI safety auditor. | Python | 0 |
| Safety / trust | [SocAbdul/repairwise-gemma](https://github.com/SocAbdul/repairwise-gemma) | RepairWise, positioned for Digital Equity, Safety & Trust, and Unsloth special prize. | Jupyter Notebook | 0 |
| Safety / trust | [sumagiribl/Privy-Kaggle](https://github.com/sumagiribl/Privy-Kaggle) | Privacy-preserving AI pipeline demo. | Python | 0 |
| Safety / trust | [TaylorAmarelTech/gemma4_comp](https://github.com/TaylorAmarelTech/gemma4_comp) | DueCare, agentic LLM safety harness for migrant-worker protection. | Python | 0 |

## Immediate implications for GemmaFit

1. Do not compete as a generic medical assistant. That field is crowded and claim-risky.
2. Do not compete as a generic tutor. Education has the most clones and shallow variants.
3. Make the demo visibly real-device and evidence-based. Many repos say "offline" or "on-device"; few public descriptions prove Pixel-grade deployment and deterministic gates.
4. Lean into senior-care motion evidence. SilverAid and dementia/caregiver assistants are close in audience, but they do not appear to own movement-quality evidence from camera pose.
5. Keep FormForward in the competitor slide. It is the clearest form-analysis overlap; GemmaFit's answer should be temporal evidence, senior safety boundary, and Android offline execution.
6. Make the safety boundary a feature. The market is full of projects that risk overclaiming medical triage or prediction. GemmaFit can look more mature by refusing unsupported claims and showing the audit trail.
