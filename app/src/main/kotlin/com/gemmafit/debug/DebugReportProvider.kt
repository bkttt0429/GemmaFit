package com.gemmafit.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gemmafit.BuildConfig
import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONObject

/**
 * adb-readable debug API.
 *
 * Examples:
 *   adb shell content read --uri content://com.gemmafit.debug/report
 *   adb shell content read --uri content://com.gemmafit.debug/events
 *   adb shell content read --uri content://com.gemmafit.debug/litert_smoke
 *   adb shell content read --uri content://com.gemmafit.debug/litert_effect_smoke
 *   adb shell content read --uri content://com.gemmafit.debug/litert_raw_smoke
 *   adb shell content read --uri 'content://com.gemmafit.debug/litert_prompt_infer?model=official'
 *   adb shell content read --uri 'content://com.gemmafit.debug/litert_image_prompt_infer?image=debug_phone_current.png&model=official'
 *   adb shell content read --uri 'content://com.gemmafit.debug/litert_visual_context_infer?scene=session_scene_anchor.png&panel=session_motionzip_panel.png&model=official'
 *   adb shell content read --uri 'content://com.gemmafit.debug/litert_prewarm?model=official'
 *   adb shell content read --uri content://com.gemmafit.debug/model_invocation_smoke
 *   adb shell content read --uri content://com.gemmafit.debug/layer2_smoke
 *   adb shell content read --uri content://com.gemmafit.debug/care_log
 *   adb shell content read --uri content://com.gemmafit.debug/dual_task
 *   adb shell content read --uri content://com.gemmafit.debug/subjective_checkin
 *   adb shell content read --uri content://com.gemmafit.debug/persona_report
 *   adb shell content read --uri 'content://com.gemmafit.debug/model_readiness?model=official'
 *   adb shell content read --uri content://com.gemmafit.debug/review_frame
 *   adb shell content read --uri content://com.gemmafit.debug/pose_detection_timeline
 *   adb shell content read --uri content://com.gemmafit.debug/no_pose_retry
 *   adb shell content read --uri content://com.gemmafit.debug/person_detector
 *   adb shell content read --uri content://com.gemmafit.debug/motion_zip_packet
 *   adb shell content read --uri content://com.gemmafit.debug/rgba_pipeline_audit
 *   adb shell content read --uri 'content://com.gemmafit.debug/rgba_pipeline_audit?reset=true'
 *   adb shell content read --uri 'content://com.gemmafit.debug/motionzip_model_equivalence?file=model_prompt_pair_compact.jsonl&model=official&backend=auto&max_tokens=512'
 *   adb shell content read --uri 'content://com.gemmafit.debug/video_realtime_smoke?file=senior_chair_stand_cdc.mp4'
 *   adb shell content read --uri content://com.gemmafit.debug/litert_functiongemma_no_tools
 *   adb shell content read --uri content://com.gemmafit.debug/litert_functiongemma_min_tool
 *   adb shell content delete --uri content://com.gemmafit.debug/events
 */
class DebugReportProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { GemmaFitDebugApi.initialize(it) }
        return true
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val payload = payloadFor(uri)
        return MatrixCursor(arrayOf("_id", "kind", "payload")).apply {
            addRow(arrayOf<Any?>(0, kindFor(uri), payload))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains("r")) {
            throw FileNotFoundException("GemmaFit debug API is read-only.")
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val context = context
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    if (
                        context != null &&
                        kindFor(uri) == "litert_prompt_infer" &&
                        uri.getQueryParameter("stream") == "true"
                    ) {
                        GemmaFitDebugApi.writeLiteRtPromptInferenceStream(
                            context = context,
                            requestedName = uri.getQueryParameter("file") ?: uri.pathSegments.getOrNull(1),
                            requestedModel = uri.getQueryParameter("model"),
                            output = output,
                        )
                    } else {
                        val payload = runCatching { payloadFor(uri) }.getOrElse { error ->
                            JSONObject()
                                .put("enabled", true)
                                .put("success", false)
                                .put("error", "debug_payload_failed")
                                .put("error_type", error::class.java.name)
                                .put("error_detail", error.message ?: "unknown")
                                .toString(2)
                        }
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (ignored: IOException) {
                // The adb/content client can close the read side first during long-running
                // LiteRT debug probes. Treat that as a cancelled read, not a debug-process crash.
            }
        }, "GemmaFitDebugPipe").start()
        return pipe[0]
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val context = context ?: return 0
        if (!BuildConfig.DEBUG) return 0
        return when (kindFor(uri)) {
            "events", "report" -> GemmaFitDebugApi.clearEvents(context)
            "rgba_pipeline_audit" -> GemmaFitDebugApi.clearRgbaPipelineAudit(context)
            else -> 0
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun payloadFor(uri: Uri): String {
        val context = context ?: return """{"error":"no_context"}"""
        return when (kindFor(uri)) {
            "events" -> GemmaFitDebugApi.dumpEvents(context)
            "state" -> GemmaFitDebugApi.dumpState(context)
            "litert_smoke" -> GemmaFitDebugApi.runLiteRtSmoke(context)
            "litert_effect_smoke" -> GemmaFitDebugApi.runLiteRtEffectSmoke(context)
            "litert_raw_smoke" -> GemmaFitDebugApi.runLiteRtRawSmoke(context)
            "litert_prompt_infer" -> GemmaFitDebugApi.runLiteRtPromptInference(
                context = context,
                requestedName = uri.getQueryParameter("file") ?: uri.pathSegments.getOrNull(1),
                requestedModel = uri.getQueryParameter("model"),
                constrained = uri.getQueryParameter("constrained") == "true",
            )
            "litert_image_prompt_infer" -> GemmaFitDebugApi.runLiteRtImagePromptInference(
                context = context,
                requestedImageName = uri.getQueryParameter("image") ?: uri.pathSegments.getOrNull(1),
                requestedModel = uri.getQueryParameter("model"),
            )
            "litert_visual_context_infer" -> GemmaFitDebugApi.runLiteRtVisualContextInference(
                context = context,
                requestedImageName = uri.getQueryParameter("image"),
                requestedSceneName = uri.getQueryParameter("scene"),
                requestedPanelName = uri.getQueryParameter("panel") ?: uri.pathSegments.getOrNull(1),
                requestedModel = uri.getQueryParameter("model"),
                requestedTimeoutMs = uri.getQueryParameter("timeout_ms"),
            )
            "litert_prewarm" -> GemmaFitDebugApi.runLiteRtPrewarm(
                context = context,
                requestedModel = uri.getQueryParameter("model"),
                requestedMaxNumImages = uri.getQueryParameter("max_num_images"),
            )
            "model_invocation_smoke" -> GemmaFitDebugApi.runModelInvocationSmoke()
            "layer2_smoke" -> GemmaFitDebugApi.runLayer2Smoke()
            "care_log" -> GemmaFitDebugApi.dumpSection(context, "care_log")
            "dual_task" -> GemmaFitDebugApi.dumpSection(context, "dual_task")
            "subjective_checkin" -> GemmaFitDebugApi.dumpSection(context, "subjective_checkin")
            "persona_report" -> GemmaFitDebugApi.dumpSection(context, "persona_report")
            "model_readiness" -> GemmaFitDebugApi.dumpModelReadiness(
                context = context,
                requestedModel = uri.getQueryParameter("model"),
            )
            "review_frame" -> GemmaFitDebugApi.dumpSection(context, "review_frame")
            "pose_detection_timeline" -> GemmaFitDebugApi.dumpSection(context, "pose_detection_timeline")
            "no_pose_retry" -> GemmaFitDebugApi.dumpSection(context, "no_pose_retry")
            "person_detector" -> GemmaFitDebugApi.dumpSection(context, "person_detector")
            "motion_zip_packet" -> GemmaFitDebugApi.dumpSection(context, "motion_zip_packet")
            "rgba_pipeline_audit" -> GemmaFitDebugApi.dumpRgbaPipelineAudit(
                context = context,
                reset = uri.getQueryParameter("reset") == "true",
            )
            "motionzip_model_equivalence" -> GemmaFitDebugApi.runMotionZipModelEquivalence(
                context = context,
                requestedName = uri.getQueryParameter("file") ?: uri.pathSegments.getOrNull(1),
                requestedModel = uri.getQueryParameter("model"),
                requestedBackend = uri.getQueryParameter("backend"),
                requestedMaxTokens = uri.getQueryParameter("max_tokens")?.toIntOrNull(),
            )
            "video_realtime_smoke" -> GemmaFitDebugApi.runVideoRealtimeSmoke(
                context = context,
                requestedName = uri.getQueryParameter("file") ?: uri.pathSegments.getOrNull(1),
            )
            "litert_functiongemma_no_tools" -> GemmaFitDebugApi.runFunctionGemmaNoToolsSmoke(context)
            "litert_functiongemma_min_tool" -> GemmaFitDebugApi.runFunctionGemmaMinimalToolSmoke(context)
            else -> GemmaFitDebugApi.dumpReport(context)
        }
    }

    private fun kindFor(uri: Uri): String {
        return uri.pathSegments.firstOrNull()?.ifBlank { "report" } ?: "report"
    }
}
