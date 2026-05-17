package com.gemmafit.debug

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.gemmafit.BuildConfig
import com.gemmafit.jni.KinematicsBridge
import com.gemmafit.video.BackendPreference
import com.gemmafit.video.CoachContext
import com.gemmafit.video.CoachModelResolver
import com.gemmafit.video.EvidenceCard
import com.gemmafit.video.EvidenceItem
import com.gemmafit.video.ModelInvocationDecision
import com.gemmafit.video.Layer2Event
import com.gemmafit.video.Layer2FrameFeatures
import com.gemmafit.video.Layer2ActivityHypothesis
import com.gemmafit.video.Layer2Output
import com.gemmafit.video.Layer2Phase
import com.gemmafit.video.Layer2RulePolicy
import com.gemmafit.video.Layer2TemporalInterpreter
import com.gemmafit.video.GemmaFitLiteRtTools
import com.gemmafit.video.LiteRtConstrainedToolResult
import com.gemmafit.video.LiteRtInitAttempt
import com.gemmafit.video.LiteRtLmCoachBackend
import com.gemmafit.video.ModelDeviceState
import com.gemmafit.video.ModelReadinessSnapshot
import com.gemmafit.video.ModelInvocationRequest
import com.gemmafit.video.ModelInvocationScheduler
import com.gemmafit.video.ModelInvocationTrigger
import com.gemmafit.video.ModelReasoningMode
import com.gemmafit.video.MotionDerivedLabels
import com.gemmafit.video.MotionFeatureValues
import com.gemmafit.video.MotionFeatureWindow
import com.gemmafit.video.MotionZipPacketBuilder
import com.gemmafit.video.MuscleFocusResult
import com.gemmafit.video.NormalizedLandmark
import com.gemmafit.video.PersonTrackingState
import com.gemmafit.video.QualityFlag
import com.gemmafit.video.SafetyWarning
import com.gemmafit.video.TemporalMotionAnalyzer
import com.gemmafit.video.VideoAnalysisPass
import com.gemmafit.video.VideoProcessor
import com.gemmafit.video.toDebugMap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import com.google.ai.edge.litertlm.tool
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Debug-only structured event surface for adb inspection.
 *
 * The app writes compact JSONL events and a latest-state snapshot under
 * files/debug. DebugReportProvider exposes those files through a read-only
 * content URI so a connected phone can be inspected without guessing logcat.
 */
object GemmaFitDebugApi {
    private const val TAG = "GemmaFit.DebugApi"
    private const val MAX_EVENT_BYTES = 1_500_000L
    private const val DEFAULT_EVENT_LIMIT = 180
    private const val LOGCAT_REPEAT_INTERVAL_MS = 1_000L
    private const val FUNCTIONGEMMA_MAX_TOKENS = 1024
    private const val FUNCTIONGEMMA_TIMEOUT_MS = 45_000L
    private const val MODEL_EQUIVALENCE_MAX_TOKENS = 512
    private const val MODEL_EQUIVALENCE_TIMEOUT_MS = 90_000L
    private const val MODEL_EQUIVALENCE_DEFAULT_FILE = "model_prompt_pair_compact.jsonl"
    private const val LITERT_PROMPT_INFER_MAX_TOKENS = 4096
    private const val LITERT_PROMPT_INFER_TIMEOUT_MS = 90_000L
    private const val LITERT_PROMPT_INFER_DEFAULT_FILE = "litert_session_prompt_request.json"
    private const val LITERT_IMAGE_PROMPT_DEFAULT_FILE = "debug_phone_current.png"
    private const val LITERT_VISUAL_CONTEXT_MAX_TOKENS = 512
    private const val LITERT_VISUAL_CONTEXT_TIMEOUT_MS = 8_000L
    private const val ISOLATED_ENGINE_TTL_MS = 180_000L
    private const val VIDEO_REALTIME_SMOKE_TIMEOUT_MS = 180_000L
    private const val VIDEO_REALTIME_SMOKE_FRAME_LIMIT = 96
    private const val VIDEO_REALTIME_SMOKE_INTERVAL_MS = 200L
    private const val VIDEO_REALTIME_SMOKE_LONG_SIDE = 256
    private const val VIDEO_REALTIME_SMOKE_MAX_POSES = 1
    private const val APP_LAUNCH_PREWARM_COOLDOWN_MS = 10 * 60 * 1_000L

