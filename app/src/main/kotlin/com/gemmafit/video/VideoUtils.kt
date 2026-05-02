package com.gemmafit.video

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

object VideoUtils {

    fun getDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                    return cursor.getString(nameIndex)
                }
            }
        }
        return uri.pathSegments.lastOrNull() ?: uri.toString()
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val projection = arrayOf(OpenableColumns.SIZE)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
                    return cursor.getLong(sizeIndex)
                }
            }
        }
        return 0L
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    fun isVideoUri(uri: Uri): Boolean {
        val path = uri.path ?: return false
        val extensions = listOf(".mp4", ".avi", ".mov", ".mkv", ".3gp", ".webm", ".flv", ".wmv")
        return extensions.any { path.lowercase().endsWith(it) }
    }
}