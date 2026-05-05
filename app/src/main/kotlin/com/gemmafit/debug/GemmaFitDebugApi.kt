package com.gemmafit.debug

import android.content.Context
import android.util.Log
import com.gemmafit.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    private val lock = Any()
    private val state = LinkedHashMap<String, JSONObject>()
    private val lastLogcatAtMs = LinkedHashMap<String, Long>()
    private var appContext: Context? = null
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
}

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