    private val lock = Any()
    private val state = LinkedHashMap<String, JSONObject>()
    private val lastLogcatAtMs = LinkedHashMap<String, Long>()
    private var appContext: Context? = null
    private var appLaunchPrewarmStartedAtMs: Long = 0L
    private val isolatedEngineLock = Any()
    private var isolatedEngine: Engine? = null
    private var isolatedEngineModelPath: String? = null
    private var isolatedEngineBackendLabel: String? = null
    private var isolatedEngineMaxNumTokens: Int = LITERT_PROMPT_INFER_MAX_TOKENS
    private var isolatedEngineMaxNumImages: Int = 0
    private var isolatedEngineCreatedAtMs: Long = 0L
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GemmaFitDebugWriter").apply {
            isDaemon = true
        }
    }

    fun initialize(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        enqueueIo {
            debugDir().mkdirs()
            rotateIfOversizedLocked()
            writeStateLocked()
        }
    }

    fun record(
        category: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        if (!BuildConfig.DEBUG) return
        val threadName = Thread.currentThread().name
        val event = JSONObject()
            .put("ts_ms", System.currentTimeMillis())
            .put("category", category)
            .put("message", message)
            .put("thread", threadName)
            .put("data", data.toJsonObject())
        enqueueIo {
            val context = appContext ?: return@enqueueIo
            runCatching {
                val file = eventFile(context)
                file.parentFile?.mkdirs()
                rotateIfOversizedLocked()
                file.appendText(event.toString() + "\n")
            }.onFailure {
                Log.w(TAG, "record failed: ${it.message}")
            }
        }
        if (shouldLogcat(category, message, event.getLong("ts_ms"))) {
            Log.d(TAG, "$category: $message")
        }
    }

    fun updateState(
        section: String,
        data: Map<String, Any?>,
    ) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            appContext ?: return
            state[section] = JSONObject()
                .put("updated_at_ms", System.currentTimeMillis())
                .put("data", data.toJsonObject())
        }
        enqueueIo {
            writeStateLocked()
        }
    }

    fun dumpReport(context: Context, maxEvents: Int = DEFAULT_EVENT_LIMIT): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        synchronized(lock) {
            val dir = debugDir(context)
            return JSONObject()
                .put("enabled", true)
                .put("generated_at_ms", System.currentTimeMillis())
                .put("package", context.packageName)
                .put("debug_dir", dir.absolutePath)
                .put("commands", JSONArray(listOf(
                    "adb shell content read --uri content://com.gemmafit.debug/report",
                    "adb shell content read --uri content://com.gemmafit.debug/events",
                    "adb shell content read --uri content://com.gemmafit.debug/care_log",
                    "adb shell content read --uri content://com.gemmafit.debug/dual_task",
                    "adb shell content read --uri content://com.gemmafit.debug/subjective_checkin",
                    "adb shell content read --uri content://com.gemmafit.debug/persona_report",
                    "adb shell content read --uri 'content://com.gemmafit.debug/model_readiness?model=official'",
                    "adb shell content read --uri content://com.gemmafit.debug/review_frame",
                    "adb shell content read --uri content://com.gemmafit.debug/pose_detection_timeline",
                    "adb shell content read --uri content://com.gemmafit.debug/no_pose_retry",
                    "adb shell content read --uri content://com.gemmafit.debug/person_detector",
                    "adb shell content read --uri content://com.gemmafit.debug/motion_zip_packet",
                    "adb shell content read --uri content://com.gemmafit.debug/rgba_pipeline_audit",
                    "adb shell content read --uri 'content://com.gemmafit.debug/rgba_pipeline_audit?reset=true'",
                    "adb shell content read --uri content://com.gemmafit.debug/litert_smoke",
                    "adb shell content read --uri content://com.gemmafit.debug/litert_effect_smoke",
                    "adb shell content read --uri content://com.gemmafit.debug/litert_raw_smoke",
                    "adb shell content read --uri 'content://com.gemmafit.debug/litert_prewarm?model=official'",
                    "adb shell content read --uri 'content://com.gemmafit.debug/litert_prompt_infer?model=official'",
                    "adb shell content read --uri 'content://com.gemmafit.debug/litert_visual_context_infer?scene=session_scene_anchor.png&panel=session_motionzip_panel.png&model=official'",
                    "adb shell content read --uri 'content://com.gemmafit.debug/motionzip_model_equivalence?model=official&backend=auto&max_tokens=512'",
                    "adb shell content read --uri content://com.gemmafit.debug/model_invocation_smoke",
                    "adb shell content read --uri content://com.gemmafit.debug/layer2_smoke",
                    "adb shell content read --uri content://com.gemmafit.debug/litert_functiongemma_no_tools",
                    "adb shell content read --uri content://com.gemmafit.debug/litert_functiongemma_min_tool",
                    "adb shell content delete --uri content://com.gemmafit.debug/events",
                )))
                .put("state", readStateLocked(context))
                .put("events", readEventsLocked(context, maxEvents))
                .toString(2)
        }
    }

    fun dumpEvents(context: Context, maxEvents: Int = DEFAULT_EVENT_LIMIT): String {
        if (!BuildConfig.DEBUG) return "[]"
        initialize(context)
        synchronized(lock) {
            return readEventsLocked(context, maxEvents).toString(2)
        }
    }

    fun dumpState(context: Context): String {
        if (!BuildConfig.DEBUG) return "{}"
        initialize(context)
        synchronized(lock) {
            return readStateLocked(context).toString(2)
        }
    }

    fun dumpSection(context: Context, section: String): String {
        if (!BuildConfig.DEBUG) return "{}"
        initialize(context)
        synchronized(lock) {
            val sections = readStateLocked(context).optJSONObject("sections") ?: JSONObject()
            return (sections.optJSONObject(section) ?: JSONObject()
                .put("section", section)
                .put("status", "missing"))
                .toString(2)
        }
    }

    fun dumpModelReadiness(context: Context, requestedModel: String? = null): String {
        if (!BuildConfig.DEBUG) return "{}"
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("section", "model_readiness")
                .put("status", "error")
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel)
        val snapshot = ModelReadinessSnapshot.from(
            liteRtModelPath = modelPath,
            backend = if (modelPath == null) "fallback" else "litert-lm:available",
            fallback = modelPath == null,
            fallbackReason = if (modelPath == null) "litert_model_file_not_found" else "",
        )
        val payload = mapOf(
            "status" to snapshot.status.name.lowercase(),
            "label" to snapshot.label,
            "requested_model" to (requestedModel ?: ""),
            "model_priority" to JSONArray(CoachModelResolver.liteRtModelPriority(requestedModel)),
            "model_path" to (modelPath ?: ""),
            "model_file_name" to snapshot.modelFileName,
            "model_size_bytes" to snapshot.modelSizeBytes,
            "backend" to snapshot.backend,
            "fallback_reason" to snapshot.fallbackReason,
        )
        updateState("model_readiness", payload)
        return JSONObject()
            .put("section", "model_readiness")
            .put("data", payload.toJsonObject())
            .toString(2)
    }

    fun updateCareLogState(data: Map<String, Any?>) {
        updateState("care_log", data)
        record("care_log", "state_updated", data)
    }

    fun updateDualTaskState(data: Map<String, Any?>) {
        updateState("dual_task", data)
        record("dual_task", "state_updated", data)
    }

    fun updateSubjectiveCheckinState(data: Map<String, Any?>) {
        updateState("subjective_checkin", data)
        record("subjective_checkin", "state_updated", data)
    }

    fun updatePersonaReportState(data: Map<String, Any?>) {
        updateState("persona_report", data)
        record("persona_report", "state_updated", data)
    }

    fun recordRgbaPipelineFrame(context: Context, sample: RgbaPipelineFrameSample) {
        if (!BuildConfig.DEBUG) return
        initialize(context)
        if (!RgbaPipelineAudit.record(sample)) return
        updateState(
            section = "rgba_pipeline_audit",
            data = mapOf("snapshot" to RgbaPipelineAudit.snapshotJson()),
        )
    }

    fun dumpRgbaPipelineAudit(context: Context, reset: Boolean = false): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        if (reset) {
            RgbaPipelineAudit.reset()
            record("rgba_pipeline_audit", "reset")
        }
        val payload = RgbaPipelineAudit.snapshotJson()
        updateState(
            section = "rgba_pipeline_audit",
            data = mapOf("snapshot" to payload),
        )
        return payload.toString(2)
    }

    fun startAppLaunchLiteRtPrewarm(
        context: Context,
        requestedModel: String? = "official",
        requestedMaxNumImages: String? = "0",
    ) {
        if (!BuildConfig.DEBUG) return
        initialize(context)
        val maxNumImages = requestedMaxNumImages
            ?.toIntOrNull()
            ?.coerceIn(0, 2)
            ?: 0
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - appLaunchPrewarmStartedAtMs < APP_LAUNCH_PREWARM_COOLDOWN_MS) {
                return
            }
            appLaunchPrewarmStartedAtMs = now
        }
        val thermalStatus = currentThermalStatus(context)
        if (shouldSkipPrewarmForThermal(thermalStatus)) {
            updateState(
                section = "litert_prewarm",
                data = mapOf(
                    "success" to false,
                    "stage" to "skipped_thermal",
                    "trigger" to "app_launch",
                    "requested_model" to (requestedModel ?: ""),
                    "max_num_images" to maxNumImages,
                    "thermal_status" to thermalStatus,
                    "thermal_status_name" to thermalStatusName(thermalStatus),
                ),
            )
            record(
                category = "coach_summary",
                message = "litert_app_launch_prewarm_skipped_thermal",
                data = mapOf(
                    "thermal_status" to thermalStatus,
                    "thermal_status_name" to thermalStatusName(thermalStatus),
                    "max_num_images" to maxNumImages,
                ),
            )
            return
        }
        updateState(
            section = "litert_prewarm",
            data = mapOf(
                "success" to false,
                "stage" to "scheduled",
                "trigger" to "app_launch",
                "requested_model" to (requestedModel ?: ""),
                "max_num_images" to maxNumImages,
                "thermal_status" to thermalStatus,
                "thermal_status_name" to thermalStatusName(thermalStatus),
            ),
        )
        Thread({
            record(
                category = "coach_summary",
                message = "litert_app_launch_prewarm_started",
                data = mapOf(
                    "requested_model" to (requestedModel ?: ""),
                    "max_num_images" to maxNumImages,
                    "thermal_status" to thermalStatus,
                    "thermal_status_name" to thermalStatusName(thermalStatus),
                ),
            )
            runCatching {
                runLiteRtPrewarm(
                    context = context.applicationContext,
                    requestedModel = requestedModel,
                    requestedMaxNumImages = maxNumImages.toString(),
                )
            }.onFailure { error ->
                updateState(
                    section = "litert_prewarm",
                    data = mapOf(
                        "success" to false,
                        "stage" to "app_launch_failed",
                        "trigger" to "app_launch",
                        "requested_model" to (requestedModel ?: ""),
                        "max_num_images" to maxNumImages,
                        "error_type" to error::class.java.name,
                        "error" to (error.message ?: "app_launch_prewarm_failed"),
                    ),
                )
            }
        }, "GemmaFitLiteRtPrewarm").apply {
            isDaemon = true
            start()
        }
    }

    fun runLiteRtSmoke(context: Context): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            val payload = JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm")
                .put("error", "litert_model_file_not_found")
            record(
                category = "litert_smoke",
                message = "model_missing",
                data = mapOf("model_path" to modelPath),
            )
            return payload.toString(2)
        }

        val backend = LiteRtLmCoachBackend(app)
        val started = System.currentTimeMillis()
        return try {
            val result = runBlocking(Dispatchers.IO) {
                backend.runInference(
                    context = smokeCoachContext(),
                    safetyJson = smokeSafetyJson(),
                    reasoningMode = ModelReasoningMode.OFF,
                )
            }
            val initAttempts = backend.initAttemptsSnapshot().toJsonArray()
            val modelInfo = runCatching { JSONObject(result.modelInfoJson) }.getOrElse { JSONObject() }
            val payload = JSONObject()
                .put("enabled", true)
                .put("success", result.success)
                .put("backend", result.backend)
                .put("function", result.functionName)
                .put("error", result.errorMessage)
                .put("inference_time_ms", result.inferenceTimeMs)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("selection_basis", result.selectionBasis)
                .put("evidence_refs", JSONArray(result.evidenceRefs))
                .put("model_path", modelPath)
                .put("model_size_bytes", modelFile.length())
                .put("init_attempts", initAttempts)
                .put("model_info", modelInfo)
            record(
                category = "litert_smoke",
                message = "completed",
                data = mapOf(
                    "success" to result.success,
                    "backend" to result.backend,
                    "error" to result.errorMessage,
                    "elapsed_ms" to (System.currentTimeMillis() - started),
                    "init_attempts" to initAttempts,
                ),
            )
            updateState(
                section = "litert_smoke",
                data = mapOf(
                    "success" to result.success,
                    "backend" to result.backend,
                    "error" to result.errorMessage,
                    "model_path" to modelPath,
                    "model_size_bytes" to modelFile.length(),
                    "model_info" to modelInfo,
                    "elapsed_ms" to (System.currentTimeMillis() - started),
                    "init_attempts" to initAttempts,
                ),
            )
            payload.toString(2)
        } catch (e: Throwable) {
            val initAttempts = backend.initAttemptsSnapshot().toJsonArray()
            val payload = JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm")
                .put("error", e.message ?: "litert_smoke_failed")
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("model_path", modelPath)
                .put("model_size_bytes", modelFile.length())
                .put("init_attempts", initAttempts)
            record(
                category = "litert_smoke",
                message = "failed",
                data = mapOf(
                    "error" to (e.message ?: "unknown"),
                    "elapsed_ms" to (System.currentTimeMillis() - started),
                    "init_attempts" to initAttempts,
                ),
            )
            updateState(
                section = "litert_smoke",
                data = mapOf(
                    "success" to false,
                    "backend" to "litert-lm",
                    "error" to (e.message ?: "litert_smoke_failed"),
                    "model_path" to modelPath,
                    "model_size_bytes" to modelFile.length(),
                    "elapsed_ms" to (System.currentTimeMillis() - started),
                    "init_attempts" to initAttempts,
                ),
            )
            payload.toString(2)
        } finally {
            backend.close()
        }
    }

    fun runLiteRtEffectSmoke(context: Context): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }

        val started = System.currentTimeMillis()
        val cases = liteRtEffectCases()
        val results = JSONArray()
        var allSucceeded = true
        for (case in cases) {
            val backend = LiteRtLmCoachBackend(app)
            val caseStarted = System.currentTimeMillis()
            try {
                val result = runBlocking(Dispatchers.IO) {
                    backend.runInference(
                        context = case.context,
                        safetyJson = case.safetyJson,
                        reasoningMode = ModelReasoningMode.OFF,
                    )
                }
                val matchesExpected = result.functionName == case.expectedFamily
                if (!result.success || !matchesExpected) allSucceeded = false
                results.put(
                    JSONObject()
                        .put("name", case.name)
                        .put("expected_family", case.expectedFamily)
                        .put("success", result.success)
                        .put("matches_expected", matchesExpected)
                        .put("backend", result.backend)
                        .put("function", result.functionName)
                        .put("args", runCatching { JSONObject(result.argsJson) }.getOrElse { JSONObject() })
                        .put("error", result.errorMessage)
                        .put("selection_basis", result.selectionBasis)
                        .put("evidence_refs", JSONArray(result.evidenceRefs))
                        .put("inference_time_ms", result.inferenceTimeMs)
                        .put("elapsed_ms", System.currentTimeMillis() - caseStarted)
                        .put("raw_response", result.rawResponse.take(1000))
                        .put("init_attempts", backend.initAttemptsSnapshot().toJsonArray())
                )
            } catch (e: Throwable) {
                allSucceeded = false
                results.put(
                    JSONObject()
                        .put("name", case.name)
                        .put("expected_family", case.expectedFamily)
                        .put("success", false)
                        .put("backend", "litert-lm")
                        .put("function", "")
                        .put("error_type", e::class.java.name)
                        .put("error", e.message ?: "litert_effect_case_failed")
                        .put("elapsed_ms", System.currentTimeMillis() - caseStarted)
                        .put("init_attempts", backend.initAttemptsSnapshot().toJsonArray())
                )
            } finally {
                backend.close()
            }
        }
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", allSucceeded)
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("elapsed_ms", System.currentTimeMillis() - started)
            .put("cases", results)
        updateState(
            section = "litert_effect_smoke",
            data = mapOf(
                "success" to allSucceeded,
                "model_path" to modelPath,
                "model_size_bytes" to modelFile.length(),
                "elapsed_ms" to (System.currentTimeMillis() - started),
                "cases" to results,
            ),
        )
        return payload.toString(2)
    }

    fun runLiteRtRawSmoke(context: Context): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:raw")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }

        val started = System.currentTimeMillis()
        val prompt = rawLiteRtSmokePrompt()
        val attempts = JSONArray()
        val backends = listOf(
            "litert-lm:raw:gpu" to Backend.GPU(),
            "litert-lm:raw:cpu" to Backend.CPU(),
        )
        for ((label, backend) in backends) {
            var stage = "engine_create"
            var engine: Engine? = null
            val attemptStarted = System.currentTimeMillis()
            try {
                engine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        maxNumTokens = 4096,
                        cacheDir = File(context.cacheDir, "litert-lm-raw").absolutePath,
                    )
                )
                stage = "engine_initialize"
                engine.initialize()
                stage = "session_create"
                engine.createSession(
                    SessionConfig(
                        SamplerConfig(
                            topK = 1,
                            topP = 0.1,
                            temperature = 0.0,
                            seed = 17,
                        )
                    )
                ).use { session ->
                    stage = "generate_content"
                    val raw = runBlocking(Dispatchers.IO) {
                        withTimeout(60_000L) {
                            session.generateContent(listOf(InputData.Text(prompt)))
                        }
                    }
                    val parsed = parseFirstJsonObject(raw)
                    val functionName = parsed?.optString("function").orEmpty()
                    val functionAllowed = functionName in rawSmokeAllowedFunctions
                    val args = parsed?.optJSONObject("args") ?: JSONObject()
                    val payload = JSONObject()
                        .put("enabled", true)
                        .put("success", functionAllowed)
                        .put("generation_success", raw.isNotBlank())
                        .put("backend", label)
                        .put("stage", "complete")
                        .put("function", functionName)
                        .put("function_allowed", functionAllowed)
                        .put("args", args)
                        .put("error", rawSmokeError(functionName, functionAllowed))
                        .put("elapsed_ms", System.currentTimeMillis() - started)
                        .put("attempt_elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("model_path", modelPath)
                        .put("model_size_bytes", modelFile.length())
                        .put("raw_response", raw.take(4_000))
                        .put("attempts", attempts)
                    updateState(
                        section = "litert_raw_smoke",
                        data = mapOf(
                            "success" to payload.optBoolean("success"),
                            "generation_success" to payload.optBoolean("generation_success"),
                            "backend" to label,
                            "function" to functionName,
                            "function_allowed" to functionAllowed,
                            "error" to payload.optString("error"),
                            "model_path" to modelPath,
                            "model_size_bytes" to modelFile.length(),
                            "elapsed_ms" to payload.optLong("elapsed_ms"),
                        ),
                    )
                    return payload.toString(2)
                }
            } catch (e: Throwable) {
                attempts.put(
                    JSONObject()
                        .put("backend", label)
                        .put("stage", stage)
                        .put("elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("error_type", e::class.java.name)
                        .put("error", e.message ?: "unknown")
                )
            } finally {
                runCatching { engine?.close() }
            }
        }
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", false)
            .put("backend", "litert-lm:raw")
            .put("error", "raw_session_smoke_failed")
            .put("elapsed_ms", System.currentTimeMillis() - started)
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("attempts", attempts)
        updateState(
            section = "litert_raw_smoke",
            data = mapOf(
                "success" to false,
                "backend" to "litert-lm:raw",
                "error" to "raw_session_smoke_failed",
                "model_path" to modelPath,
                "model_size_bytes" to modelFile.length(),
                "elapsed_ms" to payload.optLong("elapsed_ms"),
                "attempts" to attempts,
            ),
        )
        return payload.toString(2)
    }

    private data class IsolatedEngineLease(
        val engine: Engine,
        val backendLabel: String,
        val reusedEngine: Boolean,
        val engineCreateMs: Long,
        val engineInitializeMs: Long,
    )

    private fun isolatedBackendOptions(preferredLabel: String? = null): List<Pair<String, Backend>> {
        val base = listOf(
            "litert-lm:isolated:gpu" to Backend.GPU(),
            "litert-lm:isolated:cpu" to Backend.CPU(),
        )
        if (preferredLabel.isNullOrBlank()) return base
        return base.sortedBy { (label, _) -> if (label == preferredLabel) 0 else 1 }
    }

    private fun motionZipEquivalenceBackendOptions(requestedBackend: String? = null): List<Pair<String, Backend>> {
        val base = listOf(
            "litert-lm:isolated:gpu" to Backend.GPU(),
            "litert-lm:isolated:cpu" to Backend.CPU(),
        )
        val normalized = requestedBackend
            ?.trim()
            ?.lowercase()
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotEmpty() && it != "auto" }
            ?: return base
        return when (normalized) {
            "gpu" -> listOf(base[0])
            "cpu" -> listOf(base[1])
            else -> base
        }
    }

    private fun isForcedMotionZipEquivalenceBackend(requestedBackend: String?): Boolean {
        val normalized = requestedBackend
            ?.trim()
            ?.lowercase()
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return normalized != "auto"
    }

    private fun reusableIsolatedEngineLabel(
        modelPath: String,
        maxNumTokens: Int = LITERT_PROMPT_INFER_MAX_TOKENS,
        maxNumImages: Int = 0,
    ): String? = synchronized(isolatedEngineLock) {
        val normalizedMaxNumImages = maxNumImages.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        if (
            isolatedEngine != null &&
            isolatedEngineModelPath == modelPath &&
            isolatedEngineMaxNumTokens == maxNumTokens &&
            isolatedEngineMaxNumImages >= normalizedMaxNumImages &&
            now - isolatedEngineCreatedAtMs < ISOLATED_ENGINE_TTL_MS
        ) {
            isolatedEngineBackendLabel
        } else {
            null
        }
    }

    private fun ensureIsolatedEngineLocked(
        context: Context,
        modelPath: String,
        backendLabel: String,
        backend: Backend,
        maxNumTokens: Int = LITERT_PROMPT_INFER_MAX_TOKENS,
        maxNumImages: Int = 0,
    ): IsolatedEngineLease {
        val normalizedMaxNumImages = maxNumImages.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val reusable =
            isolatedEngine != null &&
                isolatedEngineModelPath == modelPath &&
                isolatedEngineBackendLabel == backendLabel &&
                isolatedEngineMaxNumTokens == maxNumTokens &&
                isolatedEngineMaxNumImages >= normalizedMaxNumImages &&
                now - isolatedEngineCreatedAtMs < ISOLATED_ENGINE_TTL_MS
        if (reusable) {
            return IsolatedEngineLease(
                engine = requireNotNull(isolatedEngine),
                backendLabel = backendLabel,
                reusedEngine = true,
                engineCreateMs = 0L,
                engineInitializeMs = 0L,
            )
        }

        closeIsolatedEngineLocked()
        val cacheSuffix = backendLabel.substringAfterLast(':').ifBlank { "default" }
        val imageSuffix = if (normalizedMaxNumImages > 0) "-img$normalizedMaxNumImages" else ""
        val createStarted = System.currentTimeMillis()
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = if (normalizedMaxNumImages > 0) backend else null,
                maxNumTokens = maxNumTokens,
                maxNumImages = normalizedMaxNumImages.takeIf { it > 0 },
                cacheDir = File(
                    context.cacheDir,
                    "litert-lm-isolated-session-$cacheSuffix$imageSuffix",
                ).absolutePath,
            ),
        )
        val engineCreateMs = System.currentTimeMillis() - createStarted
        try {
            val initializeStarted = System.currentTimeMillis()
            engine.initialize()
            val engineInitializeMs = System.currentTimeMillis() - initializeStarted
            isolatedEngine = engine
            isolatedEngineModelPath = modelPath
            isolatedEngineBackendLabel = backendLabel
            isolatedEngineMaxNumTokens = maxNumTokens
            isolatedEngineMaxNumImages = normalizedMaxNumImages
            isolatedEngineCreatedAtMs = System.currentTimeMillis()
            return IsolatedEngineLease(
                engine = engine,
                backendLabel = backendLabel,
                reusedEngine = false,
                engineCreateMs = engineCreateMs,
                engineInitializeMs = engineInitializeMs,
            )
        } catch (error: Throwable) {
            runCatching { engine.close() }
            isolatedEngine = null
            isolatedEngineModelPath = null
            isolatedEngineBackendLabel = null
            isolatedEngineMaxNumTokens = LITERT_PROMPT_INFER_MAX_TOKENS
            isolatedEngineMaxNumImages = 0
            isolatedEngineCreatedAtMs = 0L
            throw error
        }
    }

    private fun closeIsolatedEngineLocked() {
        runCatching { isolatedEngine?.close() }
        isolatedEngine = null
        isolatedEngineModelPath = null
        isolatedEngineBackendLabel = null
        isolatedEngineMaxNumTokens = LITERT_PROMPT_INFER_MAX_TOKENS
        isolatedEngineMaxNumImages = 0
        isolatedEngineCreatedAtMs = 0L
    }

    private fun sessionConfigForPromptInference(): SessionConfig {
        return SessionConfig(
            SamplerConfig(
                topK = 1,
                topP = 0.1,
                temperature = 0.0,
                seed = 17,
            ),
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun runConstrainedPromptInference(
        engine: Engine,
        prompt: String,
        started: Long,
        attemptStarted: Long,
        label: String,
        modelPath: String,
        modelFile: File,
        requestedModel: String?,
        promptChars: Int,
        requestFile: File,
        attempts: JSONArray,
        engineCreateMs: Long,
        engineInitializeMs: Long,
        reusedEngine: Boolean,
        streamRequested: Boolean,
    ): JSONObject {
        val toolExecutions = mutableListOf<JSONObject>()
        val previousConstrained = ExperimentalFlags.enableConversationConstrainedDecoding
        val conversationCreateStarted = System.currentTimeMillis()
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are GemmaFit's constrained session-summary router. " +
                            "Use create_care_activity_log exactly once. " +
                            "Do not output prose outside the tool call."
                    ),
                    tools = GemmaFitLiteRtTools.sessionSummary(toolExecutions).map { tool(it) },
                    samplerConfig = SamplerConfig(
                        topK = 1,
                        topP = 0.1,
                        temperature = 0.0,
                        seed = 17,
                    ),
                    automaticToolCalling = true,
                )
            ).use { conversation ->
                val conversationCreateMs = System.currentTimeMillis() - conversationCreateStarted
                val generateStarted = System.currentTimeMillis()
                val response = conversation.sendMessage(prompt)
                val generateContentMs = System.currentTimeMillis() - generateStarted
                val toolCalls = toolCallsToJsonArray(response.toolCalls)
                val constrainedToolCallObserved = toolCalls.length() > 0 || toolExecutions.isNotEmpty()
                val raw = LiteRtConstrainedToolResult.rawFunctionCallFrom(
                    executions = toolExecutions,
                    toolCalls = toolCalls,
                    fallbackContent = response.contents.toString(),
                )
                val totalElapsedMs = System.currentTimeMillis() - started
                val firstTokenMs = generateContentMs.takeIf { raw.isNotBlank() } ?: 0L
                return JSONObject()
                    .put("enabled", true)
                    .put("success", raw.isNotBlank())
                    .put("generation_success", raw.isNotBlank())
                    .put("backend", label)
                    .put("stage", "complete")
                    .put("elapsed_ms", totalElapsedMs)
                    .put("attempt_elapsed_ms", System.currentTimeMillis() - attemptStarted)
                    .put("engine_create_ms", engineCreateMs)
                    .put("engine_initialize_ms", engineInitializeMs)
                    .put("session_create_ms", conversationCreateMs)
                    .put("generate_content_ms", generateContentMs)
                    .put("constrained_decoding", true)
                    .put("constrained_decoding_ms", generateContentMs)
                    .put("constrained_tool_call_observed", constrainedToolCallObserved)
                    .put("stream_requested", streamRequested)
                    .put("stream_token_count", 0)
                    .put("first_token_ms", firstTokenMs)
                    .put("total_elapsed_ms", totalElapsedMs)
                    .put("reused_engine", reusedEngine)
                    .put("requested_model", requestedModel ?: "")
                    .put("model_path", modelPath)
                    .put("model_size_bytes", modelFile.length())
                    .put("prompt_chars", promptChars)
                    .put("request_file", requestFile.absolutePath)
                    .put("response_role", response.role.toString())
                    .put("response_contents", response.contents.toString().take(4000))
                    .put("tool_calls", toolCalls)
                    .put("tool_executions", jsonObjectsToArray(toolExecutions))
                    .put("raw_response", raw)
                    .put("attempts", attempts)
            }
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = previousConstrained
        }
    }

    fun writeLiteRtPromptInferenceStream(
        context: Context,
        requestedName: String?,
        requestedModel: String? = null,
        output: OutputStream,
    ) {
        fun writeEvent(event: String, payload: JSONObject = JSONObject()) {
            val obj = JSONObject()
                .put("event", event)
                .put("timestamp_ms", System.currentTimeMillis())
            payload.keys().forEach { key -> obj.put(key, payload.opt(key)) }
            output.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        fun writeDone(payload: JSONObject) {
            writeEvent("done", JSONObject().put("payload", payload))
        }

        fun writeError(error: String, detail: String = "") {
            val payload = JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("generation_success", false)
                .put("backend", "litert-lm:isolated:stream")
                .put("stage", "failed")
                .put("error", error)
                .put("error_detail", detail)
                .put("stream_requested", true)
            writeEvent("error", JSONObject().put("payload", payload))
            writeDone(payload)
        }

        if (!BuildConfig.DEBUG) {
            writeDone(JSONObject().put("enabled", false).put("reason", "debug_api_disabled_in_release"))
            return
        }
        initialize(context)
        val app = context.applicationContext as? Application
        if (app == null) {
            writeError("application_context_unavailable")
            return
        }
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            writeError("litert_model_file_not_found")
            return
        }
        val requestFile = resolveDebugRequestFile(
            context = context,
            requestedName = requestedName,
            defaultName = LITERT_PROMPT_INFER_DEFAULT_FILE,
        )
        if (requestFile == null || !requestFile.exists()) {
            writeError("prompt_request_not_found", requestedName ?: LITERT_PROMPT_INFER_DEFAULT_FILE)
            return
        }
        val request = runCatching { JSONObject(requestFile.readText()) }.getOrElse { error ->
            writeError("prompt_request_invalid_json", error.message ?: "unknown")
            return
        }
        val prompt = request.optString("prompt").trim()
        if (prompt.isBlank()) {
            writeError("prompt_request_empty", requestFile.absolutePath)
            return
        }

        val started = System.currentTimeMillis()
        val attempts = JSONArray()
        writeEvent(
            "started",
            JSONObject()
                .put("backend", "litert-lm:isolated:stream")
                .put("requested_model", requestedModel ?: "")
                .put("model_path", modelPath)
                .put("prompt_chars", prompt.length)
                .put("request_file", requestFile.absolutePath),
        )

        for ((label, backend) in isolatedBackendOptions(reusableIsolatedEngineLabel(modelPath))) {
            var stage = "engine_create"
            val attemptStarted = System.currentTimeMillis()
            var engineCreateMs = 0L
            var engineInitializeMs = 0L
            var sessionCreateMs = 0L
            var generateContentMs = 0L
            var reusedEngine = false
            try {
                val payload = synchronized(isolatedEngineLock) {
                    val lease = ensureIsolatedEngineLocked(context, modelPath, label, backend)
                    engineCreateMs = lease.engineCreateMs
                    engineInitializeMs = lease.engineInitializeMs
                    reusedEngine = lease.reusedEngine
                    stage = "session_create"
                    val sessionCreateStarted = System.currentTimeMillis()
                    val litertSession = lease.engine.createSession(sessionConfigForPromptInference())
                    sessionCreateMs = System.currentTimeMillis() - sessionCreateStarted
                    stage = "generate_content_stream"
                    litertSession.use { session ->
                        val generateStarted = System.currentTimeMillis()
                        val done = CountDownLatch(1)
                        val streamError = AtomicReference<Throwable?>(null)
                        val rawBuilder = StringBuilder()
                        var lastCallbackText = ""
                        var firstTokenMs = 0L
                        var streamTokenCount = 0

                        writeEvent(
                            "prefill",
                            JSONObject()
                                .put("backend", label)
                                .put("engine_create_ms", engineCreateMs)
                                .put("engine_initialize_ms", engineInitializeMs)
                                .put("session_create_ms", sessionCreateMs)
                                .put("reused_engine", reusedEngine),
                        )

                        session.generateContentStream(
                            listOf(InputData.Text(prompt)),
                            object : ResponseCallback {
                                override fun onNext(text: String) {
                                    if (text.isBlank()) return
                                    val delta = if (text.startsWith(lastCallbackText)) {
                                        text.removePrefix(lastCallbackText)
                                    } else {
                                        text
                                    }
                                    lastCallbackText = if (text.startsWith(lastCallbackText)) text else lastCallbackText + delta
                                    if (delta.isBlank()) return
                                    rawBuilder.append(delta)
                                    streamTokenCount += 1
                                    if (firstTokenMs == 0L) {
                                        firstTokenMs = System.currentTimeMillis() - generateStarted
                                    }
                                    writeEvent(
                                        "token",
                                        JSONObject()
                                            .put("backend", label)
                                            .put("elapsed_ms", System.currentTimeMillis() - started)
                                            .put("first_token_ms", firstTokenMs)
                                            .put("stream_token_count", streamTokenCount)
                                            .put("text_delta", delta)
                                            .put("partial_chars", rawBuilder.length),
                                    )
                                }

                                override fun onDone() {
                                    done.countDown()
                                }

                                override fun onError(error: Throwable) {
                                    streamError.set(error)
                                    done.countDown()
                                }
                            }
                        )

                        if (!done.await(LITERT_PROMPT_INFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            runCatching { session.cancelProcess() }
                            throw IllegalStateException("litert_stream_timeout")
                        }
                        streamError.get()?.let { throw it }
                        generateContentMs = System.currentTimeMillis() - generateStarted
                        val raw = rawBuilder.toString()
                        val totalElapsedMs = System.currentTimeMillis() - started
                        JSONObject()
                            .put("enabled", true)
                            .put("success", raw.isNotBlank())
                            .put("generation_success", raw.isNotBlank())
                            .put("backend", label)
                            .put("stage", "complete")
                            .put("elapsed_ms", totalElapsedMs)
                            .put("attempt_elapsed_ms", System.currentTimeMillis() - attemptStarted)
                            .put("engine_create_ms", engineCreateMs)
                            .put("engine_initialize_ms", engineInitializeMs)
                            .put("session_create_ms", sessionCreateMs)
                            .put("generate_content_ms", generateContentMs)
                            .put("constrained_decoding", false)
                            .put("constrained_decoding_ms", JSONObject.NULL)
                            .put("constrained_tool_call_observed", false)
                            .put("stream_requested", true)
                            .put("streaming_api", "Session.generateContentStream")
                            .put("stream_token_count", streamTokenCount)
                            .put("first_token_ms", firstTokenMs.takeIf { it > 0L } ?: JSONObject.NULL)
                            .put("total_elapsed_ms", totalElapsedMs)
                            .put("reused_engine", reusedEngine)
                            .put("requested_model", requestedModel ?: "")
                            .put("model_path", modelPath)
                            .put("model_size_bytes", modelFile.length())
                            .put("prompt_chars", prompt.length)
                            .put("request_file", requestFile.absolutePath)
                            .put("raw_response", raw)
                            .put("attempts", attempts)
                    }
                }
                updateState(
                    section = "litert_prompt_infer",
                    data = mapOf(
                        "success" to payload.optBoolean("success"),
                        "backend" to label,
                        "stage" to "complete",
                        "elapsed_ms" to payload.optLong("elapsed_ms"),
                        "generate_content_ms" to payload.optLong("generate_content_ms"),
                        "stream_requested" to true,
                        "streaming_api" to payload.optString("streaming_api"),
                        "stream_token_count" to payload.optInt("stream_token_count"),
                        "first_token_ms" to payload.optLong("first_token_ms"),
                        "reused_engine" to reusedEngine,
                        "requested_model" to (requestedModel ?: ""),
                    ),
                )
                writeDone(payload)
                return
            } catch (error: Throwable) {
                attempts.put(
                    JSONObject()
                        .put("backend", label)
                        .put("stage", stage)
                        .put("elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("engine_create_ms", engineCreateMs)
                        .put("engine_initialize_ms", engineInitializeMs)
                        .put("session_create_ms", sessionCreateMs)
                        .put("generate_content_ms", generateContentMs)
                        .put("total_elapsed_ms", System.currentTimeMillis() - started)
                        .put("reused_engine", reusedEngine)
                        .put("stream_requested", true)
                        .put("error_type", error::class.java.name)
                        .put("error", error.message ?: "unknown")
                )
            }
        }

        val lastAttempt = attempts.optJSONObject(attempts.length() - 1)
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", false)
            .put("generation_success", false)
            .put("backend", "litert-lm:isolated:stream")
            .put("stage", "failed")
            .put("elapsed_ms", System.currentTimeMillis() - started)
            .put("stream_requested", true)
            .put("requested_model", requestedModel ?: "")
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("prompt_chars", prompt.length)
            .put("request_file", requestFile.absolutePath)
            .put("attempts", attempts)
            .put("error", lastAttempt?.optString("error") ?: "litert_prompt_stream_failed")
        writeEvent("error", JSONObject().put("payload", payload))
        writeDone(payload)
    }

    fun runLiteRtPromptInference(
        context: Context,
        requestedName: String?,
        requestedModel: String? = null,
        constrained: Boolean = false,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }

        val requestFile = resolveDebugRequestFile(
            context = context,
            requestedName = requestedName,
            defaultName = LITERT_PROMPT_INFER_DEFAULT_FILE,
        )
        if (requestFile == null || !requestFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "prompt_request_not_found")
                .put("requested_name", requestedName ?: LITERT_PROMPT_INFER_DEFAULT_FILE)
                .toString(2)
        }
        val request = runCatching { JSONObject(requestFile.readText()) }.getOrElse { error ->
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "prompt_request_invalid_json")
                .put("error_detail", error.message ?: "unknown")
                .put("request_file", requestFile.absolutePath)
                .toString(2)
        }
        val prompt = request.optString("prompt").trim()
        if (prompt.isBlank()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "prompt_request_empty")
                .put("request_file", requestFile.absolutePath)
                .toString(2)
        }
        val constrainedDecoding = constrained || request.optBoolean("constrained_decoding", false)
        val streamRequested = request.optBoolean("stream_requested", false)

        val started = System.currentTimeMillis()
        val attempts = JSONArray()
        // Session summaries run in the isolated debug process. Try GPU first for latency,
        // then fall back to CPU. If the GPU delegate crashes inside liblitertlm_jni, the
        // isolated process can die without taking down the UI process.
        val backends = isolatedBackendOptions(reusableIsolatedEngineLabel(modelPath))
        for ((label, backend) in backends) {
            var stage = "engine_create"
            val attemptStarted = System.currentTimeMillis()
            var engineCreateMs = 0L
            var engineInitializeMs = 0L
            var sessionCreateMs = 0L
            var generateContentMs = 0L
            var constrainedDecodingMs = 0L
            var reusedEngine = false
            try {
                val payload = synchronized(isolatedEngineLock) {
                    val lease = ensureIsolatedEngineLocked(context, modelPath, label, backend)
                    engineCreateMs = lease.engineCreateMs
                    engineInitializeMs = lease.engineInitializeMs
                    reusedEngine = lease.reusedEngine
                    stage = "session_create"
                    val sessionCreateStarted = System.currentTimeMillis()
                    val litertSession = if (constrainedDecoding) null else lease.engine.createSession(sessionConfigForPromptInference())
                    sessionCreateMs = System.currentTimeMillis() - sessionCreateStarted
                    if (constrainedDecoding) {
                        stage = "constrained_conversation"
                        val generateStarted = System.currentTimeMillis()
                        val constrainedPayload: JSONObject = runBlocking(Dispatchers.IO) {
                            withTimeout<JSONObject>(LITERT_PROMPT_INFER_TIMEOUT_MS) {
                                runConstrainedPromptInference(
                                    engine = lease.engine,
                                    prompt = prompt,
                                    started = started,
                                    attemptStarted = attemptStarted,
                                    label = label,
                                    modelPath = modelPath,
                                    modelFile = modelFile,
                                    requestedModel = requestedModel,
                                    promptChars = prompt.length,
                                    requestFile = requestFile,
                                    attempts = attempts,
                                    engineCreateMs = engineCreateMs,
                                    engineInitializeMs = engineInitializeMs,
                                    reusedEngine = reusedEngine,
                                    streamRequested = streamRequested,
                                )
                            }
                        }
                        constrainedDecodingMs = System.currentTimeMillis() - generateStarted
                        sessionCreateMs = constrainedPayload.optLong("session_create_ms", sessionCreateMs)
                        generateContentMs = constrainedPayload.optLong("generate_content_ms", constrainedDecodingMs)
                        constrainedPayload
                    } else {
                        stage = "generate_content"
                        requireNotNull(litertSession).use { session ->
                            val generateStarted = System.currentTimeMillis()
                            val raw = runBlocking(Dispatchers.IO) {
                                withTimeout(LITERT_PROMPT_INFER_TIMEOUT_MS) {
                                    session.generateContent(listOf(InputData.Text(prompt)))
                                }
                            }
                            generateContentMs = System.currentTimeMillis() - generateStarted
                            val totalElapsedMs = System.currentTimeMillis() - started
                            JSONObject()
                                .put("enabled", true)
                                .put("success", raw.isNotBlank())
                                .put("generation_success", raw.isNotBlank())
                                .put("backend", label)
                                .put("stage", "complete")
                                .put("elapsed_ms", totalElapsedMs)
                                .put("attempt_elapsed_ms", System.currentTimeMillis() - attemptStarted)
                                .put("engine_create_ms", engineCreateMs)
                                .put("engine_initialize_ms", engineInitializeMs)
                                .put("session_create_ms", sessionCreateMs)
                            .put("generate_content_ms", generateContentMs)
                            .put("constrained_decoding", false)
                            .put("constrained_decoding_ms", JSONObject.NULL)
                            .put("constrained_tool_call_observed", false)
                            .put("stream_requested", streamRequested)
                                .put("total_elapsed_ms", totalElapsedMs)
                                .put("reused_engine", reusedEngine)
                                .put("requested_model", requestedModel ?: "")
                                .put("model_path", modelPath)
                                .put("model_size_bytes", modelFile.length())
                                .put("prompt_chars", prompt.length)
                                .put("request_file", requestFile.absolutePath)
                                .put("raw_response", raw)
                                .put("attempts", attempts)
                        }
                    }
                }
                updateState(
                    section = "litert_prompt_infer",
                    data = mapOf(
                        "success" to payload.optBoolean("success"),
                        "backend" to label,
                        "stage" to "complete",
                        "elapsed_ms" to payload.optLong("elapsed_ms"),
                        "engine_create_ms" to engineCreateMs,
                        "engine_initialize_ms" to engineInitializeMs,
                        "session_create_ms" to sessionCreateMs,
                        "generate_content_ms" to generateContentMs,
                        "constrained_decoding" to constrainedDecoding,
                        "constrained_decoding_ms" to constrainedDecodingMs,
                        "constrained_tool_call_observed" to payload.optBoolean("constrained_tool_call_observed"),
                        "stream_requested" to streamRequested,
                        "total_elapsed_ms" to payload.optLong("total_elapsed_ms"),
                        "reused_engine" to reusedEngine,
                        "requested_model" to (requestedModel ?: ""),
                        "prompt_chars" to prompt.length,
                        "request_file" to requestFile.absolutePath,
                    ),
                )
                return payload.toString(2)
            } catch (e: Throwable) {
                attempts.put(
                    JSONObject()
                        .put("backend", label)
                        .put("stage", stage)
                        .put("elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("engine_create_ms", engineCreateMs)
                        .put("engine_initialize_ms", engineInitializeMs)
                        .put("session_create_ms", sessionCreateMs)
                        .put("generate_content_ms", generateContentMs)
                        .put("constrained_decoding", constrainedDecoding)
                        .put("constrained_decoding_ms", constrainedDecodingMs)
                        .put("constrained_tool_call_observed", false)
                        .put("stream_requested", streamRequested)
                        .put("total_elapsed_ms", System.currentTimeMillis() - started)
                        .put("reused_engine", reusedEngine)
                        .put("error_type", e::class.java.name)
                        .put("error", e.message ?: "unknown")
                )
            }
        }
        val lastAttempt = attempts.optJSONObject(attempts.length() - 1)
        val totalElapsedMs = System.currentTimeMillis() - started
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", false)
            .put("generation_success", false)
            .put("backend", "litert-lm:isolated")
            .put("stage", "failed")
            .put("elapsed_ms", totalElapsedMs)
            .put("engine_create_ms", lastAttempt?.optLong("engine_create_ms", 0L) ?: 0L)
            .put("engine_initialize_ms", lastAttempt?.optLong("engine_initialize_ms", 0L) ?: 0L)
            .put("session_create_ms", lastAttempt?.optLong("session_create_ms", 0L) ?: 0L)
            .put("generate_content_ms", lastAttempt?.optLong("generate_content_ms", 0L) ?: 0L)
            .put("constrained_decoding", constrainedDecoding)
            .put("constrained_decoding_ms", lastAttempt?.optLong("constrained_decoding_ms", 0L) ?: 0L)
            .put("constrained_tool_call_observed", false)
            .put("stream_requested", streamRequested)
            .put("total_elapsed_ms", totalElapsedMs)
            .put("reused_engine", lastAttempt?.optBoolean("reused_engine", false) ?: false)
            .put("requested_model", requestedModel ?: "")
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("prompt_chars", prompt.length)
            .put("request_file", requestFile.absolutePath)
            .put("attempts", attempts)
            .put("error", lastAttempt?.optString("error") ?: "litert_prompt_infer_failed")
        updateState(
            section = "litert_prompt_infer",
            data = mapOf(
                "success" to false,
                "backend" to "litert-lm:isolated",
                "stage" to "failed",
                "elapsed_ms" to payload.optLong("elapsed_ms"),
                "engine_create_ms" to payload.optLong("engine_create_ms"),
                "engine_initialize_ms" to payload.optLong("engine_initialize_ms"),
                "session_create_ms" to payload.optLong("session_create_ms"),
                "generate_content_ms" to payload.optLong("generate_content_ms"),
                "constrained_decoding" to payload.optBoolean("constrained_decoding"),
                "constrained_decoding_ms" to payload.optLong("constrained_decoding_ms"),
                "constrained_tool_call_observed" to payload.optBoolean("constrained_tool_call_observed"),
                "stream_requested" to payload.optBoolean("stream_requested"),
                "total_elapsed_ms" to payload.optLong("total_elapsed_ms"),
                "reused_engine" to payload.optBoolean("reused_engine"),
                "error" to payload.optString("error"),
            ),
        )
        return payload.toString(2)
    }

    fun runLiteRtImagePromptInference(
        context: Context,
        requestedImageName: String?,
        requestedModel: String? = null,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }

        val imageFile = resolveDebugRequestFile(
            context = context,
            requestedName = requestedImageName,
            defaultName = LITERT_IMAGE_PROMPT_DEFAULT_FILE,
        )
        if (imageFile == null || !imageFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "image_request_not_found")
                .put("requested_name", requestedImageName ?: LITERT_IMAGE_PROMPT_DEFAULT_FILE)
                .toString(2)
        }
        val imageBytes = runCatching { imageFile.readBytes() }.getOrElse { error ->
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "image_request_read_failed")
                .put("error_detail", error.message ?: "unknown")
                .put("image_file", imageFile.absolutePath)
                .toString(2)
        }
        val prompt = """
            Return one compact JSON object only:
            {"person_visible":true|false,"support_object_visible":true|false,"camera_view":"front|side|partial|unknown","visual_limits":[]}
            Describe only what is visible in the image. Do not judge medical risk, force, or diagnosis.
        """.trimIndent()

        val started = System.currentTimeMillis()
        val attempts = JSONArray()
        for ((label, backend) in isolatedBackendOptions(reusableIsolatedEngineLabel(modelPath))) {
            var stage = "engine_create"
            val attemptStarted = System.currentTimeMillis()
            var engineCreateMs = 0L
            var engineInitializeMs = 0L
            var sessionCreateMs = 0L
            var generateContentMs = 0L
            val reusedEngine = false
            var engine: Engine? = null
            try {
                val cacheSuffix = label.substringAfterLast(':').ifBlank { "default" }
                val createStarted = System.currentTimeMillis()
                engine = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        visionBackend = backend,
                        maxNumTokens = LITERT_PROMPT_INFER_MAX_TOKENS,
                        maxNumImages = 1,
                        cacheDir = File(context.cacheDir, "litert-lm-image-$cacheSuffix").absolutePath,
                    ),
                )
                engineCreateMs = System.currentTimeMillis() - createStarted
                stage = "engine_initialize"
                val initializeStarted = System.currentTimeMillis()
                engine.initialize()
                engineInitializeMs = System.currentTimeMillis() - initializeStarted
                stage = "session_create"
                val sessionCreateStarted = System.currentTimeMillis()
                val litertSession = engine.createSession(sessionConfigForPromptInference())
                sessionCreateMs = System.currentTimeMillis() - sessionCreateStarted
                stage = "generate_content"
                val payload = litertSession.use { session ->
                    val generateStarted = System.currentTimeMillis()
                    val raw = runBlocking(Dispatchers.IO) {
                        withTimeout(LITERT_PROMPT_INFER_TIMEOUT_MS) {
                            session.generateContent(
                                listOf(
                                    InputData.Text(prompt),
                                    InputData.Image(imageBytes),
                                )
                            )
                        }
                    }
                    generateContentMs = System.currentTimeMillis() - generateStarted
                    val totalElapsedMs = System.currentTimeMillis() - started
                    JSONObject()
                        .put("enabled", true)
                        .put("success", raw.isNotBlank())
                        .put("generation_success", raw.isNotBlank())
                        .put("backend", label)
                        .put("stage", "complete")
                        .put("elapsed_ms", totalElapsedMs)
                        .put("attempt_elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("engine_create_ms", engineCreateMs)
                        .put("engine_initialize_ms", engineInitializeMs)
                        .put("session_create_ms", sessionCreateMs)
                        .put("generate_content_ms", generateContentMs)
                        .put("total_elapsed_ms", totalElapsedMs)
                        .put("reused_engine", reusedEngine)
                        .put("requested_model", requestedModel ?: "")
                        .put("model_path", modelPath)
                        .put("model_size_bytes", modelFile.length())
                        .put("prompt_chars", prompt.length)
                        .put("image_file", imageFile.absolutePath)
                        .put("image_bytes", imageBytes.size)
                        .put("raw_response", raw)
                        .put("attempts", attempts)
                }
                updateState(
                    section = "litert_image_prompt_infer",
                    data = mapOf(
                        "success" to payload.optBoolean("success"),
                        "backend" to label,
                        "stage" to "complete",
                        "elapsed_ms" to payload.optLong("elapsed_ms"),
                        "engine_create_ms" to engineCreateMs,
                        "engine_initialize_ms" to engineInitializeMs,
                        "session_create_ms" to sessionCreateMs,
                        "generate_content_ms" to generateContentMs,
                        "total_elapsed_ms" to payload.optLong("total_elapsed_ms"),
                        "reused_engine" to reusedEngine,
                        "requested_model" to (requestedModel ?: ""),
                        "prompt_chars" to prompt.length,
                        "image_file" to imageFile.absolutePath,
                        "image_bytes" to imageBytes.size,
                    ),
                )
                return payload.toString(2)
            } catch (e: Throwable) {
                attempts.put(
                    JSONObject()
                        .put("backend", label)
                        .put("stage", stage)
                        .put("elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("engine_create_ms", engineCreateMs)
                        .put("engine_initialize_ms", engineInitializeMs)
                        .put("session_create_ms", sessionCreateMs)
                        .put("generate_content_ms", generateContentMs)
                        .put("total_elapsed_ms", System.currentTimeMillis() - started)
                        .put("reused_engine", reusedEngine)
                        .put("error_type", e::class.java.name)
                        .put("error", e.message ?: "unknown")
                )
            } finally {
                runCatching { engine?.close() }
            }
        }
        val lastAttempt = attempts.optJSONObject(attempts.length() - 1)
        val totalElapsedMs = System.currentTimeMillis() - started
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", false)
            .put("generation_success", false)
            .put("backend", "litert-lm:isolated")
            .put("stage", "failed")
            .put("elapsed_ms", totalElapsedMs)
            .put("engine_create_ms", lastAttempt?.optLong("engine_create_ms", 0L) ?: 0L)
            .put("engine_initialize_ms", lastAttempt?.optLong("engine_initialize_ms", 0L) ?: 0L)
            .put("session_create_ms", lastAttempt?.optLong("session_create_ms", 0L) ?: 0L)
            .put("generate_content_ms", lastAttempt?.optLong("generate_content_ms", 0L) ?: 0L)
            .put("total_elapsed_ms", totalElapsedMs)
            .put("reused_engine", lastAttempt?.optBoolean("reused_engine", false) ?: false)
            .put("requested_model", requestedModel ?: "")
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("prompt_chars", prompt.length)
            .put("image_file", imageFile.absolutePath)
            .put("image_bytes", imageBytes.size)
            .put("attempts", attempts)
            .put("error", lastAttempt?.optString("error") ?: "litert_image_prompt_infer_failed")
        updateState(
            section = "litert_image_prompt_infer",
            data = mapOf(
                "success" to false,
                "backend" to "litert-lm:isolated",
                "stage" to "failed",
                "elapsed_ms" to payload.optLong("elapsed_ms"),
                "engine_create_ms" to payload.optLong("engine_create_ms"),
                "engine_initialize_ms" to payload.optLong("engine_initialize_ms"),
                "session_create_ms" to payload.optLong("session_create_ms"),
                "generate_content_ms" to payload.optLong("generate_content_ms"),
                "total_elapsed_ms" to payload.optLong("total_elapsed_ms"),
                "reused_engine" to payload.optBoolean("reused_engine"),
                "error" to payload.optString("error"),
            ),
        )
        return payload.toString(2)
    }

    fun runLiteRtVisualContextInference(
        context: Context,
        requestedImageName: String? = null,
        requestedSceneName: String?,
        requestedPanelName: String?,
        requestedModel: String? = null,
        requestedTimeoutMs: String? = null,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel ?: "official")
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }

        val imageFiles = if (!requestedImageName.isNullOrBlank()) {
            listOfNotNull(
                resolveDebugRequestFile(
                    context = context,
                    requestedName = requestedImageName,
                    defaultName = "session_visual_context.jpg",
                ),
            )
        } else {
            listOfNotNull(
                resolveDebugRequestFile(
                    context = context,
                    requestedName = requestedSceneName,
                    defaultName = "session_scene_anchor.png",
                ),
                resolveDebugRequestFile(
                    context = context,
                    requestedName = requestedPanelName,
                    defaultName = "session_motionzip_panel.png",
                ),
            )
        }
            .distinctBy { it.absolutePath }
            .take(2)
        if (imageFiles.isEmpty()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "visual_context_images_not_found")
                .put("requested_scene", requestedSceneName ?: "")
                .put("requested_panel", requestedPanelName ?: "")
                .toString(2)
        }
        val imageBytes = imageFiles.mapNotNull { file ->
            runCatching { file.readBytes() }.getOrNull()
        }
        if (imageBytes.isEmpty()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "visual_context_image_read_failed")
                .put("image_files", JSONArray(imageFiles.map { it.absolutePath }))
                .toString(2)
        }
        val timeoutMs = requestedTimeoutMs
            ?.toLongOrNull()
            ?.coerceIn(2_000L, LITERT_VISUAL_CONTEXT_TIMEOUT_MS)
            ?: LITERT_VISUAL_CONTEXT_TIMEOUT_MS
        val prompt = if (imageBytes.size == 1) {
            "Output only five key=value pairs separated by semicolons. " +
                "Choose one value for each key; do not echo choices, do not use pipes. " +
                "Allowed env: indoor, outdoor, unknown. Allowed support: chair, none, unknown. " +
                "Allowed person: visible, not_visible, multiple, unknown. " +
                "Allowed overlay_readable and limited: true or false. " +
                "Example: env=outdoor;support=chair;person=visible;overlay_readable=false;limited=false. " +
                "Use the single image for scene, support, person visibility, and whether the view is limited. No prose."
        } else {
            "Output only five key=value pairs separated by semicolons. " +
                "Choose one value for each key; do not echo choices, do not use pipes. " +
                "Allowed env: indoor, outdoor, unknown. Allowed support: chair, none, unknown. " +
                "Allowed person: visible, not_visible, multiple, unknown. " +
                "Allowed overlay_readable and limited: true or false. " +
                "Example: env=outdoor;support=chair;person=visible;overlay_readable=true;limited=false. " +
                "Use image 1 for scene/support/person and image 2 for overlay readability. No prose."
        }

        val started = System.currentTimeMillis()
        val attempts = JSONArray()
        val maxNumImages = imageBytes.size.coerceAtLeast(1)
        val maxNumTokens = LITERT_PROMPT_INFER_MAX_TOKENS
        for ((label, backend) in isolatedBackendOptions(
            reusableIsolatedEngineLabel(
                modelPath = modelPath,
                maxNumTokens = maxNumTokens,
                maxNumImages = maxNumImages,
            )
        )) {
            var stage = "engine_create"
            val attemptStarted = System.currentTimeMillis()
            var engineCreateMs = 0L
            var engineInitializeMs = 0L
            var sessionCreateMs = 0L
            var generateContentMs = 0L
            var reusedEngine = false
            try {
                val payload = synchronized(isolatedEngineLock) {
                    val lease = ensureIsolatedEngineLocked(
                        context = context,
                        modelPath = modelPath,
                        backendLabel = label,
                        backend = backend,
                        maxNumTokens = maxNumTokens,
                        maxNumImages = maxNumImages,
                    )
                    engineCreateMs = lease.engineCreateMs
                    engineInitializeMs = lease.engineInitializeMs
                    reusedEngine = lease.reusedEngine
                    stage = "conversation_create"
                    val sessionCreateStarted = System.currentTimeMillis()
                    val conversation = lease.engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(
                                "You are a compact GemmaFit vision sidecar. " +
                                    "Return only the requested key=value fields."
                            ),
                            samplerConfig = SamplerConfig(
                                topK = 1,
                                topP = 0.1,
                                temperature = 0.0,
                                seed = 17,
                            ),
                            automaticToolCalling = false,
                        ),
                    )
                    sessionCreateMs = System.currentTimeMillis() - sessionCreateStarted
                    stage = "generate_content"
                    val generateStarted = System.currentTimeMillis()
                    val contents = Contents.of(
                        buildList<Content> {
                            add(Content.Text(prompt))
                            imageFiles.forEach { add(Content.ImageFile(it.absolutePath)) }
                        },
                    )
                    val raw = conversation.use { session ->
                        runBlocking(Dispatchers.IO) {
                            withTimeout(timeoutMs) {
                                session.sendMessage(contents).contents.toString()
                            }
                        }
                    }
                    generateContentMs = System.currentTimeMillis() - generateStarted
                    val totalElapsedMs = System.currentTimeMillis() - started
                    JSONObject()
                            .put("enabled", true)
                            .put("success", raw.isNotBlank())
                            .put("generation_success", raw.isNotBlank())
                            .put("backend", label)
                            .put("stage", "complete")
                            .put("elapsed_ms", totalElapsedMs)
                            .put("attempt_elapsed_ms", System.currentTimeMillis() - attemptStarted)
                            .put("engine_create_ms", engineCreateMs)
                            .put("engine_initialize_ms", engineInitializeMs)
                            .put("session_create_ms", sessionCreateMs)
                            .put("generate_content_ms", generateContentMs)
                            .put("total_elapsed_ms", totalElapsedMs)
                            .put("reused_engine", reusedEngine)
                            .put("requested_model", requestedModel ?: "official")
                            .put("model_path", modelPath)
                            .put("model_size_bytes", modelFile.length())
                            .put("prompt_chars", prompt.length)
                            .put("timeout_ms", timeoutMs)
                            .put("max_num_tokens", maxNumTokens)
                            .put("max_num_images", maxNumImages)
                            .put("image_files", JSONArray(imageFiles.map { it.absolutePath }))
                            .put("image_bytes", JSONArray(imageBytes.map { it.size }))
                            .put("raw_response", raw)
                            .put("attempts", attempts)
                }
                updateState(
                    section = "litert_visual_context_infer",
                    data = mapOf(
                        "success" to payload.optBoolean("success"),
                        "backend" to label,
                        "stage" to "complete",
                        "elapsed_ms" to payload.optLong("elapsed_ms"),
                        "engine_create_ms" to engineCreateMs,
                        "engine_initialize_ms" to engineInitializeMs,
                        "session_create_ms" to sessionCreateMs,
                        "generate_content_ms" to generateContentMs,
                        "reused_engine" to reusedEngine,
                        "requested_model" to (requestedModel ?: "official"),
                        "prompt_chars" to prompt.length,
                        "image_count" to imageBytes.size,
                        "timeout_ms" to timeoutMs,
                        "max_num_tokens" to maxNumTokens,
                        "max_num_images" to maxNumImages,
                    ),
                )
                return payload.toString(2)
            } catch (e: Throwable) {
                attempts.put(
                    JSONObject()
                        .put("backend", label)
                        .put("stage", stage)
                        .put("elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("engine_create_ms", engineCreateMs)
                        .put("engine_initialize_ms", engineInitializeMs)
                        .put("session_create_ms", sessionCreateMs)
                        .put("generate_content_ms", generateContentMs)
                        .put("total_elapsed_ms", System.currentTimeMillis() - started)
                        .put("reused_engine", reusedEngine)
                        .put("error_type", e::class.java.name)
                        .put("error", e.message ?: "unknown")
                )
            }
        }
        val lastAttempt = attempts.optJSONObject(attempts.length() - 1)
        val totalElapsedMs = System.currentTimeMillis() - started
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", false)
            .put("generation_success", false)
            .put("backend", "litert-lm:isolated")
            .put("stage", "failed")
            .put("elapsed_ms", totalElapsedMs)
            .put("engine_create_ms", lastAttempt?.optLong("engine_create_ms", 0L) ?: 0L)
            .put("engine_initialize_ms", lastAttempt?.optLong("engine_initialize_ms", 0L) ?: 0L)
            .put("session_create_ms", lastAttempt?.optLong("session_create_ms", 0L) ?: 0L)
            .put("generate_content_ms", lastAttempt?.optLong("generate_content_ms", 0L) ?: 0L)
            .put("total_elapsed_ms", totalElapsedMs)
            .put("requested_model", requestedModel ?: "official")
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("prompt_chars", prompt.length)
            .put("timeout_ms", timeoutMs)
            .put("max_num_tokens", maxNumTokens)
            .put("max_num_images", maxNumImages)
            .put("image_files", JSONArray(imageFiles.map { it.absolutePath }))
            .put("image_bytes", JSONArray(imageBytes.map { it.size }))
            .put("attempts", attempts)
            .put("error", lastAttempt?.optString("error") ?: "litert_visual_context_infer_failed")
        updateState(
            section = "litert_visual_context_infer",
            data = mapOf(
                "success" to false,
                "backend" to "litert-lm:isolated",
                "stage" to "failed",
                "elapsed_ms" to payload.optLong("elapsed_ms"),
                "engine_create_ms" to payload.optLong("engine_create_ms"),
                "engine_initialize_ms" to payload.optLong("engine_initialize_ms"),
                "session_create_ms" to payload.optLong("session_create_ms"),
                "generate_content_ms" to payload.optLong("generate_content_ms"),
                "error" to payload.optString("error"),
            ),
        )
        return payload.toString(2)
    }

    fun runLiteRtPrewarm(
        context: Context,
        requestedModel: String? = null,
        requestedMaxNumImages: String? = null,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val started = System.currentTimeMillis()
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:isolated")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }
        val maxNumImages = requestedMaxNumImages
            ?.toIntOrNull()
            ?.coerceIn(0, 2)
            ?: 0
        val attempts = JSONArray()
        for ((label, backend) in isolatedBackendOptions(
            reusableIsolatedEngineLabel(
                modelPath = modelPath,
                maxNumImages = maxNumImages,
            )
        )) {
            val attemptStarted = System.currentTimeMillis()
            var stage = "engine_create"
            try {
                val payload = synchronized(isolatedEngineLock) {
                    val lease = ensureIsolatedEngineLocked(
                        context = context,
                        modelPath = modelPath,
                        backendLabel = label,
                        backend = backend,
                        maxNumImages = maxNumImages,
                    )
                    stage = "complete"
                    JSONObject()
                        .put("enabled", true)
                        .put("success", true)
                        .put("backend", label)
                        .put("stage", "complete")
                        .put("engine_create_ms", lease.engineCreateMs)
                        .put("engine_initialize_ms", lease.engineInitializeMs)
                        .put("session_create_ms", 0L)
                        .put("ttl_ms", ISOLATED_ENGINE_TTL_MS)
                        .put("reused_engine", lease.reusedEngine)
                        .put("max_num_images", maxNumImages)
                        .put("requested_model", requestedModel ?: "")
                        .put("model_path", modelPath)
                        .put("model_size_bytes", modelFile.length())
                        .put("elapsed_ms", System.currentTimeMillis() - started)
                        .put("attempts", attempts)
                }
                updateState(
                    section = "litert_prewarm",
                    data = mapOf(
                        "success" to true,
                        "backend" to label,
                        "stage" to "complete",
                        "engine_create_ms" to payload.optLong("engine_create_ms"),
                        "engine_initialize_ms" to payload.optLong("engine_initialize_ms"),
                        "ttl_ms" to ISOLATED_ENGINE_TTL_MS,
                        "reused_engine" to payload.optBoolean("reused_engine"),
                        "max_num_images" to maxNumImages,
                        "requested_model" to (requestedModel ?: ""),
                        "elapsed_ms" to payload.optLong("elapsed_ms"),
                    ),
                )
                record(
                    category = "coach_summary",
                    message = "litert_prewarm_complete",
                    data = mapOf(
                        "backend" to label,
                        "engine_initialize_ms" to payload.optLong("engine_initialize_ms"),
                        "reused_engine" to payload.optBoolean("reused_engine"),
                        "max_num_images" to maxNumImages,
                    ),
                )
                return payload.toString(2)
            } catch (e: Throwable) {
                attempts.put(
                    JSONObject()
                        .put("backend", label)
                        .put("stage", stage)
                        .put("elapsed_ms", System.currentTimeMillis() - attemptStarted)
                        .put("error_type", e::class.java.name)
                        .put("error", e.message ?: "unknown"),
                )
            }
        }
        val lastAttempt = attempts.optJSONObject(attempts.length() - 1)
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", false)
            .put("backend", "litert-lm:isolated")
            .put("stage", "failed")
            .put("ttl_ms", ISOLATED_ENGINE_TTL_MS)
            .put("reused_engine", false)
            .put("max_num_images", maxNumImages)
            .put("requested_model", requestedModel ?: "")
            .put("model_path", modelPath)
            .put("model_size_bytes", modelFile.length())
            .put("elapsed_ms", System.currentTimeMillis() - started)
            .put("attempts", attempts)
            .put("error", lastAttempt?.optString("error") ?: "litert_prewarm_failed")
        updateState(
            section = "litert_prewarm",
            data = mapOf(
                "success" to false,
                "backend" to "litert-lm:isolated",
                "stage" to "failed",
                "elapsed_ms" to payload.optLong("elapsed_ms"),
                "max_num_images" to maxNumImages,
                "error" to payload.optString("error"),
            ),
        )
        record(
            category = "coach_summary",
            message = "litert_prewarm_failed",
            data = mapOf("error" to payload.optString("error")),
        )
        return payload.toString(2)
    }

    fun runMotionZipModelEquivalence(
        context: Context,
        requestedName: String? = null,
        requestedModel: String? = null,
        requestedBackend: String? = null,
        requestedMaxTokens: Int? = null,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app, requestedModel)
        val modelFile = modelPath?.let { File(it) }
        if (modelPath == null || modelFile == null || !modelFile.exists()) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:raw")
                .put("error", "litert_model_file_not_found")
                .toString(2)
        }
        val promptFile = resolveMotionZipEquivalencePromptFile(context, requestedName)
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:raw")
                .put("error", "model_prompt_pair_not_found")
                .put("requested_file", requestedName ?: MODEL_EQUIVALENCE_DEFAULT_FILE)
                .put("expected_locations", JSONArray(motionZipEquivalencePromptDirs(context).map { it.absolutePath }))
                .toString(2)
        val cases = runCatching { loadMotionZipEquivalenceCases(promptFile) }.getOrElse { error ->
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:raw")
                .put("error", "model_prompt_pair_parse_failed")
                .put("error_detail", error.message ?: "unknown")
                .put("prompt_file", promptFile.absolutePath)
                .toString(2)
        }
        if (cases.size < 2) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:raw")
                .put("error", "model_prompt_pair_needs_two_cases")
                .put("case_count", cases.size)
                .put("prompt_file", promptFile.absolutePath)
                .toString(2)
        }
        val maxTokens = requestedMaxTokens
            ?.coerceIn(128, LITERT_PROMPT_INFER_MAX_TOKENS)
            ?: MODEL_EQUIVALENCE_MAX_TOKENS

        val started = System.currentTimeMillis()
        return try {
            val payload = runBlocking(Dispatchers.IO) {
                val attempts = JSONArray()
                var selectedPayload: JSONObject? = null
                val forcedBackend = isForcedMotionZipEquivalenceBackend(requestedBackend)
                for ((backendLabel, backend) in motionZipEquivalenceBackendOptions(requestedBackend)) {
                    val attemptPayload = runMotionZipEquivalenceBackendAttempt(
                        context = context,
                        modelFile = modelFile,
                        promptFile = promptFile,
                        cases = cases,
                        backendLabel = backendLabel,
                        backend = backend,
                        maxTokens = maxTokens,
                    )
                    attempts.put(attemptPayload)
                    if (attemptPayload.optBoolean("success") || forcedBackend) {
                        selectedPayload = attemptPayload
                        break
                    }
                }
                (selectedPayload ?: attempts.optJSONObject(attempts.length() - 1) ?: JSONObject()
                    .put("enabled", true)
                    .put("success", false)
                    .put("overall_pass", false)
                    .put("backend", "litert-lm:raw")
                    .put("error", "motionzip_model_equivalence_no_backend_attempts"))
                    .put("attempts", attempts)
            }
            payload
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("requested_model", requestedModel ?: "")
                .put("requested_backend", requestedBackend ?: "auto")
                .put("max_tokens", maxTokens)
                .put("model_path", modelPath)
                .put("model_size_bytes", modelFile.length())
            updateState(
                section = "motionzip_model_equivalence",
                data = mapOf(
                    "success" to payload.optBoolean("success"),
                    "backend" to payload.optString("backend"),
                    "error" to payload.optString("error"),
                    "overall_pass" to payload.optBoolean("overall_pass"),
                    "case_count" to cases.size,
                    "elapsed_ms" to payload.optLong("elapsed_ms"),
                    "requested_model" to (requestedModel ?: ""),
                    "requested_backend" to (requestedBackend ?: "auto"),
                    "max_tokens" to maxTokens,
                    "model_path" to modelPath,
                    "prompt_file" to promptFile.absolutePath,
                ),
            )
            record(
                category = "motionzip_model_equivalence",
                message = "completed",
                data = mapOf(
                    "success" to payload.optBoolean("success"),
                    "overall_pass" to payload.optBoolean("overall_pass"),
                    "elapsed_ms" to payload.optLong("elapsed_ms"),
                    "case_count" to cases.size,
                    "requested_model" to (requestedModel ?: ""),
                    "requested_backend" to (requestedBackend ?: "auto"),
                    "max_tokens" to maxTokens,
                ),
            )
            payload.toString(2)
        } catch (e: Throwable) {
            JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("backend", "litert-lm:raw")
                .put("error_type", e::class.java.name)
                .put("error", e.message ?: "motionzip_model_equivalence_failed")
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("requested_model", requestedModel ?: "")
                .put("requested_backend", requestedBackend ?: "auto")
                .put("max_tokens", maxTokens)
                .put("model_path", modelPath)
                .put("model_size_bytes", modelFile.length())
                .put("prompt_file", promptFile.absolutePath)
                .toString(2)
        }
    }

    private suspend fun runMotionZipEquivalenceBackendAttempt(
        context: Context,
        modelFile: File,
        promptFile: File,
        cases: List<MotionZipEquivalenceCase>,
        backendLabel: String,
        backend: Backend,
        maxTokens: Int,
    ): JSONObject = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var stage = "engine_create"
        var engineCreateMs = 0L
        var engineInitializeMs = 0L
        var reusedEngine = false
        try {
            val lease = synchronized(isolatedEngineLock) {
                ensureIsolatedEngineLocked(
                    context = context,
                    modelPath = modelFile.absolutePath,
                    backendLabel = backendLabel,
                    backend = backend,
                    maxNumTokens = maxTokens,
                )
            }
            engineCreateMs = lease.engineCreateMs
            engineInitializeMs = lease.engineInitializeMs
            reusedEngine = lease.reusedEngine
            val results = JSONArray()
            val understandings = LinkedHashMap<String, JSONObject>()
            for (case in cases) {
                stage = "session_create:${case.id}"
                lease.engine.createSession(
                    SessionConfig(
                        SamplerConfig(
                            topK = 1,
                            topP = 0.1,
                            temperature = 0.0,
                            seed = 23,
                        )
                    )
                ).use { session ->
                    stage = "generate_content:${case.id}"
                    val raw = withTimeout(MODEL_EQUIVALENCE_TIMEOUT_MS) {
                        session.generateContent(listOf(InputData.Text(motionZipEquivalencePrompt(case))))
                    }
                    val parsed = parseFirstJsonObject(raw)
                    if (parsed != null) {
                        understandings[case.id] = parsed
                    }
                    results.put(
                        JSONObject()
                            .put("id", case.id)
                            .put("success", parsed != null)
                            .put("understanding", parsed ?: JSONObject.NULL)
                            .put("raw_response", raw.take(4000))
                    )
                }
            }
            val dense = understandings["dense_frame_by_frame"]
            val motionZip = understandings["motionzip_compressed"]
            val comparison = compareMotionZipModelUnderstandings(dense, motionZip)
            return@withContext JSONObject()
                .put("enabled", true)
                .put("success", dense != null && motionZip != null)
                .put("overall_pass", comparison.optBoolean("overall_pass"))
                .put("backend", backendLabel)
                .put("stage", "complete")
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("engine_create_ms", engineCreateMs)
                .put("engine_initialize_ms", engineInitializeMs)
                .put("reused_engine", reusedEngine)
                .put("prompt_file", promptFile.absolutePath)
                .put("prompt_file_bytes", promptFile.length())
                .put("max_tokens", maxTokens)
                .put("case_count", cases.size)
                .put("cases", results)
                .put("comparison", comparison)
        } catch (e: Throwable) {
            return@withContext JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("overall_pass", false)
                .put("backend", backendLabel)
                .put("stage", stage)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("engine_create_ms", engineCreateMs)
                .put("engine_initialize_ms", engineInitializeMs)
                .put("reused_engine", reusedEngine)
                .put("prompt_file", promptFile.absolutePath)
                .put("max_tokens", maxTokens)
                .put("error_type", e::class.java.name)
                .put("error", e.message ?: "unknown")
        }
    }

    private fun motionZipEquivalenceSystemInstruction(): String {
        return (
            "You are GemmaFit's motion-evidence extractor. Use the provided tool exactly once. " +
                "Extract only activity, states, event frames, velocity band/peak, confidence floor, " +
                "low confidence reason, limits, and evidence refs from the input. Do not diagnose, " +
                "predict fall risk, infer force, GRF, EMG, muscle activation, or use raw-video assumptions."
            )
    }

    private fun motionZipEquivalencePrompt(case: MotionZipEquivalenceCase): String {
        return buildString {
            append("<|turn>system\n")
            append(motionZipEquivalenceSystemInstruction())
            append(" Return JSON only. Do not include markdown.\n")
            append("<|turn>user\n")
            append("CASE_ID: ")
            append(case.id)
            append('\n')
            append("Return exactly one JSON object with this shape: ")
            append("{\"activity\":\"...\",\"states\":[\"...\"],\"events\":[{\"state\":\"...\",\"frame\":0,\"reason\":\"...\"}],")
            append("\"velocity\":{\"band\":\"...\",\"peak_deg_s\":0},\"confidence\":{\"floor\":0,\"low_confidence_reason\":\"...\"},")
            append("\"limits\":[\"...\"],\"evidence_refs\":[\"...\"]}\n")
            append("Use only this JSON evidence:\n")
            append(case.prompt.toString())
            append("\n<|turn>model\n")
        }
    }

    private fun recordKeyMotionUnderstandingTool(toolExecutions: MutableList<JSONObject>): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            return JSONObject()
                .put("name", "record_key_motion_understanding")
                .put("description", "Records the model's key motion understanding from one evidence input.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties",
                            JSONObject()
                                .put("activity", JSONObject().put("type", "STRING"))
                                .put(
                                    "states",
                                    JSONObject()
                                        .put("type", "ARRAY")
                                        .put("items", JSONObject().put("type", "STRING"))
                                )
                                .put(
                                    "events",
                                    JSONObject()
                                        .put("type", "ARRAY")
                                        .put(
                                            "items",
                                            JSONObject()
                                                .put("type", "OBJECT")
                                                .put(
                                                    "properties",
                                                    JSONObject()
                                                        .put("state", JSONObject().put("type", "STRING"))
                                                        .put("frame", JSONObject().put("type", "NUMBER"))
                                                        .put("reason", JSONObject().put("type", "STRING"))
                                                )
                                        )
                                )
                                .put(
                                    "velocity",
                                    JSONObject()
                                        .put("type", "OBJECT")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put("band", JSONObject().put("type", "STRING"))
                                                .put("peak_deg_s", JSONObject().put("type", "NUMBER"))
                                        )
                                )
                                .put(
                                    "confidence",
                                    JSONObject()
                                        .put("type", "OBJECT")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put("floor", JSONObject().put("type", "NUMBER"))
                                                .put("low_confidence_reason", JSONObject().put("type", "STRING"))
                                        )
                                )
                                .put(
                                    "limits",
                                    JSONObject()
                                        .put("type", "ARRAY")
                                        .put("items", JSONObject().put("type", "STRING"))
                                )
                                .put(
                                    "evidence_refs",
                                    JSONObject()
                                        .put("type", "ARRAY")
                                        .put("items", JSONObject().put("type", "STRING"))
                                )
                        )
                        .put(
                            "required",
                            JSONArray(
                                listOf(
                                    "activity",
                                    "states",
                                    "events",
                                    "velocity",
                                    "confidence",
                                    "limits",
                                    "evidence_refs",
                                )
                            )
                        )
                )
                .toString()
        }

        override fun execute(paramsJsonString: String): String {
            val params = runCatching { JSONObject(paramsJsonString) }.getOrElse { JSONObject() }
            toolExecutions += JSONObject()
                .put("function", "record_key_motion_understanding")
                .put("params", params)
            return JSONObject()
                .put("accepted", true)
                .put("function", "record_key_motion_understanding")
                .toString()
        }
    }

    private fun motionZipUnderstandingFromToolResult(
        executions: List<JSONObject>,
        toolCalls: JSONArray,
        responseContents: String,
    ): JSONObject? {
        val executed = executions.lastOrNull()
            ?.optJSONObject("params")
        if (executed != null && executed.length() > 0) return executed
        for (index in toolCalls.length() - 1 downTo 0) {
            val call = toolCalls.optJSONObject(index) ?: continue
            val args = call.optJSONObject("arguments")
            if (args != null && args.length() > 0) return args
        }
        return parseFirstJsonObject(responseContents)
    }

    private fun compareMotionZipModelUnderstandings(
        dense: JSONObject?,
        motionZip: JSONObject?,
    ): JSONObject {
        val checks = JSONArray()

        fun addCheck(
            key: String,
            denseValue: Any?,
            motionZipValue: Any?,
            pass: Boolean,
            extra: Map<String, Any?> = emptyMap(),
        ) {
            val obj = JSONObject()
                .put("key", key)
                .put("dense", denseValue ?: JSONObject.NULL)
                .put("motionzip", motionZipValue ?: JSONObject.NULL)
                .put("pass", pass)
            extra.forEach { (name, value) -> obj.put(name, value ?: JSONObject.NULL) }
            checks.put(obj)
        }

        if (dense == null || motionZip == null) {
            return JSONObject()
                .put("overall_pass", false)
                .put("checks", checks)
                .put("error", "missing_model_understanding")
        }

        val denseActivity = dense.optString("activity")
        val motionActivity = motionZip.optString("activity")
        addCheck("activity", denseActivity, motionActivity, denseActivity == motionActivity)

        val denseStates = normalizedStringArray(dense.optJSONArray("states"))
        val motionStates = normalizedStringArray(motionZip.optJSONArray("states"))
        addCheck("states", denseStates, motionStates, denseStates.toString() == motionStates.toString())

        val denseEvents = dense.optJSONArray("events") ?: JSONArray()
        val motionEvents = motionZip.optJSONArray("events") ?: JSONArray()
        addCheck("event_count", denseEvents.length(), motionEvents.length(), denseEvents.length() == motionEvents.length())

        val denseFrames = eventFrames(denseEvents)
        val motionFrames = eventFrames(motionEvents)
        val maxDiff = denseFrames.zip(motionFrames).maxOfOrNull { (left, right) -> kotlin.math.abs(left - right) }
        addCheck(
            key = "event_frames",
            denseValue = JSONArray(denseFrames),
            motionZipValue = JSONArray(motionFrames),
            pass = maxDiff != null && maxDiff <= 12,
            extra = mapOf("max_frame_diff" to maxDiff, "tolerance_frames" to 12),
        )

        val denseVelocity = dense.optJSONObject("velocity") ?: JSONObject()
        val motionVelocity = motionZip.optJSONObject("velocity") ?: JSONObject()
        val denseBand = denseVelocity.optString("band")
        val motionBand = motionVelocity.optString("band")
        addCheck("velocity_band", denseBand, motionBand, denseBand == motionBand)
        val densePeak = denseVelocity.optDouble("peak_deg_s", Double.NaN)
        val motionPeak = motionVelocity.optDouble("peak_deg_s", Double.NaN)
        val peakRelativeError = if (densePeak.isFinite() && densePeak != 0.0 && motionPeak.isFinite()) {
            kotlin.math.abs(densePeak - motionPeak) / densePeak
        } else {
            Double.POSITIVE_INFINITY
        }
        addCheck(
            key = "velocity_peak",
            denseValue = densePeak.takeIf { it.isFinite() },
            motionZipValue = motionPeak.takeIf { it.isFinite() },
            pass = peakRelativeError <= 0.05,
            extra = mapOf("relative_error" to peakRelativeError.takeIf { it.isFinite() }, "tolerance_ratio" to 0.05),
        )

        val denseConfidence = dense.optJSONObject("confidence") ?: JSONObject()
        val motionConfidence = motionZip.optJSONObject("confidence") ?: JSONObject()
        val denseFloor = denseConfidence.optDouble("floor", Double.NaN)
        val motionFloor = motionConfidence.optDouble("floor", Double.NaN)
        val floorError = if (denseFloor.isFinite() && motionFloor.isFinite()) {
            kotlin.math.abs(denseFloor - motionFloor)
        } else {
            Double.POSITIVE_INFINITY
        }
        addCheck(
            key = "confidence_floor",
            denseValue = denseFloor.takeIf { it.isFinite() },
            motionZipValue = motionFloor.takeIf { it.isFinite() },
            pass = floorError <= 0.02,
            extra = mapOf("absolute_error" to floorError.takeIf { it.isFinite() }, "tolerance" to 0.02),
        )
        val denseReason = denseConfidence.optString("low_confidence_reason")
        val motionReason = motionConfidence.optString("low_confidence_reason")
        addCheck("low_confidence_reason", denseReason, motionReason, denseReason == motionReason)

        val passCount = (0 until checks.length()).count { checks.optJSONObject(it)?.optBoolean("pass") == true }
        return JSONObject()
            .put("overall_pass", passCount == checks.length())
            .put("pass_count", passCount)
            .put("total", checks.length())
            .put("pass_rate", if (checks.length() > 0) passCount.toDouble() / checks.length() else 0.0)
            .put("checks", checks)
    }

    private fun normalizedStringArray(arr: JSONArray?): JSONArray {
        val values = mutableListOf<String>()
        if (arr != null) {
            for (index in 0 until arr.length()) {
                val value = arr.optString(index).trim()
                if (value.isNotBlank()) values += value
            }
        }
        return JSONArray(values.distinct().sorted())
    }

    private fun eventFrames(events: JSONArray): List<Int> {
        val frames = mutableListOf<Int>()
        for (index in 0 until events.length()) {
            val obj = events.optJSONObject(index) ?: continue
            frames += obj.optInt("frame")
        }
        return frames
    }

    private data class MotionZipEquivalenceCase(
        val id: String,
        val prompt: JSONObject,
    )

    private fun loadMotionZipEquivalenceCases(file: File): List<MotionZipEquivalenceCase> {
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val obj = JSONObject(line)
                MotionZipEquivalenceCase(
                    id = obj.optString("id"),
                    prompt = obj.optJSONObject("prompt") ?: JSONObject(),
                )
            }
            .filter { it.id.isNotBlank() && it.prompt.length() > 0 }
    }

    private fun resolveMotionZipEquivalencePromptFile(
        context: Context,
        requestedName: String?,
    ): File? {
        val name = requestedName?.takeIf { it.isNotBlank() }?.let { File(it).name }
            ?: MODEL_EQUIVALENCE_DEFAULT_FILE
        return motionZipEquivalencePromptDirs(context)
            .map { File(it, name) }
            .distinctBy { it.absolutePath }
            .firstOrNull { it.exists() && it.canRead() && it.length() > 0L }
    }

    private fun motionZipEquivalencePromptDirs(context: Context): List<File> {
        return buildList {
            context.getExternalFilesDir(null)?.let { add(it) }
            add(context.filesDir)
            add(File("/storage/emulated/0/Android/data/com.gemmafit/files"))
            add(File("/sdcard/Android/data/com.gemmafit/files"))
        }.distinctBy { it.absolutePath }
    }

    private fun resolveDebugRequestFile(
        context: Context,
        requestedName: String?,
        defaultName: String,
    ): File? {
        val name = requestedName?.takeIf { it.isNotBlank() }?.let { File(it).name } ?: defaultName
        return buildList {
            add(debugDir(context))
            add(context.filesDir)
            context.getExternalFilesDir(null)?.let { add(it) }
            add(File("/storage/emulated/0/Android/data/com.gemmafit/files"))
            add(File("/sdcard/Android/data/com.gemmafit/files"))
        }
            .distinctBy { it.absolutePath }
            .map { File(it, name) }
            .firstOrNull { it.exists() && it.canRead() && it.length() > 0L }
    }

    fun runModelInvocationSmoke(): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        val sampleWindow = MotionFeatureWindow(
            windowId = "debug.motion.rep.1",
            trigger = "REP_COMPLETED",
            windowMs = 2_400L,
            exercise = "chair_sit_to_stand",
            source = listOf("debug_smoke", "pose_sequence"),
            features = MotionFeatureValues(
                hipVerticalDisplacement = null,
                kneeAngleMin = 82f,
                kneeAngleMax = 168f,
                primaryAngleMin = 82f,
                primaryAngleMax = 168f,
                rangeOfMotionDeg = 86f,
                repDurationMs = 2_400L,
                peakVelocityDegS = 220f,
                velocityPeak = "low",
                stabilizationMs = 700L,
                confidenceFloor = 0.86f,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "controlled",
                phaseSequenceEstimate = listOf("top", "descent", "bottom", "ascent", "top"),
                repCompleted = true,
                supportPattern = "double_stance",
            ),
            evidenceRefs = listOf(
                "metric.motion.rep_duration",
                "metric.motion.rom",
                "metric.motion.peak_velocity",
                "metric.motion.confidence_floor",
            ),
            limits = listOf(
                "derived_from_single_camera_pose",
                "no_force_or_grf",
                "no_emg_or_muscle_activation",
            ),
        )
        val requests = listOf(
            "live_frame" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.LIVE_FRAME,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
            ),
            "live_frame_multimodal_enabled" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.LIVE_FRAME,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = true,
            ),
            "clean_rep" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
            ),
            "warning_rep" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            ),
            "predicted_tracking" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                personTrackingState = PersonTrackingState.PREDICTED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            ),
            "left_activity_area" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.USER_LEFT_ACTIVITY_AREA,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            ),
            "no_response_after_cue" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.NO_RESPONSE_AFTER_CUE,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = sampleWindow.features.confidenceFloor,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            ),
            "session_model_disabled" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.SESSION_ENDED,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = 1f,
                needsLanguageExplanation = true,
                deviceState = ModelDeviceState(modelDisabled = true),
            ),
            "session_ended_multimodal_available" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.SESSION_ENDED,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = 1f,
                needsLanguageExplanation = true,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = true,
            ),
            "caregiver_export_multimodal_available" to ModelInvocationRequest(
                trigger = ModelInvocationTrigger.CAREGIVER_EXPORT,
                personTrackingState = PersonTrackingState.OBSERVED,
                confidenceFloor = 1f,
                needsLanguageExplanation = true,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = true,
            ),
        )
        val sampleLayer2Output = Layer2Output(
            timestampMs = 3_200L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "chair_sit_to_stand",
                confidence = 0.91f,
                source = listOf("debug_smoke", "derived_motion_features"),
            ),
            phase = Layer2Phase.STANDING_STABILIZED,
            event = Layer2Event.REP_COMPLETED,
            confidence = 0.9f,
            repCount = 1,
            rulePolicy = Layer2RulePolicy.controlledStrength(),
            evidenceRefs = listOf(
                "layer2.activity.chair_sit_to_stand",
                "layer2.phase.standing_stabilized",
                "layer2.event.rep_completed",
            ),
        )
        val motionZipPacket = MotionZipPacketBuilder.fromRepEvent(
            motionFeatureWindow = sampleWindow,
            layer2Output = sampleLayer2Output,
            framesKept = 24,
        )
        val cases = JSONArray()
        requests.forEach { (name, request) ->
            val plan = ModelInvocationScheduler.plan(request)
            cases.put(
                JSONObject()
                    .put("name", name)
                    .put("request", request.toDebugMap().toJsonObject())
                    .put("plan", plan.toDebugMap().toJsonObject())
            )
        }
        val payload = JSONObject()
            .put("enabled", true)
            .put("success", true)
            .put("motion_feature_window", sampleWindow.toDebugMap().toJsonObject())
            .put("motion_zip_packet", motionZipPacket.toDebugMap().toJsonObject())
            .put("cases", cases)
        updateState(
            section = "model_invocation_smoke",
            data = mapOf(
                "success" to true,
                "motion_feature_window" to sampleWindow.toDebugMap(),
                "motion_zip_packet" to motionZipPacket.toDebugMap(),
                "cases" to requests.map { (name, request) ->
                    mapOf(
                        "name" to name,
                        "request" to request.toDebugMap(),
                        "plan" to ModelInvocationScheduler.plan(request).toDebugMap(),
                    )
                },
            ),
        )
        record(
            category = "model_invocation",
            message = "smoke_completed",
            data = mapOf("case_count" to requests.size),
        )
        return payload.toString(2)
    }

    fun runLayer2Smoke(): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        val cases = JSONArray()
        val caseMaps = mutableListOf<Map<String, Any?>>()

        fun addCase(
            name: String,
            frames: List<Layer2FrameFeatures>,
            expectEvent: Layer2Event? = null,
        ) {
            val interpreter = Layer2TemporalInterpreter()
            val outputs = frames.map { interpreter.update(it) }
            val finalOutput = outputs.last()
            val success = expectEvent?.let { finalOutput.event == it } ?: true
            val caseMap = mapOf(
                "name" to name,
                "success" to success,
                "input_summary" to mapOf(
                    "frame_count" to frames.size,
                    "activity_hint" to frames.last().activityHint,
                    "metrics" to frames.last().metrics.keys.sorted(),
                    "confidence_floor" to frames.minOf { it.poseConfidence.toDouble() },
                    "person_tracking_states" to frames.map { it.personTrackingState.name }.distinct(),
                ),
                "phase_sequence" to outputs.map { it.phase.wireName },
                "events" to outputs.filter { it.event != Layer2Event.NONE }.map { it.event.wireName },
                "final_output" to finalOutput.toDebugMap(),
            )
            cases.put(caseMap.toJsonObject())
            caseMaps += caseMap
        }

        addCase(
            name = "chair_sit_to_stand_rep",
            expectEvent = Layer2Event.REP_COMPLETED,
            frames = listOf(175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f, 172f)
                .mapIndexed { index, angle ->
                    Layer2FrameFeatures(
                        timestampMs = index * 400L,
                        activityHint = "chair_sit_to_stand",
                        activityConfidence = 0.91f,
                        poseConfidence = 0.86f,
                        metrics = mapOf("knee_angle" to angle),
                    )
                },
        )
        addCase(
            name = "supported_squat_rep_monitor_only",
            expectEvent = Layer2Event.REP_COMPLETED,
            frames = listOf(175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f, 172f)
                .mapIndexed { index, angle ->
                    Layer2FrameFeatures(
                        timestampMs = index * 400L,
                        activityHint = "supported_squat",
                        activityConfidence = 0.9f,
                        poseConfidence = 0.86f,
                        metrics = mapOf("knee_angle" to angle, "support_contact_proxy" to 1f),
                    )
                },
        )
        addCase(
            name = "balance_hold_completed",
            expectEvent = Layer2Event.BALANCE_HOLD_COMPLETED,
            frames = (0..5).map { second ->
                Layer2FrameFeatures(
                    timestampMs = second * 1_000L,
                    activityHint = "balance_hold",
                    activityConfidence = 0.9f,
                    poseConfidence = 0.9f,
                    metrics = mapOf("sway_norm" to 0.03f),
                )
            },
        )
        addCase(
            name = "non_senior_lunge_demoted",
            frames = listOf(170f, 148f, 112f, 104f, 132f)
                .mapIndexed { index, angle ->
                    Layer2FrameFeatures(
                        timestampMs = index * 160L,
                        activityHint = "lunge",
                        activityConfidence = 0.84f,
                        poseConfidence = 0.88f,
                        metrics = mapOf("front_knee_angle" to angle),
                    )
                },
        )
        addCase(
            name = "non_senior_basketball_demoted",
            frames = listOf(
                mapOf("knee_angle" to 170f),
                mapOf("knee_angle" to 145f),
                mapOf("knee_angle" to 162f),
                mapOf("knee_angle" to 168f, "shoulder_angle" to 132f),
                mapOf("knee_angle" to 168f, "shoulder_angle" to 138f, "elbow_angle" to 166f),
            ).mapIndexed { index, metrics ->
                Layer2FrameFeatures(
                    timestampMs = index * 120L,
                    activityHint = "basketball_jump_shot",
                    activityConfidence = 0.86f,
                    poseConfidence = 0.9f,
                    metrics = metrics,
                )
            },
        )
        addCase(
            name = "predicted_tracking_abstain",
            expectEvent = Layer2Event.ABSTAIN,
            frames = listOf(
                Layer2FrameFeatures(
                    timestampMs = 0L,
                    activityHint = "chair_sit_to_stand",
                    poseConfidence = 0.9f,
                    personTrackingState = PersonTrackingState.PREDICTED,
                    metrics = mapOf("knee_angle" to 170f),
                )
            ),
        )

        val payload = JSONObject()
            .put("enabled", true)
            .put("success", caseMaps.all { it["success"] == true })
            .put("cases", cases)
        updateState(
            section = "layer2_event",
            data = mapOf(
                "success" to payload.optBoolean("success"),
                "cases" to caseMaps,
            ),
        )
        record(
            category = "layer2_event",
            message = "smoke_completed",
            data = mapOf("case_count" to caseMaps.size, "success" to payload.optBoolean("success")),
        )
        return payload.toString(2)
    }

    fun runFunctionGemmaNoToolsSmoke(context: Context): String {
        return runFunctionGemmaIsolatedSmoke(
            context = context,
            mode = "no_tools",
            useTool = false,
        )
    }

    fun runVideoRealtimeSmoke(
        context: Context,
        requestedName: String? = null,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val app = context.applicationContext as? Application
            ?: return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "application_context_unavailable")
                .toString(2)
        val videoFile = resolveVideoRealtimeSmokeFile(context, requestedName)
        if (videoFile == null) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("error", "test_video_not_found")
                .put("requested_name", requestedName ?: JSONObject.NULL)
                .put("expected_locations", JSONArray(videoRealtimeSmokeSearchDirs(context).map { it.absolutePath }))
                .toString(2)
        }

        val started = System.currentTimeMillis()
        val frameSamples = JSONArray()
        val poseMs = mutableListOf<Long>()
        val convertMs = mutableListOf<Long>()
        val extractMs = mutableListOf<Long>()
        val sourceCounts = linkedMapOf<String, Int>()
        val temporalAnalyzer = TemporalMotionAnalyzer()
        var analyzedFrames = 0
        var poseHits = 0
        var landmark33Frames = 0
        var firstPoseFrame: Int? = null
        var firstPoseTimestampMs: Long? = null
        var lastTimestampMs: Long? = null
        var timestampMonotonic = true
        var visibilitySum = 0.0
        var visibilityFrames = 0
        var maxVelocityDegS = 0f
        var latestWarning: QualityFlag? = null
        var lastTemporal = TemporalMotionAnalyzer.Result()
        var nativeAnalyzedFrames = 0
        var nativeGateBlockedFrames = 0
        var nativeLowConfidenceFrames = 0
        var nativeViewLimitedFrames = 0
        var nativeMonitorFrames = 0
        var firstNativeHardBlockFrame: Int? = null
        var firstNativeHardBlockReason: String? = null
        var previousNativeLandmarks: FloatArray? = null
        val nativeReasonCounts = linkedMapOf<String, Int>()
        val nativeExerciseCounts = linkedMapOf<String, Int>()
        var delegate = "uninitialized"
        var landmarker: PoseLandmarker? = null

        return try {
            runBlocking(Dispatchers.IO) {
                val created = createDebugVideoPoseLandmarker(context, maxPoses = VIDEO_REALTIME_SMOKE_MAX_POSES)
                landmarker = created.landmarker
                delegate = created.delegate
                if (landmarker == null) return@runBlocking

                val processor = VideoProcessor(
                    context = context,
                    poseLandmarker = landmarker,
                    sampleEveryNFrames = 1,
                    maxDimension = VIDEO_REALTIME_SMOKE_LONG_SIDE,
                    targetAnalysisIntervalMs = VIDEO_REALTIME_SMOKE_INTERVAL_MS,
                    pass = VideoAnalysisPass.PREVIEW,
                )
                withTimeout(VIDEO_REALTIME_SMOKE_TIMEOUT_MS) {
                    processor.processVideo(Uri.fromFile(videoFile))
                        .take(VIDEO_REALTIME_SMOKE_FRAME_LIMIT)
                        .collect { result ->
                        analyzedFrames += 1
                        poseMs += result.poseMs
                        convertMs += result.convertMs
                        extractMs += result.extractMs
                        sourceCounts[result.source] = (sourceCounts[result.source] ?: 0) + 1
                        lastTimestampMs?.let { previous ->
                            if (result.timestampMs <= previous) timestampMonotonic = false
                        }
                        lastTimestampMs = result.timestampMs

                        val pose = result.landmarks?.landmarks?.firstOrNull()
                        val poseCount = result.landmarks?.landmarks?.size ?: 0
                        val landmarkCount = pose?.size ?: 0
                        val summary = landmarkSummary(pose)
                        val avgVisibility = summary.optDouble("avg_visibility", 0.0)
                        if (poseCount > 0) {
                            poseHits += 1
                            visibilitySum += avgVisibility
                            visibilityFrames += 1
                            if (firstPoseFrame == null) {
                                firstPoseFrame = result.frameIndex
                                firstPoseTimestampMs = result.timestampMs
                            }
                        }
                        if (landmarkCount >= 33) {
                            landmark33Frames += 1
                        }

                        pose?.takeIf { it.size >= 33 }?.let { nativePose ->
                            val nativeInput = nativePose.toFloat99()
                            val nativeResult = KinematicsBridge.parseResult(
                                KinematicsBridge.processFrame(nativeInput, previousNativeLandmarks, 0.6f)
                            )
                            previousNativeLandmarks = nativeInput
                            if (nativeResult.success) {
                                nativeAnalyzedFrames += 1
                                if (nativeResult.gateBlocked) {
                                    nativeGateBlockedFrames += 1
                                    val reason = nativeResult.gateReason.ifBlank { "native_gate_blocked" }
                                    nativeReasonCounts[reason] = (nativeReasonCounts[reason] ?: 0) + 1
                                    if (firstNativeHardBlockFrame == null) {
                                        firstNativeHardBlockFrame = result.frameIndex
                                        firstNativeHardBlockReason = reason
                                    }
                                } else {
                                    val motion = runCatching {
                                        JSONObject(nativeResult.motionReportJson)
                                    }.getOrNull()
                                    val exercise = motion?.optString("exercise", "unknown") ?: "unknown"
                                    nativeExerciseCounts[exercise] = (nativeExerciseCounts[exercise] ?: 0) + 1
                                    val quality = motion?.optJSONArray("quality_flags")
                                    var hasLimited = false
                                    var hasViewLimited = false
                                    var hasMonitor = false
                                    if (quality != null) {
                                        for (i in 0 until quality.length()) {
                                            val flag = quality.optJSONObject(i) ?: continue
                                            val status = flag.optString("status")
                                            val reason = flag.optString("reason", flag.optString("id", "quality_flag"))
                                            if (reason.isNotBlank()) {
                                                nativeReasonCounts[reason] = (nativeReasonCounts[reason] ?: 0) + 1
                                            }
                                            when (status) {
                                                "LOW_CONFIDENCE" -> hasLimited = true
                                                "VIEW_LIMITED" -> hasViewLimited = true
                                                "MONITOR" -> hasMonitor = true
                                            }
                                        }
                                    }
                                    if (hasLimited) nativeLowConfidenceFrames += 1
                                    if (hasViewLimited) nativeViewLimitedFrames += 1
                                    if (hasMonitor) nativeMonitorFrames += 1
                                    if ((hasLimited || hasViewLimited) && firstNativeHardBlockFrame == null) {
                                        firstNativeHardBlockFrame = result.frameIndex
                                        firstNativeHardBlockReason = nativeReasonCounts.keys.lastOrNull()
                                    }
                                }
                            }
                        }

                        val kneeAngle = pose?.let(::bestKneeAngleDeg)
                        if (kneeAngle != null) {
                            lastTemporal = temporalAnalyzer.addSample(
                                frameIndex = result.frameIndex,
                                timestampMs = result.timestampMs,
                                exercise = "squat",
                                metrics = mapOf("knee_angle" to kneeAngle),
                                confidenceFloor = avgVisibility.toFloat().coerceIn(0f, 1f),
                            )
                            maxVelocityDegS = maxOf(maxVelocityDegS, lastTemporal.smoothedVelocityDegS)
                            val rapid = lastTemporal.rapidFlag
                            if (rapid?.status in setOf("WARNING", "CRITICAL")) {
                                latestWarning = rapid
                            }
                        }

                        if (frameSamples.length() < VIDEO_REALTIME_SMOKE_FRAME_LIMIT) {
                            frameSamples.put(
                                JSONObject()
                                    .put("frame", result.frameIndex)
                                    .put("timestamp_ms", result.timestampMs)
                                    .put("pose_count", poseCount)
                                    .put("landmark_count", landmarkCount)
                                    .put("source", result.source)
                                    .put("extract_ms", result.extractMs)
                                    .put("pose_ms", result.poseMs)
                                    .put("convert_ms", result.convertMs)
                                    .put("knee_angle_deg", kneeAngle ?: JSONObject.NULL)
                                    .put("landmark_summary", summary)
                            )
                        }
                    }
                }
            }

            val elapsedMs = System.currentTimeMillis() - started
            val poseHitRate = ratio(poseHits, analyzedFrames)
            val landmark33Rate = ratio(landmark33Frames, analyzedFrames)
            val avgVisibility = if (visibilityFrames > 0) visibilitySum / visibilityFrames else 0.0
            val expectedNoPerson = videoFile.name.contains("no_person", ignoreCase = true)
            val skeletonCorrect = if (expectedNoPerson) {
                poseHitRate <= 0.1
            } else {
                analyzedFrames > 0 && poseHitRate >= 0.7 && landmark33Rate >= 0.7
            }
            val realtimeBudgetMs = VIDEO_REALTIME_SMOKE_INTERVAL_MS.toDouble()
            val realtimeEnough = analyzedFrames > 0 &&
                average(poseMs) <= realtimeBudgetMs * 0.6 &&
                percentile(poseMs, 95) <= realtimeBudgetMs * 0.9
            val posePayload = JSONObject()
                .put("success", skeletonCorrect && realtimeEnough)
                .put("skeleton_correct", skeletonCorrect)
                .put("realtime_enough", realtimeEnough)
                .put("delegate", delegate)
                .put("sample_interval_ms", VIDEO_REALTIME_SMOKE_INTERVAL_MS)
                .put("sample_cap_frames", VIDEO_REALTIME_SMOKE_FRAME_LIMIT)
                .put("long_side", VIDEO_REALTIME_SMOKE_LONG_SIDE)
                .put("max_poses", VIDEO_REALTIME_SMOKE_MAX_POSES)
                .put("analyzed_frames", analyzedFrames)
                .put("pose_hits", poseHits)
                .put("pose_misses", analyzedFrames - poseHits)
                .put("pose_hit_rate", poseHitRate)
                .put("landmark_33_rate", landmark33Rate)
                .put("first_pose_frame", firstPoseFrame ?: JSONObject.NULL)
                .put("first_pose_timestamp_ms", firstPoseTimestampMs ?: JSONObject.NULL)
                .put("avg_visibility", avgVisibility)
                .put("avg_pose_ms", average(poseMs))
                .put("p95_pose_ms", percentile(poseMs, 95))
                .put("avg_pose_budget_ms", realtimeBudgetMs * 0.6)
                .put("p95_pose_budget_ms", realtimeBudgetMs * 0.9)
                .put("avg_extract_ms", average(extractMs))
                .put("p95_extract_ms", percentile(extractMs, 95))
                .put("avg_convert_ms", average(convertMs))
                .put("p95_convert_ms", percentile(convertMs, 95))
                .put("processing_fps", if (elapsedMs > 0) analyzedFrames * 1000.0 / elapsedMs else 0.0)
                .put("timestamp_monotonic", timestampMonotonic)
                .put("source_counts", JSONObject(sourceCounts as Map<*, *>))
                .put("native_motion_quality", JSONObject()
                    .put("analyzed_frames", nativeAnalyzedFrames)
                    .put("gate_blocked_frames", nativeGateBlockedFrames)
                    .put("low_confidence_frames", nativeLowConfidenceFrames)
                    .put("view_limited_frames", nativeViewLimitedFrames)
                    .put("monitor_frames", nativeMonitorFrames)
                    .put("first_hard_block_frame", firstNativeHardBlockFrame ?: JSONObject.NULL)
                    .put("first_hard_block_reason", firstNativeHardBlockReason ?: JSONObject.NULL)
                    .put("exercise_counts", JSONObject(nativeExerciseCounts as Map<*, *>))
                    .put("reason_counts", JSONObject(nativeReasonCounts as Map<*, *>)))
                .put("first_frames", frameSamples)

            val aiPayload = runVideoRealtimeCoachCheck(
                app = app,
                videoName = videoFile.name,
                poseHitRate = poseHitRate,
                landmark33Rate = landmark33Rate,
                avgVisibility = avgVisibility.toFloat(),
                latestWarning = latestWarning,
                lastTemporal = lastTemporal,
                maxVelocityDegS = maxVelocityDegS,
            )
            val payload = JSONObject()
                .put("enabled", true)
                .put("success", posePayload.optBoolean("success") && aiPayload.optBoolean("success"))
                .put("video_name", videoFile.name)
                .put("video_path", videoFile.absolutePath)
                .put("video_size_bytes", videoFile.length())
                .put("elapsed_ms", elapsedMs)
                .put("pose_realtime", posePayload)
                .put("ai_assistant_realtime", aiPayload)
            updateState(
                section = "video_realtime_smoke",
                data = mapOf(
                    "success" to payload.optBoolean("success"),
                    "video_name" to videoFile.name,
                    "elapsed_ms" to elapsedMs,
                    "pose_realtime" to posePayload,
                    "ai_assistant_realtime" to aiPayload,
                ),
            )
            record(
                category = "video_realtime_smoke",
                message = "completed",
                data = mapOf(
                    "success" to payload.optBoolean("success"),
                    "video_name" to videoFile.name,
                    "pose_success" to posePayload.optBoolean("success"),
                    "ai_success" to aiPayload.optBoolean("success"),
                    "elapsed_ms" to elapsedMs,
                ),
            )
            payload.toString(2)
        } catch (e: Throwable) {
            val payload = JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("video_name", videoFile.name)
                .put("video_path", videoFile.absolutePath)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("error_type", e::class.java.name)
                .put("error", e.message ?: "video_realtime_smoke_failed")
            updateState(
                section = "video_realtime_smoke",
                data = mapOf(
                    "success" to false,
                    "video_name" to videoFile.name,
                    "error" to (e.message ?: "video_realtime_smoke_failed"),
                ),
            )
            payload.toString(2)
        } finally {
            runCatching { landmarker?.close() }
        }
    }

    fun runFunctionGemmaMinimalToolSmoke(context: Context): String {
        return runFunctionGemmaIsolatedSmoke(
            context = context,
            mode = "minimal_tool",
            useTool = true,
        )
    }

    fun clearEvents(context: Context): Int {
        if (!BuildConfig.DEBUG) return 0
        initialize(context)
        synchronized(lock) {
            val file = eventFile(context)
            val existed = file.exists()
            if (existed) file.writeText("")
            record("debug_api", "events_cleared")
            return if (existed) 1 else 0
        }
    }

    fun clearRgbaPipelineAudit(context: Context): Int {
        if (!BuildConfig.DEBUG) return 0
        initialize(context)
        RgbaPipelineAudit.reset()
        updateState(
            section = "rgba_pipeline_audit",
            data = mapOf("snapshot" to RgbaPipelineAudit.snapshotJson()),
        )
        record("rgba_pipeline_audit", "reset")
        return 1
    }

    private fun currentThermalStatus(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    private fun shouldSkipPrewarmForThermal(status: Int): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            status >= PowerManager.THERMAL_STATUS_SEVERE
    }

    private fun thermalStatusName(status: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown_$status"
        }
    }

    private fun enqueueIo(block: () -> Unit) {
        ioExecutor.execute {
            synchronized(lock) {
                block()
            }
        }
    }

    private fun shouldLogcat(category: String, message: String, nowMs: Long): Boolean {
        val key = "$category:$message"
        synchronized(lock) {
            val lastMs = lastLogcatAtMs[key] ?: 0L
            if (nowMs - lastMs < LOGCAT_REPEAT_INTERVAL_MS) {
                return false
            }
            lastLogcatAtMs[key] = nowMs
            if (lastLogcatAtMs.size > 64) {
                val iterator = lastLogcatAtMs.keys.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            return true
        }
    }

    private fun writeStateLocked() {
        val context = appContext ?: return
        val root = JSONObject()
            .put("updated_at_ms", System.currentTimeMillis())
            .put("sections", JSONObject())
        val sections = root.getJSONObject("sections")
        state.forEach { (key, value) -> sections.put(key, value) }
        runCatching {
            val file = stateFile(context)
            file.parentFile?.mkdirs()
            file.writeText(root.toString(2))
        }.onFailure {
            Log.w(TAG, "state write failed: ${it.message}")
        }
    }

    private fun readStateLocked(context: Context): JSONObject {
        if (state.isNotEmpty()) {
            val root = JSONObject()
                .put("updated_at_ms", System.currentTimeMillis())
                .put("sections", JSONObject())
            val sections = root.getJSONObject("sections")
            state.forEach { (key, value) -> sections.put(key, value) }
            return root
        }
        val file = stateFile(context)
        if (!file.exists()) return JSONObject().put("sections", JSONObject())
        return runCatching { JSONObject(file.readText()) }
            .getOrElse { JSONObject().put("parse_error", it.message ?: "unknown") }
    }

    private fun readEventsLocked(context: Context, limit: Int): JSONArray {
        val file = eventFile(context)
        if (!file.exists()) return JSONArray()
        val lines = runCatching { file.readLines().takeLast(limit.coerceAtLeast(1)) }
            .getOrDefault(emptyList())
        val arr = JSONArray()
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            arr.put(runCatching { JSONObject(line) }.getOrElse {
                JSONObject().put("parse_error", it.message ?: "unknown").put("raw", line.take(300))
            })
        }
        return arr
    }

    private fun rotateIfOversizedLocked() {
        val context = appContext ?: return
        val file = eventFile(context)
        if (!file.exists() || file.length() <= MAX_EVENT_BYTES) return
        val retained = runCatching { file.readLines().takeLast(DEFAULT_EVENT_LIMIT) }
            .getOrDefault(emptyList())
        file.writeText(retained.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun debugDir(): File {
        val context = appContext ?: return File("debug")
        return debugDir(context)
    }

    private fun debugDir(context: Context): File = File(context.filesDir, "debug")

    private fun eventFile(context: Context): File = File(debugDir(context), "gemmafit_debug_events.jsonl")

    private fun stateFile(context: Context): File = File(debugDir(context), "gemmafit_debug_state.json")

    private fun runFunctionGemmaIsolatedSmoke(
        context: Context,
        mode: String,
        useTool: Boolean,
    ): String {
        if (!BuildConfig.DEBUG) {
            return JSONObject()
                .put("enabled", false)
                .put("reason", "debug_api_disabled_in_release")
                .toString()
        }
        initialize(context)
        val modelFile = functionGemmaModelFile(context)
        if (modelFile == null) {
            return JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("mode", mode)
                .put("error", "functiongemma_model_file_not_found")
                .toString(2)
        }

        val started = System.currentTimeMillis()
        return try {
            val payload = runBlocking(Dispatchers.IO) {
                runFunctionGemmaBackendAttempt(
                    context = context,
                    modelFile = modelFile,
                    mode = mode,
                    useTool = useTool,
                )
            }
            record(
                category = "litert_functiongemma_smoke",
                message = "completed",
                data = mapOf(
                    "mode" to mode,
                    "success" to payload.optBoolean("success"),
                    "backend" to payload.optString("backend"),
                    "elapsed_ms" to (System.currentTimeMillis() - started),
                ),
            )
            payload.toString(2)
        } catch (e: Throwable) {
            JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("mode", mode)
                .put("backend", "litert-lm:cpu")
                .put("error_type", e::class.java.name)
                .put("error", e.message ?: "functiongemma_isolated_smoke_failed")
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("model_path", modelFile.absolutePath)
                .put("model_size_bytes", modelFile.length())
                .toString(2)
        }
    }

    private suspend fun runFunctionGemmaBackendAttempt(
        context: Context,
        modelFile: File,
        mode: String,
        useTool: Boolean,
    ): JSONObject = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var stage = "engine_create"
        var engine: Engine? = null
        try {
            engine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = FUNCTIONGEMMA_MAX_TOKENS,
                    cacheDir = File(context.cacheDir, "litert-lm-functiongemma-$mode").absolutePath,
                )
            )
            stage = "engine_initialize"
            engine.initialize()
            stage = "conversation_create"
            val toolExecutions = mutableListOf<JSONObject>()
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are a mobile action router. Use the available tool when the user asks for a device action."
                    ),
                    tools = if (useTool) listOf(tool(mobileActionShowMapTool(toolExecutions))) else emptyList(),
                    samplerConfig = SamplerConfig(
                        topK = 1,
                        topP = 0.1,
                        temperature = 0.0,
                        seed = 11,
                    ),
                    automaticToolCalling = useTool,
                )
            )
            conversation.use { activeConversation ->
                stage = "send_message"
                val prompt = if (useTool) {
                    "Show Taipei 101 on the map."
                } else {
                    "Reply with exactly: ok"
                }
                val response = withTimeout(FUNCTIONGEMMA_TIMEOUT_MS) {
                    activeConversation.sendMessage(prompt)
                }
                return@withContext JSONObject()
                    .put("enabled", true)
                    .put("success", true)
                    .put("mode", mode)
                    .put("backend", "litert-lm:cpu")
                    .put("stage", "complete")
                    .put("elapsed_ms", System.currentTimeMillis() - started)
                    .put("model_path", modelFile.absolutePath)
                    .put("model_size_bytes", modelFile.length())
                    .put("response_role", response.role.toString())
                    .put("response_contents", response.contents.toString())
                    .put("tool_calls", toolCallsToJsonArray(response.toolCalls))
                    .put("tool_executions", jsonObjectsToArray(toolExecutions))
                    .put("raw_response", response.toString())
            }
        } catch (e: Throwable) {
            return@withContext JSONObject()
                .put("enabled", true)
                .put("success", false)
                .put("mode", mode)
                .put("backend", "litert-lm:cpu")
                .put("stage", stage)
                .put("elapsed_ms", System.currentTimeMillis() - started)
                .put("model_path", modelFile.absolutePath)
                .put("model_size_bytes", modelFile.length())
                .put("error_type", e::class.java.name)
                .put("error", e.message ?: "unknown")
        } finally {
            runCatching { engine?.close() }
        }
    }

    private fun functionGemmaModelFile(context: Context): File? {
        val name = "mobile_actions_q8_ekv1024.litertlm"
        val candidates = buildList {
            add(File(context.filesDir, name))
            context.getExternalFilesDir(null)?.let { add(File(it, name)) }
            add(File("/storage/emulated/0/Android/data/com.gemmafit/files/$name"))
            add(File("/sdcard/Android/data/com.gemmafit/files/$name"))
        }
        return candidates.distinctBy { it.absolutePath }
            .firstOrNull { it.exists() && it.canRead() && it.length() > 0L }
    }

    private fun mobileActionShowMapTool(toolExecutions: MutableList<JSONObject>): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            return JSONObject()
                .put("name", "show_map")
                .put("description", "Shows a location on the map.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "OBJECT")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put(
                                            "description",
                                            "The location to search for. May be the name of a place, a business, or an address."
                                        )
                                )
                        )
                        .put("required", JSONArray(listOf("query")))
                )
                .toString()
        }

        override fun execute(paramsJsonString: String): String {
            Log.d(TAG, "FunctionGemma show_map execute params=$paramsJsonString")
            toolExecutions += JSONObject()
                .put("function", "show_map")
                .put("params", runCatching { JSONObject(paramsJsonString) }.getOrElse { JSONObject() })
            return JSONObject()
                .put("accepted", true)
                .put("function", "show_map")
                .put("params", runCatching { JSONObject(paramsJsonString) }.getOrElse { JSONObject() })
                .toString()
        }
    }
}

