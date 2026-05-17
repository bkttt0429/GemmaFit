package com.gemmafit.video

internal enum class SubjectRelocalizationMode {
    TRACKING,
    UNCERTAIN,
    RELOCALIZING,
    LOST,
}

internal data class SubjectRelocalizationDecision(
    val mode: SubjectRelocalizationMode,
    val shouldRequestBurst: Boolean,
    val reason: String,
    val trustFlags: List<String>,
)

/**
 * Mobile-side governor for expensive person detector / ReID fallback.
 *
 * This class does not run YOLO. It only decides when a low-frequency detector
 * burst would be allowed, so the fast MediaPipe path does not thrash between
 * pipelines during occlusion or multi-person overlap.
 */
internal class SubjectRelocalizationPolicy(
    private val uncertainFramesToTrigger: Int = 3,
    private val stableFramesToRecover: Int = 3,
    private val minBurstIntervalMs: Long = 750L,
    private val recoveryCooldownMs: Long = 2_000L,
    private val maxRelocalizingMs: Long = 2_500L,
) {
    private var mode: SubjectRelocalizationMode = SubjectRelocalizationMode.TRACKING
    private var uncertainFrames = 0
    private var stableFrames = 0
    private var lastBurstRequestMs = Long.MIN_VALUE / 4
    private var relocalizingSinceMs: Long? = null
    private var cooldownUntilMs = 0L

    fun reset() {
        mode = SubjectRelocalizationMode.TRACKING
        uncertainFrames = 0
        stableFrames = 0
        lastBurstRequestMs = Long.MIN_VALUE / 4
        relocalizingSinceMs = null
        cooldownUntilMs = 0L
    }

    fun update(
        status: SubjectLockStatus,
        hasActiveSubject: Boolean,
        candidateCount: Int,
        reason: String,
        timestampMs: Long,
    ): SubjectRelocalizationDecision {
        val stable = hasActiveSubject && status != SubjectLockStatus.SUBJECT_LOST
        if (stable) {
            return onStableFrame(timestampMs)
        }

        stableFrames = 0
        val signal = uncertaintySignal(status, candidateCount, reason)
        if (signal.isBlank()) {
            mode = SubjectRelocalizationMode.TRACKING
            uncertainFrames = 0
            relocalizingSinceMs = null
            return decision("subject_tracking_idle")
        }

        uncertainFrames += 1
        val inCooldown = timestampMs < cooldownUntilMs
        if (inCooldown) {
            mode = SubjectRelocalizationMode.UNCERTAIN
            return decision(
                reason = signal,
                extraFlags = listOf("relocalization_cooldown"),
            )
        }

        val relocalizingForMs = relocalizingSinceMs?.let { timestampMs - it } ?: 0L
        if (mode == SubjectRelocalizationMode.RELOCALIZING && relocalizingForMs > maxRelocalizingMs) {
            mode = SubjectRelocalizationMode.LOST
            return decision(
                reason = signal,
                extraFlags = listOf("relocalization_failed"),
            )
        }

        val shouldStartOrContinue = uncertainFrames >= uncertainFramesToTrigger ||
            status == SubjectLockStatus.SUBJECT_LOST
        val canRequestBurst = shouldStartOrContinue &&
            timestampMs - lastBurstRequestMs >= minBurstIntervalMs

        if (canRequestBurst) {
            if (mode != SubjectRelocalizationMode.RELOCALIZING) {
                relocalizingSinceMs = timestampMs
            }
            mode = SubjectRelocalizationMode.RELOCALIZING
            lastBurstRequestMs = timestampMs
            return decision(
                reason = signal,
                requestBurst = true,
                extraFlags = listOf("relocalization_requested"),
            )
        }

        mode = if (mode == SubjectRelocalizationMode.RELOCALIZING) {
            SubjectRelocalizationMode.RELOCALIZING
        } else {
            SubjectRelocalizationMode.UNCERTAIN
        }
        return decision(signal)
    }

    private fun onStableFrame(timestampMs: Long): SubjectRelocalizationDecision {
        uncertainFrames = 0
        stableFrames += 1
        if (mode == SubjectRelocalizationMode.RELOCALIZING && stableFrames >= stableFramesToRecover) {
            mode = SubjectRelocalizationMode.TRACKING
            relocalizingSinceMs = null
            cooldownUntilMs = timestampMs + recoveryCooldownMs
            return decision(
                reason = "subject_relocalized",
                extraFlags = listOf("relocalization_recovered", "relocalization_cooldown"),
            )
        }
        if (mode == SubjectRelocalizationMode.RELOCALIZING) {
            return decision("subject_relocalizing_stabilizing")
        }
        if (timestampMs < cooldownUntilMs) {
            mode = SubjectRelocalizationMode.TRACKING
            return decision(
                reason = "subject_tracking_stable",
                extraFlags = listOf("relocalization_cooldown"),
            )
        }
        mode = SubjectRelocalizationMode.TRACKING
        relocalizingSinceMs = null
        return decision("subject_tracking_stable")
    }

    private fun uncertaintySignal(
        status: SubjectLockStatus,
        candidateCount: Int,
        reason: String,
    ): String {
        if (status == SubjectLockStatus.SUBJECT_LOST) return "subject_lost"
        if (reason.contains("occluded", ignoreCase = true)) return "subject_occluded"
        if (reason.contains("reacquiring", ignoreCase = true)) return "subject_reacquiring"
        if (reason.contains("identity", ignoreCase = true)) return "subject_identity_uncertain"
        if (reason.contains("unmatched", ignoreCase = true)) return "subject_unmatched"
        if (candidateCount > 1 && status == SubjectLockStatus.NEEDS_SELECTION) return "multi_person_ambiguous"
        if (candidateCount == 0) return "no_pose_candidate"
        return ""
    }

    private fun decision(
        reason: String,
        requestBurst: Boolean = false,
        extraFlags: List<String> = emptyList(),
    ): SubjectRelocalizationDecision {
        val flags = buildList {
            add("relocalization_${mode.name.lowercase()}")
            addAll(extraFlags)
        }.distinct()
        return SubjectRelocalizationDecision(
            mode = mode,
            shouldRequestBurst = requestBurst,
            reason = reason,
            trustFlags = flags,
        )
    }
}
