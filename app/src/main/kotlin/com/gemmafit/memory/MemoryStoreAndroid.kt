package com.gemmafit.memory

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gemmafit.memory.proto.CalibrationMemoryProto
import com.gemmafit.memory.proto.CueStyleProto
import com.gemmafit.memory.proto.SupportTypeProto
import com.gemmafit.memory.proto.UserProfileMemoryProto
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Android persistence implementation for [MemoryStore].
 *
 * Hot config is stored in DataStore Proto, append-only session/evidence
 * records are stored in Room, and audit decisions are appended as JSONL.
 */
class MemoryStoreAndroid(
    context: Context,
) : MemoryStore {
    private val appContext = context.applicationContext
    private val memoryDir = File(appContext.filesDir, "memory").also { it.mkdirs() }
    private val calibrationDir = File(memoryDir, "calibration").also { it.mkdirs() }
    private val auditFile = File(memoryDir, "audit.log")

    private val profileStore: DataStore<UserProfileMemoryProto> = DataStoreFactory.create(
        serializer = UserProfileMemorySerializer,
        produceFile = { File(memoryDir, "profile.pb") },
    )

    private val calibrationStores = ConcurrentHashMap<String, DataStore<CalibrationMemoryProto>>()

    private val database: MemoryRoomDatabase = Room.databaseBuilder(
        appContext,
        MemoryRoomDatabase::class.java,
        File(memoryDir, "sessions.db").absolutePath,
    )
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    private val dao = database.memoryDao()

    override suspend fun loadProfile(): UserProfileMemory {
        return profileStore.data.first().toDomain()
    }

    override suspend fun saveProfile(profile: UserProfileMemory) {
        profileStore.updateData { profile.toProto() }
    }

    override suspend fun loadCalibration(exercise: String): CalibrationMemory? {
        val proto = calibrationStore(exercise).data.first()
        return proto.toDomain().takeIf { it.exercise.isNotBlank() }
    }

    override suspend fun saveCalibration(calibration: CalibrationMemory) {
        calibrationStore(calibration.exercise).updateData { calibration.toProto() }
    }

    override suspend fun appendSession(summary: SessionSummary) {
        dao.upsertSession(summary.toEntity())
    }

    override suspend fun appendEvidence(entries: List<EvidenceMemoryEntry>) {
        if (entries.isEmpty()) return
        dao.upsertEvidence(entries.map { it.toEntity() })
    }

    override suspend fun recentSessions(
        exercise: String,
        limit: Int,
        sinceEpochMs: Long?,
    ): List<SessionSummary> {
        val cutoff = sinceEpochMs?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        return dao.sessionsForExercise(exercise)
            .asSequence()
            .map { it.toDomain() }
            .filter { session ->
                cutoff == null || runCatching { LocalDate.parse(session.date) >= cutoff }.getOrDefault(true)
            }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override suspend fun aggregateTrend(exercise: String, windowDays: Int): TrendAggregate {
        val cutoff = LocalDate.now().minusDays(windowDays.toLong())
        val sessions = dao.sessionsForExercise(exercise)
            .map { it.toDomain() }
            .filter { session ->
                runCatching { LocalDate.parse(session.date) >= cutoff }.getOrDefault(true)
            }
        val noteCounts = linkedMapOf<TrendNote, Int>()
        sessions.forEach { session ->
            session.trendNotes.forEach { noteCounts[it] = (noteCounts[it] ?: 0) + 1 }
        }
        return TrendAggregate(
            exercise = exercise,
            windowDays = windowDays,
            sessions = sessions.size,
            totalReps = sessions.sumOf { it.reps },
            warningsCount = sessions.sumOf { it.warningsCount },
            lowConfidenceCount = sessions.sumOf { it.lowConfidenceCount },
            notApplicableCount = sessions.sumOf { it.notApplicableCount },
            commonTrendNotes = noteCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key },
        )
    }

    override suspend fun evidenceForSession(sessionId: String): List<EvidenceMemoryEntry> {
        return dao.evidenceForSession(sessionId).map { it.toDomain() }
    }

    override suspend fun audit(entry: AuditEntry) {
        withContext(Dispatchers.IO) {
            runCatching {
                auditFile.parentFile?.mkdirs()
                auditFile.appendText(entry.toJson().toString() + "\n")
            }
        }
    }

    override suspend fun truncateAuditOlderThan(retentionDays: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!auditFile.exists()) return@runCatching
                val cutoffMs = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 86_400_000L
                val retained = auditFile.readLines()
                    .filter { line ->
                        val ts = runCatching { JSONObject(line).optLong("timestamp_ms", Long.MAX_VALUE) }
                            .getOrDefault(Long.MAX_VALUE)
                        ts >= cutoffMs
                    }
                auditFile.writeText(retained.joinToString(separator = "\n", postfix = if (retained.isEmpty()) "" else "\n"))
            }
        }
    }

    override suspend fun clearAllMemory() {
        dao.clearEvidence()
        dao.clearSessions()
        profileStore.updateData { defaultProfileProto() }
        calibrationStores.clear()
        withContext(Dispatchers.IO) {
            runCatching {
                calibrationDir.deleteRecursively()
                calibrationDir.mkdirs()
                auditFile.delete()
            }
        }
    }

    private fun calibrationStore(exercise: String): DataStore<CalibrationMemoryProto> {
        val key = exercise.sanitizedExerciseKey()
        return calibrationStores.getOrPut(key) {
            DataStoreFactory.create(
                serializer = CalibrationMemorySerializer,
                produceFile = { File(calibrationDir, "$key.pb") },
            )
        }
    }
}