private data class DebugVideoPoseLandmarker(
    val landmarker: PoseLandmarker?,
    val delegate: String,
)

private data class DebugVideoFrameSample(
    val frameIndex: Int,
    val timestampMs: Long,
    val poses: List<List<NormalizedLandmark>>,
    val convertMs: Long,
    val poseMs: Long,
)

private fun resolveVideoRealtimeSmokeFile(
    context: Context,
    requestedName: String?,
): File? {
    val names = buildList {
        requestedName?.takeIf { it.isNotBlank() }?.let { add(File(it).name) }
        add("wrong_right_squat_gymvisual_knees_preview.mp4")
        add("senior_chair_stand_cdc.mp4")
        add("pixel_line_error_1774202137014.mp4")
        add("no_person_blank_3s.mp4")
    }.distinct()
    for (dir in videoRealtimeSmokeSearchDirs(context)) {
        for (name in names) {
            val file = File(dir, name)
            if (file.exists() && file.canRead() && file.length() > 0L) return file
        }
    }
    return null
}

private fun videoRealtimeSmokeSearchDirs(context: Context): List<File> {
    val external = context.getExternalFilesDir(null)
    return buildList {
        external?.let {
            add(File(it, "test_videos"))
            add(it)
        }
        add(File(context.filesDir, "test_videos"))
        add(context.filesDir)
        add(File("/storage/emulated/0/Android/data/com.gemmafit/files/test_videos"))
        add(File("/storage/emulated/0/Android/data/com.gemmafit/files"))
        add(File("/sdcard/Android/data/com.gemmafit/files/test_videos"))
        add(File("/sdcard/Android/data/com.gemmafit/files"))
    }.distinctBy { it.absolutePath }
}

