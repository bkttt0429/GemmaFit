package com.gemmafit.video

/**
 * Runtime guard for asynchronous vision sidecars.
 *
 * This gate keeps visual analysis out of the live safety loop: callers must
 * already have rendered the deterministic cue before trying to start a job.
 */
class AsyncVisionSidecarGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val resultTtlMs: Long = DEFAULT_RESULT_TTL_MS,
    private val maxRememberedKeys: Int = DEFAULT_MAX_REMEMBERED_KEYS,
) {
    private var activeEventKey: String? = null
    private var lastStartedAtMs: Long? = null
    private val rememberedOrder = ArrayDeque<String>()
    private val rememberedKeys = linkedSetOf<String>()

    fun tryStart(
        eventKey: String,
        trigger: ModelInvocationTrigger,
        plan: MultimodalEvidencePlan,
        nowMs: Long,
    ): AsyncVisionSidecarDecision {
        if (!plan.buildPanel) {
            return AsyncVisionSidecarDecision.skipped(plan.reason)
        }
        if (trigger == ModelInvocationTrigger.LIVE_FRAME) {
            return AsyncVisionSidecarDecision.skipped("live_frame_never_uses_async_vision")
        }
        if (trigger != ModelInvocationTrigger.WARNING_PERSISTED) {
            return AsyncVisionSidecarDecision.skipped("async_vision_only_warning_persisted")
        }
        activeEventKey?.let {
            return AsyncVisionSidecarDecision.skipped("vision_sidecar_in_flight")
        }
        if (eventKey in rememberedKeys) {
            return AsyncVisionSidecarDecision.skipped("duplicate_vision_event_skipped")
        }
        val lastStarted = lastStartedAtMs
        if (lastStarted != null && nowMs - lastStarted in 0 until cooldownMs) {
            return AsyncVisionSidecarDecision.skipped("vision_sidecar_cooldown")
        }

        activeEventKey = eventKey
        lastStartedAtMs = nowMs
        remember(eventKey)
        return AsyncVisionSidecarDecision(
            accepted = true,
            reason = plan.reason,
            buildPanel = plan.buildPanel,
            callBackend = plan.callBackend,
            expiresAtMs = nowMs + resultTtlMs,
        )
    }

    fun complete(eventKey: String) {
        if (activeEventKey == eventKey) {
            activeEventKey = null
        }
    }

    fun reset() {
        activeEventKey = null
        lastStartedAtMs = null
        rememberedOrder.clear()
        rememberedKeys.clear()
    }

    private fun remember(eventKey: String) {
        if (rememberedKeys.add(eventKey)) {
            rememberedOrder.addLast(eventKey)
        }
        while (rememberedOrder.size > maxRememberedKeys) {
            val removed = rememberedOrder.removeFirst()
            rememberedKeys.remove(removed)
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 15_000L
        const val DEFAULT_RESULT_TTL_MS = 15_000L
        private const val DEFAULT_MAX_REMEMBERED_KEYS = 40
    }
}

data class AsyncVisionSidecarDecision(
    val accepted: Boolean,
    val reason: String,
    val buildPanel: Boolean,
    val callBackend: Boolean,
    val expiresAtMs: Long,
) {
    fun toDebugMap(): Map<String, Any> = mapOf(
        "accepted" to accepted,
        "reason" to reason,
        "build_panel" to buildPanel,
        "call_backend" to callBackend,
        "expires_at_ms" to expiresAtMs,
    )

    companion object {
        fun skipped(reason: String): AsyncVisionSidecarDecision =
            AsyncVisionSidecarDecision(
                accepted = false,
                reason = reason,
                buildPanel = false,
                callBackend = false,
                expiresAtMs = 0L,
            )
    }
}
