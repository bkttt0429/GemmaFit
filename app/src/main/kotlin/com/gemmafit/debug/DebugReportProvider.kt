package com.gemmafit.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gemmafit.BuildConfig
import java.io.FileNotFoundException

/**
 * adb-readable debug API.
 *
 * Examples:
 *   adb shell content read --uri content://com.gemmafit.debug/report
 *   adb shell content read --uri content://com.gemmafit.debug/events
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
        val bytes = payloadFor(uri).toByteArray(Charsets.UTF_8)
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                output.write(bytes)
            }
        }, "GemmaFitDebugPipe").start()
        return pipe[0]
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val context = context ?: return 0
        if (!BuildConfig.DEBUG) return 0
        return when (kindFor(uri)) {
            "events", "report" -> GemmaFitDebugApi.clearEvents(context)
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
            else -> GemmaFitDebugApi.dumpReport(context)
        }
    }

    private fun kindFor(uri: Uri): String {
        return uri.pathSegments.firstOrNull()?.ifBlank { "report" } ?: "report"
    }
}