private fun createDebugVideoPoseLandmarker(
    context: Context,
    maxPoses: Int,
): DebugVideoPoseLandmarker {
    fun options(delegate: Delegate): PoseLandmarker.PoseLandmarkerOptions {
        return PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setDelegate(delegate)
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .build()
            )
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setNumPoses(maxPoses.coerceAtLeast(1))
            .setRunningMode(RunningMode.VIDEO)
            .build()
    }

    return try {
        DebugVideoPoseLandmarker(
            landmarker = PoseLandmarker.createFromOptions(context, options(Delegate.GPU)),
            delegate = "GPU",
        )
    } catch (gpuError: Exception) {
        try {
            DebugVideoPoseLandmarker(
                landmarker = PoseLandmarker.createFromOptions(context, options(Delegate.CPU)),
                delegate = "CPU",
            )
        } catch (cpuError: Exception) {
            DebugVideoPoseLandmarker(
                landmarker = null,
                delegate = "failed: gpu=${gpuError.message}; cpu=${cpuError.message}",
            )
        }
    }
}

private fun sampleDebugVideoFrames(
    videoFile: File,
    landmarker: PoseLandmarker,
    intervalMs: Long,
    frameLimit: Int,
    longSide: Int,
): List<DebugVideoFrameSample> {
    val samples = mutableListOf<DebugVideoFrameSample>()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: (intervalMs * frameLimit)
        val sourceWidth = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: longSide
        val sourceHeight = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: longSide
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        val orientedWidth = if (rotation == 90 || rotation == 270) sourceHeight else sourceWidth
        val orientedHeight = if (rotation == 90 || rotation == 270) sourceWidth else sourceHeight
        val (targetWidth, targetHeight) = scaledVideoDimensions(
            width = orientedWidth,
            height = orientedHeight,
            longSide = longSide,
        )

        var frameIndex = 0
        var timestampMs = 0L
        val safeDurationMs = durationMs.coerceAtLeast(intervalMs)
        while (frameIndex < frameLimit && timestampMs < safeDurationMs) {
            var bitmap: Bitmap? = null
            val convertStart = System.currentTimeMillis()
            try {
                bitmap = getDebugRetrieverFrame(
                    retriever = retriever,
                    timestampMs = timestampMs,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                )
                val convertElapsedMs = System.currentTimeMillis() - convertStart
                if (bitmap == null) {
                    samples += DebugVideoFrameSample(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        poses = emptyList(),
                        convertMs = convertElapsedMs,
                        poseMs = 0L,
                    )
                } else {
                    val poseStart = System.currentTimeMillis()
                    val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), timestampMs)
                    val poseElapsedMs = System.currentTimeMillis() - poseStart
                    val poses = result.landmarks().map { lmList ->
                        lmList.map { lm ->
                            NormalizedLandmark(lm.x(), lm.y(), lm.z(), lm.visibility().orElse(0.0f))
                        }
                    }
                    samples += DebugVideoFrameSample(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        poses = poses,
                        convertMs = convertElapsedMs,
                        poseMs = poseElapsedMs,
                    )
                }
            } finally {
                bitmap?.recycle()
            }
            frameIndex += 1
            timestampMs += intervalMs
        }
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
    return samples
}

