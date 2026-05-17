# ModelInvocationScheduler

This note defines the runtime gate that decides whether GemmaFit should call
the local model. It is intentionally separate from the E2B function-call
router.

## Decision

GemmaFit uses a `ModelInvocationScheduler` before every local model call.

The scheduler owns:

- whether to call E2B now, skip the call, defer it, or use deterministic
  fallback only;
- which backend class is eligible (`litert_e2b`, `llama_cpp_fallback`,
  deterministic only);
- which context budget is allowed (`event_compact`, `session_compact`,
  `caregiver_export`, or `debug`);
- whether summary-only reasoning is allowed (`off` or `summary_optional`);
- whether privacy, confidence, latency, or missing evidence blocks the call.

The scheduler does not own:

- final function name selection;
- function args;
- evidence-ref selection;
- memory writes;
- refusal wording;
- safety verdicts.
- deterministic `judgeable` / `monitor_only` / `abstain` states.

Those stay with the E2B router and the Android validator:

```text
Deterministic evidence event
-> ModelInvocationScheduler decides call / skip / defer
-> Context compiler builds the existing compact E2B packet
-> E2B chooses one legal function + args + evidence_refs
-> Android validator accepts or uses deterministic fallback
```

## Summary Reasoning Policy

Gemma 4 E2B thinking mode is only useful after deterministic compression has
already happened. GemmaFit therefore treats reasoning as a summary-writing
policy, not as a movement judgment policy.

Allowed:

- `SESSION_COMPACT` session summary wording.
- `CAREGIVER_EXPORT` caregiver-facing export wording.
- future `PROFESSIONAL_SHARE` summary wording.

Always off:

- live frames;
- rep-completed event routing;
- persistent warning events;
- unsupported medical / force / cognitive questions;
- low battery, high thermal load, or model-disabled states;
- any step that decides `judgeable`, `monitor_only`, or `abstain`.

Even when `summary_optional` is enabled, the model may only organize wording.
It may not change evidence refs, capability gates, MotionZip block states, or
unsupported-claim boundaries. Android validation rejects any thought leakage,
non-tool output, missing evidence refs, or forbidden claims.

## Non-overlap With E2B Training

The current v5.1 E2B training remains valid as long as this scheduler preserves
the existing E2B packet and tool contract.

| Layer | Owner | Decision |
| --- | --- | --- |
| Motion / rule tools | C++ / Kotlin | Compute pose evidence, confidence, status, and `can_judge` / `cannot_judge`. |
| ModelInvocationScheduler | Kotlin runtime | Decide whether a model call is worth making and which compact context budget to use. |
| E2B FC router | Gemma 4 E2B | Choose the legal function, args, evidence refs, and refusal/report style. |
| Android validator | Kotlin policy | Reject missing refs, forbidden claims, `cannot_judge` violations, and unsafe memory writes. |

Retraining is not required for scheduler-only changes when:

- tool names stay unchanged;
- required arg schemas stay unchanged;
- `evidence_refs` format stays unchanged;
- `can_judge` and `cannot_judge` semantics stay unchanged;
- event packets remain compatible with the current v5.1 hard-case dataset.

Retraining or at least eval regeneration is required when:

- a new tool is added or renamed;
- model-visible packet keys become required;
- evidence-ref format changes;
- scheduler starts forcing a function instead of letting E2B choose it;
- new refusal categories or memory-write types are introduced.

## Scheduler Decisions

```kotlin
enum class ModelInvocationDecision {
    SKIP_DETERMINISTIC,
    CALL_E2B_NOW,
    DEFER_TO_SESSION_END,
    FALLBACK_ONLY,
}
```

Suggested decision fields:

```kotlin
data class ModelInvocationPlan(
    val decision: ModelInvocationDecision,
    val trigger: String,
    val backendPreference: BackendPreference,
    val contextBudget: ContextBudget,
    val reason: String,
    val blockedBy: List<String> = emptyList(),
)
```

Debug builds may log this plan. It should not become a required model-visible
field unless the dataset generator and eval suite are updated.

## Call Matrix

