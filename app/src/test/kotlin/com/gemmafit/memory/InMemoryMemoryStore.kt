package com.gemmafit.memory

class InMemoryMemoryStore : MemoryStore {
    var profile: UserProfileMemory = UserProfileMemory(
        language = "en",
        voiceSpeed = 1.0f,
        fontScale = 1.0f,
        assistedMode = false,
        cuePreference = CueStyle.ENCOURAGING,
    )
    val calibrations = linkedMapOf<String, CalibrationMemory>()
    val sessions = linkedMapOf<String, SessionSummary>()
    val evidence = linkedMapOf<String, EvidenceMemoryEntry>()
    val auditEntries = mutableListOf<AuditEntry>()

    var loadProfileCalls = 0
    var loadCalibrationCalls = 0
    var aggregateTrendCalls = 0

    override suspend fun loadProfile(): UserProfileMemory {
        loadProfileCalls++
        return profile
    }

    override suspend fun saveProfile(profile: UserProfileMemory) {
        this.profile = profile
    }

    override suspend fun loadCalibration(exercise: String): CalibrationMemory? {
        loadCalibrationCalls++
        return calibrations[exercise]
    }

    override suspend fun saveCalibration(calibration: CalibrationMemory) {
        calibrations[calibration.exercise] = calibration
    }

    override suspend fun appendSession(summary: SessionSummary) {
        sessions[summary.sessionId] = summary
    }

    override suspend fun appendEvidence(entries: List<EvidenceMemoryEntry>) {
        entries.forEach { evidence[it.evidenceCardId] = it }
    }

    override suspend fun recentSessions(
        exercise: String,
        limit: Int,
        sinceEpochMs: Long?,
    ): List<SessionSummary> {
        return sessions.values
            .filter { it.exercise == exercise }
            .sortedByDescending { it.date }
            .take(limit)
    }

    override suspend fun aggregateTrend(exercise: String, windowDays: Int): TrendAggregate {
        aggregateTrendCalls++
        val matching = sessions.values.filter { it.exercise == exercise }
        return TrendAggregate(
            exercise = exercise,
            windowDays = windowDays,
            sessions = matching.size,
            totalReps = matching.sumOf { it.reps },
            warningsCount = matching.sumOf { it.warningsCount },
            lowConfidenceCount = matching.sumOf { it.lowConfidenceCount },
            notApplicableCount = matching.sumOf { it.notApplicableCount },
            commonTrendNotes = matching.flatMap { it.trendNotes }.distinct().take(5),
        )
    }

    override suspend fun evidenceForSession(sessionId: String): List<EvidenceMemoryEntry> {
        return evidence.values.filter { it.sessionId == sessionId }
    }

    override suspend fun audit(entry: AuditEntry) {
        auditEntries.add(entry)
    }

    override suspend fun truncateAuditOlderThan(retentionDays: Int) = Unit

    override suspend fun clearAllMemory() {
        calibrations.clear()
        sessions.clear()
        evidence.clear()
        auditEntries.clear()
    }
}