private fun getDebugRetrieverFrame(
    retriever: MediaMetadataRetriever,
    timestampMs: Long,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap? {
    val timestampUs = timestampMs * 1000L
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && targetWidth > 0 && targetHeight > 0) {
        try {
            return retriever.getScaledFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                targetWidth,
                targetHeight,
            )
        } catch (_: Exception) {
        }
    }
    return try {
        retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
    } catch (_: Exception) {
        null
    }
}

private fun scaledVideoDimensions(
    width: Int,
    height: Int,
    longSide: Int,
): Pair<Int, Int> {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val scale = minOf(1f, longSide.toFloat() / maxOf(safeWidth, safeHeight))
    return (safeWidth * scale).toInt().coerceAtLeast(1) to
        (safeHeight * scale).toInt().coerceAtLeast(1)
}

private fun runVideoRealtimeCoachCheck(
    app: Application,
    videoName: String,
    poseHitRate: Double,
    landmark33Rate: Double,
    avgVisibility: Float,
    latestWarning: QualityFlag?,
    lastTemporal: TemporalMotionAnalyzer.Result,
    maxVelocityDegS: Float,
): JSONObject {
    if (poseHitRate < 0.2 || landmark33Rate < 0.2) {
        return invokeRealtimeCoachBackend(
            app = app,
            expectedFunction = "refuse_unsupported_question",
            context = videoNoPoseCoachContext(videoName, poseHitRate, landmark33Rate),
            safetyJson = videoNoPoseSafetyJson(videoName, poseHitRate, landmark33Rate),
        )
            .put("situation", "no_reliable_pose")
            .put("expected_behavior", "boundary_refusal_without_generation")
    }

    if (latestWarning != null) {
        val velocity = maxOf(latestWarning.value, maxVelocityDegS, lastTemporal.smoothedVelocityDegS)
        return invokeRealtimeCoachBackend(
            app = app,
            expectedFunction = "warn_rapid_movement",
            context = videoRapidCoachContext(videoName, latestWarning, velocity, avgVisibility),
            safetyJson = videoRapidSafetyJson(videoName, latestWarning, velocity),
        )
            .put("situation", "rapid_movement_warning")
            .put("expected_behavior", "immediate_safety_warning_without_generation")
    }

    val liveFramePlan = ModelInvocationScheduler.plan(
        ModelInvocationRequest(
            trigger = ModelInvocationTrigger.LIVE_FRAME,
            personTrackingState = PersonTrackingState.OBSERVED,
            confidenceFloor = avgVisibility.coerceIn(0f, 1f),
            capabilityJudgmentAllowed = true,
            hasCriticalOrWarningEvidence = false,
            needsLanguageExplanation = false,
        )
    )
    val repPlan = ModelInvocationScheduler.plan(
        ModelInvocationRequest(
            trigger = ModelInvocationTrigger.REP_COMPLETED,
            personTrackingState = PersonTrackingState.OBSERVED,
            confidenceFloor = avgVisibility.coerceIn(0f, 1f),
            capabilityJudgmentAllowed = true,
            hasCriticalOrWarningEvidence = false,
            needsLanguageExplanation = false,
        )
    )
    return JSONObject()
        .put("success", liveFramePlan.decision == ModelInvocationDecision.SKIP_DETERMINISTIC)
        .put("invoked", false)
        .put("situation", "pose_detected_no_realtime_warning")
        .put("expected_behavior", "no_immediate_ai_cue_for_clean_or_uncertain_video")
        .put("realtime_ok", true)
        .put("live_frame_plan", liveFramePlan.toDebugMap().toJsonObject())
        .put("rep_completed_plan", repPlan.toDebugMap().toJsonObject())
        .put("max_velocity_deg_s", maxVelocityDegS)
        .put("last_movement_phase", lastTemporal.movementPhase)
        .put("last_rep_count", lastTemporal.repCount)
}