private object UserProfileMemorySerializer : Serializer<UserProfileMemoryProto> {
    override val defaultValue: UserProfileMemoryProto = defaultProfileProto()

    override suspend fun readFrom(input: InputStream): UserProfileMemoryProto {
        return try {
            UserProfileMemoryProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read profile memory proto.", e)
        }
    }

    override suspend fun writeTo(t: UserProfileMemoryProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private object CalibrationMemorySerializer : Serializer<CalibrationMemoryProto> {
    override val defaultValue: CalibrationMemoryProto = CalibrationMemoryProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): CalibrationMemoryProto {
        return try {
            CalibrationMemoryProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read calibration memory proto.", e)
        }
    }

    override suspend fun writeTo(t: CalibrationMemoryProto, output: OutputStream) {
        t.writeTo(output)
    }
}

@Database(
    entities = [MemorySessionEntity::class, MemoryEvidenceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MemoryRoomDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: MemorySessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvidence(entities: List<MemoryEvidenceEntity>)

    @Query("SELECT * FROM session_summary WHERE exercise = :exercise ORDER BY date DESC, sessionId DESC")
    suspend fun sessionsForExercise(exercise: String): List<MemorySessionEntity>

    @Query("SELECT * FROM evidence_memory WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun evidenceForSession(sessionId: String): List<MemoryEvidenceEntity>

    @Query("DELETE FROM session_summary")
    suspend fun clearSessions(): Int

    @Query("DELETE FROM evidence_memory")
    suspend fun clearEvidence(): Int
}

@Entity(tableName = "session_summary")
data class MemorySessionEntity(
    @PrimaryKey val sessionId: String,
    val date: String,
    val mode: String,
    val exercise: String,
    val reps: Int,
    val durationSec: Int,
    val warningsCount: Int,
    val lowConfidenceCount: Int,
    val notApplicableCount: Int,
    val trendNotesJson: String,
    val evidenceCardIdsJson: String,
)

@Entity(tableName = "evidence_memory")
data class MemoryEvidenceEntity(
    @PrimaryKey val evidenceCardId: String,
    val sessionId: String,
    val exercise: String,
    val status: String,
    val metricId: String,
    val value: Double,
    val confidence: Double,
    val unsupportedJudgmentsJson: String,
    val createdAt: Long,
)

private fun defaultProfileProto(): UserProfileMemoryProto {
    return UserProfileMemoryProto.newBuilder()
        .setLanguage("en")
        .setVoiceSpeed(1.0f)
        .setFontScale(1.0f)
        .setAssistedMode(false)
        .setCuePreference(CueStyleProto.CUE_STYLE_ENCOURAGING)
        .setSchemaVersion(UserProfileMemory.SCHEMA_V1)
        .build()
}

private fun UserProfileMemory.toProto(): UserProfileMemoryProto {
    return UserProfileMemoryProto.newBuilder()
        .setLanguage(language)
        .setVoiceSpeed(voiceSpeed)
        .setFontScale(fontScale)
        .setAssistedMode(assistedMode)
        .setCuePreference(cuePreference.toProto())
        .setSchemaVersion(schemaVersion)
        .build()
}

private fun UserProfileMemoryProto.toDomain(): UserProfileMemory {
    val defaults = defaultProfileProto()
    return UserProfileMemory(
        language = language.ifBlank { defaults.language },
        voiceSpeed = if (voiceSpeed > 0f) voiceSpeed else defaults.voiceSpeed,
        fontScale = if (fontScale > 0f) fontScale else defaults.fontScale,
        assistedMode = assistedMode,
        cuePreference = cuePreference.toDomain(),
        schemaVersion = if (schemaVersion > 0) schemaVersion else UserProfileMemory.SCHEMA_V1,
    )
}

private fun CalibrationMemory.toProto(): CalibrationMemoryProto {
    val builder = CalibrationMemoryProto.newBuilder()
        .setExercise(exercise)
        .setCameraDistanceBucket(cameraSetupHint.distanceBucket)
        .setCameraAngleBucket(cameraSetupHint.angleBucket)
        .setCameraLightingBucket(cameraSetupHint.lightingBucket)
        .setSupportType(supportType.toProto())
        .setCapturedAtMs(capturedAt)
        .setSessionsSinceCalibration(sessionsSinceCalibration)
        .setCleanRepsCollected(cleanRepsCollected)
        .setLowConfidenceStreak(lowConfidenceStreak)
    baselineRomProxy?.let {
        builder.setHasBaselineRomProxy(true)
        builder.setBaselineRomProxy(it)
    }
    baselineTempoSec?.let {
        builder.setHasBaselineTempoSec(true)
        builder.setBaselineTempoSec(it)
    }
    return builder.build()
}

private fun CalibrationMemoryProto.toDomain(): CalibrationMemory {
    return CalibrationMemory(
        exercise = exercise,
        baselineRomProxy = if (hasBaselineRomProxy) baselineRomProxy else null,
        baselineTempoSec = if (hasBaselineTempoSec) baselineTempoSec else null,
        cameraSetupHint = CameraSetupHint(
            distanceBucket = cameraDistanceBucket.ifBlank { "unknown" },
            angleBucket = cameraAngleBucket.ifBlank { "unknown" },
            lightingBucket = cameraLightingBucket.ifBlank { "unknown" },
        ),
        supportType = supportType.toDomain(),
        capturedAt = capturedAtMs,
        sessionsSinceCalibration = sessionsSinceCalibration,
        cleanRepsCollected = cleanRepsCollected,
        lowConfidenceStreak = lowConfidenceStreak,
    )
}

private fun SessionSummary.toEntity(): MemorySessionEntity {
    return MemorySessionEntity(
        sessionId = sessionId,
        date = date,
        mode = mode.name,
        exercise = exercise,
        reps = reps,
        durationSec = durationSec,
        warningsCount = warningsCount,
        lowConfidenceCount = lowConfidenceCount,
        notApplicableCount = notApplicableCount,
        trendNotesJson = JSONArray(trendNotes.map { it.name }).toString(),
        evidenceCardIdsJson = JSONArray(evidenceCardIds).toString(),
    )
}

private fun MemorySessionEntity.toDomain(): SessionSummary {
    return SessionSummary(
        sessionId = sessionId,
        date = date,
        mode = enumValueOrDefault(mode, AppMode.GENERAL),
        exercise = exercise,
        reps = reps,
        durationSec = durationSec,
        warningsCount = warningsCount,
        lowConfidenceCount = lowConfidenceCount,
        notApplicableCount = notApplicableCount,
        trendNotes = jsonStringList(trendNotesJson).mapNotNull { runCatching { TrendNote.valueOf(it) }.getOrNull() },
        evidenceCardIds = jsonStringList(evidenceCardIdsJson),
    )
}

private fun EvidenceMemoryEntry.toEntity(): MemoryEvidenceEntity {
    return MemoryEvidenceEntity(
        evidenceCardId = evidenceCardId,
        sessionId = sessionId,
        exercise = exercise,
        status = status.name,
        metricId = metricId,
        value = value,
        confidence = confidence,
        unsupportedJudgmentsJson = JSONArray(unsupportedJudgments).toString(),
        createdAt = createdAt,
    )
}

private fun MemoryEvidenceEntity.toDomain(): EvidenceMemoryEntry {
    return EvidenceMemoryEntry(
        evidenceCardId = evidenceCardId,
        sessionId = sessionId,
        exercise = exercise,
        status = enumValueOrDefault(status, QualityStatus.LOW_CONFIDENCE),
        metricId = metricId,
        value = value,
        confidence = confidence,
        unsupportedJudgments = jsonStringList(unsupportedJudgmentsJson),
        createdAt = createdAt,
    )
}

private fun AuditEntry.toJson(): JSONObject {
    return JSONObject()
        .put("request_id", requestId)
        .put("timestamp_ms", timestampMs)
        .put("type", type.name)
        .put("outcome", outcome.name)
        .put("rejection_reason", rejectionReason)
        .put("evidence_ids", JSONArray(evidenceIds))
        .put("proposed_value_preview", proposedValuePreview.take(200))
}

private fun CueStyle.toProto(): CueStyleProto = when (this) {
    CueStyle.ENCOURAGING -> CueStyleProto.CUE_STYLE_ENCOURAGING
    CueStyle.TERSE -> CueStyleProto.CUE_STYLE_TERSE
    CueStyle.DETAILED -> CueStyleProto.CUE_STYLE_DETAILED
}

private fun CueStyleProto.toDomain(): CueStyle = when (this) {
    CueStyleProto.CUE_STYLE_TERSE -> CueStyle.TERSE
    CueStyleProto.CUE_STYLE_DETAILED -> CueStyle.DETAILED
    CueStyleProto.CUE_STYLE_ENCOURAGING,
    CueStyleProto.CUE_STYLE_UNSPECIFIED,
    CueStyleProto.UNRECOGNIZED,
    -> CueStyle.ENCOURAGING
}

private fun SupportType.toProto(): SupportTypeProto = when (this) {
    SupportType.CHAIR -> SupportTypeProto.SUPPORT_TYPE_CHAIR
    SupportType.WALL -> SupportTypeProto.SUPPORT_TYPE_WALL
    SupportType.NONE -> SupportTypeProto.SUPPORT_TYPE_NONE
}

private fun SupportTypeProto.toDomain(): SupportType = when (this) {
    SupportTypeProto.SUPPORT_TYPE_CHAIR -> SupportType.CHAIR
    SupportTypeProto.SUPPORT_TYPE_WALL -> SupportType.WALL
    SupportTypeProto.SUPPORT_TYPE_NONE,
    SupportTypeProto.SUPPORT_TYPE_UNSPECIFIED,
    SupportTypeProto.UNRECOGNIZED,
    -> SupportType.NONE
}

private fun jsonStringList(json: String): List<String> {
    return runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }.getOrDefault(emptyList())
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T {
    return runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}

private fun String.sanitizedExerciseKey(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9_\\-]"), "_")
        .ifBlank { "unknown" }
}
