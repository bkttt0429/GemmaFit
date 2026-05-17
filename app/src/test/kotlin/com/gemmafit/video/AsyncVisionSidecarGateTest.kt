package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncVisionSidecarGateTest {
    @Test
    fun liveFrameIsRejectedEvenWhenPanelPlanIsAvailable() {
        val gate = AsyncVisionSidecarGate(cooldownMs = 1_000L, resultTtlMs = 5_000L)

        val decision = gate.tryStart(
            eventKey = "live_frame",
            trigger = ModelInvocationTrigger.LIVE_FRAME,
            plan = MultimodalEvidencePlan.callBackend("test"),
            nowMs = 1_000L,
        )

        assertFalse(decision.accepted)
        assertEquals("live_frame_never_uses_async_vision", decision.reason)
    }

    @Test
    fun setupCheckIsNotAnAsyncVisionBackendTrigger() {
        val gate = AsyncVisionSidecarGate(cooldownMs = 1_000L, resultTtlMs = 5_000L)

        val decision = gate.tryStart(
            eventKey = "setup",
            trigger = ModelInvocationTrigger.SETUP_CHECK,
            plan = MultimodalEvidencePlan.callBackend("test"),
            nowMs = 1_000L,
        )

        assertFalse(decision.accepted)
        assertEquals("async_vision_only_warning_persisted", decision.reason)
    }

    @Test
    fun warningPersistedCanStartOneAsyncBackendJob() {
        val gate = AsyncVisionSidecarGate(cooldownMs = 1_000L, resultTtlMs = 5_000L)

        val decision = gate.tryStart(
            eventKey = "warning:1",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 1_000L,
        )

        assertTrue(decision.accepted)
        assertTrue(decision.buildPanel)
        assertTrue(decision.callBackend)
        assertEquals(6_000L, decision.expiresAtMs)
    }

    @Test
    fun secondJobIsSkippedWhileOneIsInFlight() {
        val gate = AsyncVisionSidecarGate(cooldownMs = 1_000L, resultTtlMs = 5_000L)
        gate.tryStart(
            eventKey = "warning:1",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 1_000L,
        )

        val second = gate.tryStart(
            eventKey = "warning:2",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 1_200L,
        )

        assertFalse(second.accepted)
        assertEquals("vision_sidecar_in_flight", second.reason)
    }

    @Test
    fun completedDuplicateEventIsSkipped() {
        val gate = AsyncVisionSidecarGate(cooldownMs = 0L, resultTtlMs = 5_000L)
        gate.tryStart(
            eventKey = "warning:1",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 1_000L,
        )
        gate.complete("warning:1")

        val duplicate = gate.tryStart(
            eventKey = "warning:1",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 2_000L,
        )

        assertFalse(duplicate.accepted)
        assertEquals("duplicate_vision_event_skipped", duplicate.reason)
    }

    @Test
    fun cooldownSkipsNewEventsAfterCompletion() {
        val gate = AsyncVisionSidecarGate(cooldownMs = 2_000L, resultTtlMs = 5_000L)
        gate.tryStart(
            eventKey = "warning:1",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 1_000L,
        )
        gate.complete("warning:1")

        val second = gate.tryStart(
            eventKey = "warning:2",
            trigger = ModelInvocationTrigger.WARNING_PERSISTED,
            plan = MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar"),
            nowMs = 2_000L,
        )

        assertFalse(second.accepted)
        assertEquals("vision_sidecar_cooldown", second.reason)
    }
}