private fun invokeRealtimeCoachBackend(
    app: Application,
    expectedFunction: String,
    context: CoachContext,
    safetyJson: String,
): JSONObject {
    val backend = LiteRtLmCoachBackend(app)
    val started = System.currentTimeMillis()
    return try {
        val result = runBlocking(Dispatchers.IO) {
            backend.runInference(
                context = context,
                safetyJson = safetyJson,
                reasoningMode = ModelReasoningMode.OFF,
            )
        }
        JSONObject()
            .put("success", result.success && result.functionName == expectedFunction && result.inferenceTimeMs <= 50.0)
            .put("invoked", true)
            .put("expected_function", expectedFunction)
            .put("function", result.functionName)
            .put("backend", result.backend)
            .put("realtime_ok", result.inferenceTimeMs <= 50.0)
            .put("matches_expected", result.functionName == expectedFunction)
            .put("inference_time_ms", result.inferenceTimeMs)
            .put("elapsed_ms", System.currentTimeMillis() - started)
            .put("args", runCatching { JSONObject(result.argsJson) }.getOrElse { JSONObject() })
            .put("selection_basis", result.selectionBasis)
            .put("evidence_refs", JSONArray(result.evidenceRefs))
            .put("error", result.errorMessage)
            .put("raw_response", result.rawResponse.take(1000))
    } finally {
        backend.close()
    }
}

