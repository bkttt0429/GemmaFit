package com.gemmafit.video

internal enum class SessionCoachDedupDecision {
    FIRE,
    SKIP,
}

internal data class SessionCoachDedupState(
    val lastDoneKey: String? = null,
    val failureCount: Int = 0,
)

internal class SessionCoachInferenceDedupGuard {
    private var state = SessionCoachDedupState()

    fun shouldFire(key: String): Boolean {
        return decide(key, state) == SessionCoachDedupDecision.FIRE
    }

    fun recordSuccess(key: String) {
        state = SessionCoachDedupState(lastDoneKey = key, failureCount = 0)
    }

    fun recordFailure(key: String) {
        val nextFailureCount = state.failureCount + 1
        state = if (nextFailureCount >= MAX_FAILURES_BEFORE_DONE) {
            SessionCoachDedupState(lastDoneKey = key, failureCount = nextFailureCount)
        } else {
            SessionCoachDedupState(lastDoneKey = null, failureCount = nextFailureCount)
        }
    }

    fun reset() {
        state = SessionCoachDedupState()
    }

    companion object {
        private const val MAX_FAILURES_BEFORE_DONE = 2

        fun decide(
            key: String,
            state: SessionCoachDedupState,
        ): SessionCoachDedupDecision {
            return if (state.lastDoneKey == key) {
                SessionCoachDedupDecision.SKIP
            } else {
                SessionCoachDedupDecision.FIRE
            }
        }
    }
}
