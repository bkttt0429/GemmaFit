# Evidence-Bounded Memory - Kotlin layer

This package implements the policy engine described in
[implementation_plan.md section 11.6](../../../../../../../implementation_plan.md).

## Files

| File | Role | Status |
| --- | --- | --- |
| `Schemas.kt` | Kotlin data classes + enums for all memory shapes | implemented |
| `MemoryStore.kt` | Persistence interface | implemented |
| `MemoryStoreAndroid.kt` | Room + DataStore Proto implementation | implemented |
| `MemoryWritePolicy.kt` | 6-step validation; pure decision function | implemented |
| `RefusalValidator.kt` | Deny-substring validator for unsupported claims | implemented |
| `AdaptiveRecalibration.kt` | Evidence-driven baseline update logic | implemented |
| `MemoryAwarePromptBuilder.kt` | `read_memory(scope)` tool round-trip + cache | implemented |
| `../export/CaregiverExportBuilder.kt` | Canonical JSON + human-readable text/HTML | implemented |
| `../../proto/memory.proto` | DataStore Proto schema | implemented |

## Android wiring

- Room + KSP are wired in `app/build.gradle.kts`.
- DataStore Proto + protobuf lite generation are wired for `memory.proto`.
- `MemoryStoreAndroid` stores:
  - hot config in `files/memory/profile.pb`
  - calibration in `files/memory/calibration/<exercise>.pb`
  - session/evidence logs in `files/memory/sessions.db`
  - write/reject decisions in `files/memory/audit.log`
- `FunctionRegistry` includes `read_memory`, `request_memory_update`,
  `summarize_trend`, and `refuse_unsupported_question`.
- Senior Mode Compose scaffolding lives in
  `app/src/main/kotlin/com/gemmafit/ui/screens/senior/`.

## Trust invariants

These should never be violated, in order of severity:

1. Gemma never writes directly. All state mutations route through
   `MemoryWritePolicy.evaluate(...)`.
2. Raw video is never persisted by default. `EvidenceMemoryEntry` stores
   metric values, not pixels.
3. `TREND_NOTE` requires evidence. Empty `evidenceIds` is an automatic reject.
4. Refusal matching is intentionally conservative. False positives should
   block or require review, not silently accept.
5. Caregiver export always carries the disclaimer block.
6. Idempotency is enforced. `MemoryUpdateRequest.requestId` collisions become
   no-ops.

## JVM test coverage

The following test groups cover the spec from section 11.6:

- `MemoryWritePolicyTest`
- `RefusalValidatorTest`
- `AdaptiveRecalibrationTest`
- `MemoryAwarePromptBuilderTest`
- `CaregiverExportBuilderTest`

`FunctionRegistryTest` separately verifies the memory FC tools are registered.