private fun videoNoPoseCoachContext(
    videoName: String,
    poseHitRate: Double,
    landmark33Rate: Double,
): CoachContext {
    return CoachContext(
        exercise = "unknown",
        movementPhase = "view_limited",
        pattern = "unknown",
        repCount = 0,
        cleanStreak = 0,
        metrics = mapOf(
            "pose_hit_rate" to poseHitRate.toFloat(),
            "landmark_33_rate" to landmark33Rate.toFloat(),
        ),
        muscle = null,
        warnings = emptyList(),
        qualityFlags = listOf(
            QualityFlag(
                id = "pose_not_detected",
                evidenceId = "metric.$videoName.pose_hit_rate",
                status = "VIEW_LIMITED",
                value = poseHitRate.toFloat(),
                threshold = 0.2f,
                evidence = "video_realtime_smoke_pose_detection",
                reason = "no_reliable_person_pose_detected",
                rule = 0,
                joint = "body",
            )
        ),
        notApplicableFlags = emptyList(),
        evidenceCard = EvidenceCard(
            verdict = "VIEW_LIMITED",
            reason = "No reliable person pose was detected in the test video.",
            evidence = listOf(EvidenceItem("pose hit rate", poseHitRate.toString())),
            evidenceRefs = listOf("metric.$videoName.pose_hit_rate"),
            capabilityCanJudge = emptyList(),
            capabilityCannotJudge = listOf("movement_quality", "joint_force", "muscle_activation"),
        ),
    )
}