| Event | Scheduler default | Model role |
| --- | --- | --- |
| Normal live frame | `SKIP_DETERMINISTIC` | None. Live cue comes from deterministic FrameHint. |
| `SETUP_CHECK` | `SKIP_DETERMINISTIC` | None in v1. Setup support/person prompts stay deterministic. |
| `no_person`, `lost`, `hold`, `predicted` | `SKIP_DETERMINISTIC` | None. UI asks for repositioning or waits. |
| Rep completed with no issue | `DEFER_TO_SESSION_END` | None now; compact metrics enter session summary. |
| High-confidence warning/critical event | `CALL_E2B_NOW` only if language explanation is needed | E2B may explain within existing evidence. |
| Session ended | `CALL_E2B_NOW` | E2B chooses care log, persona report, trend summary, or refusal. |
| Caregiver export requested | `CALL_E2B_NOW` | E2B drafts bounded report; validator enforces disclaimer and refs. |
| Medical / fall-risk / sarcopenia question | deterministic refusal or `CALL_E2B_NOW` for refusal wording | E2B must use refusal tool if called. |
| Low battery / high latency / model missing | `FALLBACK_ONLY` | None. Deterministic renderer labels source. |

Reasoning mode:

| Event | Reasoning |
| --- | --- |
| Live frame / rep event / warning event | `off` |
| Session ended | `summary_optional` |
| Caregiver export | `summary_optional` |
| Unsupported medical / force / cognitive question | `off` |
| Device budget limited | `off` |

## Multimodal Sidecar Boundary

The scheduler exposes a `multimodal_evidence_plan`, but v1 keeps the allowed
Gemma/multimodal backend triggers intentionally narrow:

| Trigger | Panel | Backend |
| --- | --- | --- |
| `LIVE_FRAME` | no | no |
| `SETUP_CHECK` | no | no |
| `REP_COMPLETED` | debug panel only | no default backend call |
| `WARNING_PERSISTED` | optional | optional, after deterministic warning exists |
| `USER_QUESTION` | yes, when needed | yes, if backend is available |
| `SESSION_ENDED` | yes | yes, if backend is available |
| `CAREGIVER_EXPORT` | yes | yes, if backend is available |

`SETUP_CHECK` was previously documented as an async scene/context sidecar. That
would weaken the proof story for the demo, so the 2026-05-17 architecture pass
tightened it to deterministic setup UI only. Scene context can be revisited
after v1, but it should require a new validation report and explicit product
reason.

## Context Compiler

The scheduler should feed a small context compiler, not raw session logs.

Context compiler inputs:

- current `PersonTrackingState`;
- current `CapabilityContract`;
- current `EvidenceLedger` node ids;
- compact activity and motion context;
- optional memory slices already allowed by `read_memory`.

Context compiler output:

- the existing compact E2B event packet shape used by v5.1 training;
- no raw video, raw crops, histograms, ReID embeddings, or raw evidence rows;
- no forced function name.

## Implementation Plan

P0:

- Add a pure Kotlin `ModelInvocationScheduler` with unit tests.
- Keep current E2B packet schema stable.
- Add debug report fields: invocation decision, reason, context budget,
  backend preference, and fallback reason.
- Add replay tests that prove blocked tracking states do not call E2B.

P1:

- Add latency-aware throttling and per-backend cooldown.
- Add context-budget accounting for `event_compact`, `session_compact`, and
  `caregiver_export`.
- Record model call count, fallback count, and validation reject count in the
  Pixel smoke report.

P2:

- Evaluate a small fast router only if E2B latency or JSON stability fails.
- Keep the same validator and tool contract even if the backend changes.

## Acceptance Tests

- `no_person` / `lost` / `hold` / `predicted` -> no E2B call.
- Normal live frames -> no E2B call.
- `SETUP_CHECK` -> no E2B or multimodal backend call in v1.
- Session end -> one compact E2B packet.
- Caregiver export -> one compact E2B packet with disclaimer requirement.
- Medical question -> deterministic refusal or E2B refusal tool, never hard
  coaching.
- Model missing -> deterministic fallback with source label.
- Scheduler never sets final function name.
- `SESSION_ENDED` and `CAREGIVER_EXPORT` may set `reasoning_mode=summary_optional`.
- Event-level calls keep `reasoning_mode=off`.
- Any thought leakage from the model is rejected before report rendering.
