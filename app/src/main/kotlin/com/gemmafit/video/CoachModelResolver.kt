package com.gemmafit.video

import android.app.Application
import com.gemmafit.jni.LLMBridge
import java.io.File

object CoachModelResolver {
    private val liteRtModelNames = listOf(
        "gemmafit-v2-fc.litertlm",
    )

    private val ggufModelNames = listOf(
        "gemma4-e2b-Q4_K_M.gguf",
        "gemma4-e2b-q4.gguf",
        "gemma4-e4b-q4.gguf",
        "gemma4-e4b-Q4_K_M.gguf",
        "gemmafit-v2-q4_k_m.gguf",
        "gemmafit-q4_k_m.gguf",
    )

    fun resolveLiteRtModelPath(app: Application): String? {
        return firstExisting(liteRtCandidates(app)) { path ->
            val file = File(path)
            file.exists() && file.canRead() && file.length() > 0L
        }
    }

    fun resolveGgufModelPath(app: Application): String {
        val candidates = ggufCandidates(app)
        return candidates.firstOrNull { LLMBridge.validateModel(it) } ?: candidates.first()
    }

    internal fun liteRtCandidates(app: Application): List<String> {
        val appDirs = listOfNotNull(
            app.filesDir,
            app.getExternalFilesDir(null),
        )
        return buildList {
            appDirs.forEach { dir ->
                liteRtModelNames.forEach { name -> add(File(dir, name).absolutePath) }
            }
            add("/storage/emulated/0/Android/data/com.gemmafit/files/gemmafit-v2-fc.litertlm")
            add("/sdcard/Android/data/com.gemmafit/files/gemmafit-v2-fc.litertlm")
        }.distinct()
    }

    internal fun firstExisting(
        candidates: List<String>,
        exists: (String) -> Boolean,
    ): String? {
        return candidates.firstOrNull(exists)
    }

    private fun ggufCandidates(app: Application): List<String> {
        val appDirs = listOfNotNull(
            app.filesDir,
            app.getExternalFilesDir(null),
        )
        return buildList {
            appDirs.forEach { dir ->
                ggufModelNames.forEach { name -> add(File(dir, name).absolutePath) }
            }
            add("/storage/emulated/0/Android/data/com.gemmafit/files/gemma4-e2b-Q4_K_M.gguf")
            add("/sdcard/Android/data/com.gemmafit/files/gemma4-e2b-Q4_K_M.gguf")
            add("/storage/emulated/0/Android/data/com.gemmafit/files/gemma4-e4b-q4.gguf")
            add("/sdcard/Android/data/com.gemmafit/files/gemma4-e4b-q4.gguf")
        }.distinct()
    }
}