private fun videoNoPoseSafetyJson(
    videoName: String,
    poseHitRate: Double,
    landmark33Rate: Double,
): String {
    return JSONObject()
        .put("trigger", "DEBUG_VIDEO_REALTIME_NO_POSE")
        .put("exercise", "unknown")
        .put("required_response_policy", "refuse_view_limited_or_missing_pose")
        .put(
            "person_tracking_state",
            JSONObject()
                .put("schema_version", "person_tracking_v1")
                .put("state", "lost")
                .put("pose_confidence", poseHitRate)
                .put("judgment_allowed", false)
                .put("hard_judgment_allowed", false)
                .put("reason", "no_reliable_person_pose_detected"),
        )
        .put(
            "evidence_dag_compact",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("id", "metric.$videoName.pose_hit_rate")
                        .put("metric", "pose_hit_rate")
                        .put("value", poseHitRate)
                        .put("threshold", 0.2)
                        .put("status", "VIEW_LIMITED"),
                    JSONObject()
                        .put("id", "metric.$videoName.landmark_33_rate")
                        .put("metric", "landmark_33_rate")
                        .put("value", landmark33Rate)
                        .put("threshold", 0.2)
                        .put("status", "VIEW_LIMITED"),
                )
            ),
        )
        .toString()
}

private fun videoRapidCoachContext(
    videoName: String,
    warning: QualityFlag,
    velocityDegS: Float,
    avgVisibility: Float,
): CoachContext {
    val evidenceId = warning.evidenceId.ifBlank { "metric.$videoName.knee_velocity" }
    return CoachContext(
        exercise = "squat",
        movementPhase = "transition",
        pattern = "squat",
        repCount = 0,
        cleanStreak = 0,
        metrics = mapOf(
            "knee_peak_velocity_deg_s" to velocityDegS,
            "pose_confidence" to avgVisibility,
        ),
        muscle = MuscleFocusResult(
            primary = listOf("quadriceps", "glutes"),
            secondary = listOf("hamstrings", "core"),
            pattern = "squat",
            confidence = "pose_estimated",
        ),
        warnings = listOf(
            SafetyWarning(
                rule = 6,
                functionName = "warn_rapid_movement",
                message = "Video-derived knee velocity crossed the controlled-tempo gate.",
                severity = "high",
                joint = warning.joint.ifBlank { "knee" },
            )
        ),
        qualityFlags = listOf(warning.copy(evidenceId = evidenceId, value = velocityDegS)),
        notApplicableFlags = emptyList(),
        evidenceCard = EvidenceCard(
            verdict = "WARNING",
            reason = "Temporal pose evidence indicates rapid movement.",
            evidence = listOf(EvidenceItem("knee peak velocity", velocityDegS.toString())),
            evidenceRefs = listOf(evidenceId),
            capabilityCanJudge = listOf("tempo", "knee_peak_velocity_deg_s"),
            capabilityCannotJudge = listOf("joint_force", "muscle_activation"),
        ),
    )
}

private fun videoRapidSafetyJson(
    videoName: String,
    warning: QualityFlag,
    velocityDegS: Float,
): String {
    val evidenceId = warning.evidenceId.ifBlank { "metric.$videoName.knee_velocity" }
    return JSONObject()
        .put("trigger", "DEBUG_VIDEO_REALTIME_RAPID")
        .put("exercise", "squat")
        .put(
            "evidence_dag_compact",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("id", evidenceId)
                        .put("type", "template_metric")
                        .put("metric", "knee_peak_velocity_deg_s")
                        .put("value", velocityDegS)
                        .put("threshold", warning.threshold)
                        .put("confidence", 0.8)
                        .put("status", "WARNING"),
                )
            ),
        )
        .toString()
}

private fun landmarkSummary(pose: List<NormalizedLandmark>?): JSONObject {
    if (pose.isNullOrEmpty()) {
        return JSONObject()
            .put("landmark_count", 0)
            .put("visible_count", 0)
            .put("avg_visibility", 0.0)
    }
    val visible = pose.filter { it.visibility >= 0.2f }
    val avgVisibility = pose.map { it.visibility.toDouble() }.average()
    val bbox = if (visible.isEmpty()) {
        JSONObject.NULL
    } else {
        JSONObject()
            .put("min_x", visible.minOf { it.x.toDouble() })
            .put("min_y", visible.minOf { it.y.toDouble() })
            .put("max_x", visible.maxOf { it.x.toDouble() })
            .put("max_y", visible.maxOf { it.y.toDouble() })
    }
    return JSONObject()
        .put("landmark_count", pose.size)
        .put("visible_count", visible.size)
        .put("avg_visibility", avgVisibility)
        .put("bbox", bbox)
}

private fun List<NormalizedLandmark>.toFloat99(): FloatArray {
    val out = FloatArray(99)
    for (index in 0 until minOf(size, 33)) {
        val landmark = this[index]
        out[index * 3] = landmark.x
        out[index * 3 + 1] = landmark.y
        out[index * 3 + 2] = landmark.visibility
    }
    return out
}

private fun bestKneeAngleDeg(pose: List<NormalizedLandmark>): Float? {
    val left = jointAngleDeg(pose, hip = 23, knee = 25, ankle = 27)
    val right = jointAngleDeg(pose, hip = 24, knee = 26, ankle = 28)
    return when {
        left == null -> right
        right == null -> left
        pose[25].visibility + pose[27].visibility >= pose[26].visibility + pose[28].visibility -> left
        else -> right
    }
}

private fun jointAngleDeg(
    pose: List<NormalizedLandmark>,
    hip: Int,
    knee: Int,
    ankle: Int,
): Float? {
    val a = pose.getOrNull(hip) ?: return null
    val b = pose.getOrNull(knee) ?: return null
    val c = pose.getOrNull(ankle) ?: return null
    if (listOf(a, b, c).any { it.visibility < 0.2f }) return null

    val v1x = a.x - b.x
    val v1y = a.y - b.y
    val v2x = c.x - b.x
    val v2y = c.y - b.y
    val denom = hypot(v1x.toDouble(), v1y.toDouble()) * hypot(v2x.toDouble(), v2y.toDouble())
    if (denom <= 0.000001) return null
    val cosine = ((v1x * v2x + v1y * v2y) / denom).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine)).toFloat()
}

private fun ratio(numerator: Int, denominator: Int): Double {
    return if (denominator <= 0) 0.0 else numerator.toDouble() / denominator.toDouble()
}

private fun average(values: List<Long>): Double {
    return if (values.isEmpty()) 0.0 else values.average()
}

private fun percentile(values: List<Long>, percentile: Int): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val index = ((percentile.coerceIn(0, 100) / 100.0) * (sorted.size - 1)).toInt()
    return sorted[index].toDouble()
}

private fun toolCallsToJsonArray(calls: List<com.google.ai.edge.litertlm.ToolCall>): JSONArray {
    val arr = JSONArray()
    calls.forEach { call ->
        arr.put(
            JSONObject()
                .put("name", call.name)
                .put("arguments", JSONObject(call.arguments))
        )
    }
    return arr
}

private fun jsonObjectsToArray(objects: List<JSONObject>): JSONArray {
    val arr = JSONArray()
    objects.forEach { arr.put(it) }
    return arr
}

private fun List<LiteRtInitAttempt>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    forEach { attempt ->
        arr.put(
            JSONObject()
                .put("backend", attempt.backend)
                .put("status", attempt.status)
                .put("stage", attempt.stage)
                .put("elapsed_ms", attempt.elapsedMs)
                .put("error_type", attempt.errorType)
                .put("error", attempt.errorMessage)
                .put("max_num_tokens", attempt.maxNumTokens)
                .put("model_size_bytes", attempt.modelSizeBytes)
        )
    }
    return arr
}

private data class LiteRtEffectCase(
    val name: String,
    val expectedFamily: String,
    val context: CoachContext,
    val safetyJson: String,
)

private fun liteRtEffectCases(): List<LiteRtEffectCase> {
    return listOf(
        LiteRtEffectCase(
            name = "clean_squat",
            expectedFamily = "positive_reinforcement",
            context = smokeCoachContext(),
            safetyJson = smokeSafetyJson(),
        ),
        LiteRtEffectCase(
            name = "rapid_knee_velocity",
            expectedFamily = "warn_rapid_movement",
            context = rapidMovementCoachContext(),
            safetyJson = rapidMovementSafetyJson(),
        ),
        LiteRtEffectCase(
            name = "unsupported_medical_claim",
            expectedFamily = "refuse_unsupported_question",
            context = unsupportedMedicalCoachContext(),
            safetyJson = unsupportedMedicalSafetyJson(),
        ),
    )
}

private fun smokeCoachContext(): CoachContext {
    return CoachContext(
        exercise = "squat",
        movementPhase = "summary",
        pattern = "squat",
        repCount = 3,
        cleanStreak = 30,
        metrics = mapOf(
            "squat_depth" to 0.82f,
            "tempo" to 2.4f,
            "trunk_lean" to 18f,
        ),
        muscle = MuscleFocusResult(
            primary = listOf("quadriceps", "glutes"),
            secondary = listOf("hamstrings", "core"),
            pattern = "squat",
            confidence = "pose_estimated",
        ),
        warnings = emptyList(),
        qualityFlags = emptyList(),
        notApplicableFlags = emptyList(),
        evidenceCard = EvidenceCard(
            verdict = "OK",
            reason = "LiteRT smoke test with compact synthetic evidence.",
            evidence = listOf(EvidenceItem("depth", "0.82")),
            evidenceRefs = listOf("metric.squat.depth"),
            capabilityCanJudge = listOf("squat_depth", "tempo", "trunk_lean"),
            capabilityCannotJudge = listOf("frontal_knee_valgus", "joint_force", "muscle_activation"),
        ),
    )
}

private fun rapidMovementCoachContext(): CoachContext {
    return smokeCoachContext().copy(
        cleanStreak = 0,
        metrics = mapOf(
            "squat_depth" to 0.72f,
            "tempo" to 0.8f,
            "knee_peak_velocity_deg_s" to 238f,
        ),
        warnings = listOf(
            SafetyWarning(
                rule = 6,
                functionName = "warn_rapid_movement",
                message = "Peak knee velocity crossed the controlled-tempo gate.",
                severity = "high",
                joint = "knee",
            )
        ),
        qualityFlags = listOf(
            QualityFlag(
                id = "rapid_movement.knee_velocity",
                evidenceId = "metric.squat.knee_velocity",
                status = "WARNING",
                value = 238f,
                threshold = 180f,
                evidence = "Peak knee angular velocity was 238 deg/s after smoothing.",
                reason = "tempo_too_fast_for_safe_coaching",
                rule = 6,
                joint = "knee",
            )
        ),
        evidenceCard = EvidenceCard(
            verdict = "WARNING",
            reason = "Knee angular velocity exceeded the controlled-tempo gate.",
            evidence = listOf(EvidenceItem("knee peak velocity", "238 deg/s")),
            evidenceRefs = listOf("metric.squat.knee_velocity"),
            capabilityCanJudge = listOf("tempo", "knee_peak_velocity_deg_s"),
            capabilityCannotJudge = listOf("joint_force", "muscle_activation"),
        ),
    )
}

private fun unsupportedMedicalCoachContext(): CoachContext {
    return smokeCoachContext().copy(
        exercise = "chair_sit_to_stand",
        pattern = "chair_sit_to_stand",
        evidenceCard = EvidenceCard(
            verdict = "NOT_SUPPORTED",
            reason = "The user asked for a fall-risk or sarcopenia judgment, which is outside pose-only movement feedback.",
            evidence = listOf(EvidenceItem("unsupported request", "fall risk / sarcopenia judgment")),
            evidenceRefs = listOf("capability.medical_claim.blocked"),
            capabilityCanJudge = listOf("visible_movement_quality", "tempo"),
            capabilityCannotJudge = listOf("fall_risk", "sarcopenia", "medical_diagnosis", "muscle_activation"),
        ),
    )
}

private fun smokeSafetyJson(): String {
    return JSONObject()
        .put("trigger", "DEBUG_LITERT_SMOKE")
        .put("exercise", "squat")
        .put(
            "capability_contract",
            JSONObject()
                .put(
                    "can_judge",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("metric", "squat_depth")
                                .put("confidence_ceiling", 0.9)
                                .put("evidence_refs", JSONArray(listOf("metric.squat.depth"))),
                            JSONObject()
                                .put("metric", "tempo")
                                .put("confidence_ceiling", 0.85)
                                .put("evidence_refs", JSONArray(listOf("metric.squat.tempo"))),
                        )
                    ),
                )
                .put(
                    "cannot_judge",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("metric", "frontal_knee_valgus")
                                .put("reason", "side_view")
                                .put("required_evidence", JSONArray(listOf("frontal_view")))
                                .put("evidence_refs", JSONArray(listOf("capability.frontal_knee_valgus.blocked"))),
                            JSONObject()
                                .put("metric", "muscle_activation")
                                .put("reason", "pose_only_proxy")
                                .put("required_evidence", JSONArray(listOf("emg_sensor"))),
                        )
                    ),
                ),
        )
        .put(
            "evidence_dag_compact",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("id", "metric.squat.depth")
                        .put("type", "template_metric")
                        .put("metric", "squat_depth")
                        .put("value", 0.82)
                        .put("confidence", 0.88)
                        .put("status", "OK"),
                    JSONObject()
                        .put("id", "metric.squat.tempo")
                        .put("type", "template_metric")
                        .put("metric", "tempo")
                        .put("value", 2.4)
                        .put("confidence", 0.84)
                        .put("status", "OK"),
                    JSONObject()
                        .put("id", "capability.frontal_knee_valgus.blocked")
                        .put("type", "capability")
                        .put("metric", "frontal_knee_valgus")
                        .put("confidence", 0.0)
                        .put("status", "NOT_APPLICABLE"),
                )
            ),
        )
        .toString()
}

private fun rapidMovementSafetyJson(): String {
    return JSONObject()
        .put("trigger", "DEBUG_LITERT_EFFECT_RAPID_MOVEMENT")
        .put("exercise", "squat")
        .put(
            "capability_contract",
            JSONObject()
                .put(
                    "can_judge",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("metric", "knee_peak_velocity_deg_s")
                                .put("confidence_ceiling", 0.86)
                                .put("evidence_refs", JSONArray(listOf("metric.squat.knee_velocity"))),
                            JSONObject()
                                .put("metric", "tempo")
                                .put("confidence_ceiling", 0.84)
                                .put("evidence_refs", JSONArray(listOf("metric.squat.tempo"))),
                        )
                    ),
                )
                .put(
                    "cannot_judge",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("metric", "joint_force")
                                .put("reason", "pose_only_no_force_plate")
                                .put("required_evidence", JSONArray(listOf("force_plate"))),
                            JSONObject()
                                .put("metric", "muscle_activation")
                                .put("reason", "pose_only_proxy")
                                .put("required_evidence", JSONArray(listOf("emg_sensor"))),
                        )
                    ),
                ),
        )
        .put(
            "evidence_dag_compact",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("id", "metric.squat.knee_velocity")
                        .put("type", "template_metric")
                        .put("metric", "knee_peak_velocity_deg_s")
                        .put("value", 238)
                        .put("threshold", 180)
                        .put("confidence", 0.86)
                        .put("status", "WARNING"),
                    JSONObject()
                        .put("id", "metric.squat.tempo")
                        .put("type", "template_metric")
                        .put("metric", "tempo")
                        .put("value", 0.8)
                        .put("confidence", 0.84)
                        .put("status", "WARNING"),
                )
            ),
        )
        .toString()
}

private fun unsupportedMedicalSafetyJson(): String {
    return JSONObject()
        .put("trigger", "DEBUG_LITERT_EFFECT_UNSUPPORTED_MEDICAL")
        .put("exercise", "chair_sit_to_stand")
        .put("user_question", "Does this mean I have high fall risk or sarcopenia?")
        .put("required_response_policy", "refuse_unsupported_medical_claim_and_offer_pose_based_alternative")
        .put(
            "capability_contract",
            JSONObject()
                .put(
                    "can_judge",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("metric", "visible_movement_quality")
                                .put("confidence_ceiling", 0.82)
                                .put("evidence_refs", JSONArray(listOf("metric.visible_movement_quality"))),
                        )
                    ),
                )
                .put(
                    "cannot_judge",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("metric", "fall_risk")
                                .put("reason", "medical_claim_not_supported")
                                .put("required_evidence", JSONArray(listOf("clinical_assessment"))),
                            JSONObject()
                                .put("metric", "sarcopenia")
                                .put("reason", "medical_claim_not_supported")
                                .put("required_evidence", JSONArray(listOf("clinical_assessment", "body_composition"))),
                            JSONObject()
                                .put("metric", "muscle_activation")
                                .put("reason", "pose_only_proxy")
                                .put("required_evidence", JSONArray(listOf("emg_sensor"))),
                        )
                    ),
                ),
        )
        .put(
            "evidence_dag_compact",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("id", "capability.medical_claim.blocked")
                        .put("type", "capability")
                        .put("metric", "fall_risk_sarcopenia")
                        .put("confidence", 0.0)
                        .put("status", "NOT_SUPPORTED")
                        .put("reason", "Pose-only app cannot diagnose fall risk or sarcopenia."),
                    JSONObject()
                        .put("id", "metric.visible_movement_quality")
                        .put("type", "template_metric")
                        .put("metric", "visible_movement_quality")
                        .put("value", 0.82)
                        .put("confidence", 0.82)
                        .put("status", "OK"),
                )
            ),
        )
        .toString()
}

private fun rawLiteRtSmokePrompt(): String {
    return buildString {
        append("<|turn>system\n")
        append("You are GemmaFit's local evidence router. ")
        append("Return exactly one JSON object with schema {\"function\":\"...\",\"args\":{...}}. ")
        append("The function must be one of: ")
        append(rawSmokeAllowedFunctions.joinToString(", "))
        append(". ")
        append("Do not include markdown or explanation.\n")
        append("<|turn>user\n")
        append("Motion-analysis evidence:\n")
        append(smokeSafetyJson())
        append("\nReturn one JSON function call.\n")
        append("<|turn>model\n")
    }
}

private fun parseFirstJsonObject(raw: String): JSONObject? {
    val trimmed = raw.trim()
    runCatching { return JSONObject(trimmed) }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
}

private fun rawSmokeError(functionName: String, functionAllowed: Boolean): String {
    return when {
        functionName.isBlank() -> "raw_no_json_function"
        !functionAllowed -> "raw_function_not_allowed"
        else -> ""
    }
}

private val rawSmokeAllowedFunctions = setOf(
    "correct_knee_alignment",
    "correct_spinal_alignment",
    "correct_joint_angle",
    "correct_asymmetry",
    "warn_com_offset",
    "warn_rapid_movement",
    "increase_range_of_motion",
    "positive_reinforcement",
    "read_memory",
    "request_memory_update",
    "summarize_trend",
    "refuse_unsupported_question",
    "create_care_activity_log",
    "select_dual_task_prompt",
    "record_dual_task_result",
)

private fun Map<String, Any?>.toJsonObject(): JSONObject {
    val obj = JSONObject()
    forEach { (key, value) -> obj.put(key, value.toJsonValue()) }
    return obj
}

private fun Any?.toJsonValue(): Any {
    return when (this) {
        null -> JSONObject.NULL
        is JSONObject -> this
        is JSONArray -> this
        is Boolean -> this
        is Number -> this
        is String -> this
        is Map<*, *> -> {
            val obj = JSONObject()
            forEach { (key, value) -> obj.put(key?.toString() ?: "null", value.toJsonValue()) }
            obj
        }
        is Iterable<*> -> {
            val arr = JSONArray()
            forEach { arr.put(it.toJsonValue()) }
            arr
        }
        is Array<*> -> {
            val arr = JSONArray()
            forEach { arr.put(it.toJsonValue()) }
            arr
        }
        else -> toString()
    }
}
