package com.gemmafit.video

import android.app.Application
import com.gemmafit.jni.LLMBridge
import java.io.File

object CoachModelResolver {
    const val OFFICIAL_E2B_MODEL_NAME = "gemma-4-E2B-it.litertlm"
    const val GEMMAFIT_V5_MODEL_NAME = "gemmafit-v5-e2b-evidence-router.litertlm"

    private const val EDGE_GALLERY_OFFICIAL_E2B_MODEL_NAME =
        "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"

    private val liteRtModelNames = listOf(
        // P0 baseline: official Gemma E2B initializes on Pixel 8 Pro and stays the default.
        OFFICIAL_E2B_MODEL_NAME,
        EDGE_GALLERY_OFFICIAL_E2B_MODEL_NAME,
        // Fine-tuned GemmaFit router is selectable for P1 quality comparison.
        GEMMAFIT_V5_MODEL_NAME,
        "gemmafit-v3-evidence-router.litertlm",
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

    fun resolveLiteRtModelPath(app: Application, requestedModel: String? = null): String? {
        return firstExisting(liteRtCandidates(app, requestedModel)) { path ->
            val file = File(path)
            file.exists() && file.canRead() && file.length() > 0L
        }
    }

    fun resolveGgufModelPath(app: Application): String {
        val candidates = ggufCandidates(app)
        return candidates.firstOrNull { LLMBridge.validateModel(it) } ?: candidates.first()
    }

    internal fun liteRtCandidates(app: Application, requestedModel: String? = null): List<String> {
        val appDirs = listOfNotNull(
            app.filesDir,
            app.getExternalFilesDir(null),
        )
        val modelNames = liteRtModelPriority(requestedModel)
        return buildList {
            appDirs.forEach { dir ->
                modelNames.forEach { name -> add(File(dir, name).absolutePath) }
            }
            modelNames.forEach { name ->
                add("/storage/emulated/0/Android/data/com.gemmafit/files/$name")
                add("/sdcard/Android/data/com.gemmafit/files/$name")
            }
        }.distinct()
    }

    internal fun firstExisting(
        candidates: List<String>,
        exists: (String) -> Boolean,
    ): String? {
        return candidates.firstOrNull(exists)
    }

    internal fun liteRtModelPriority(requestedModel: String? = null): List<String> {
        val requestedNames = liteRtRequestedModelNames(requestedModel)
        if (requestedNames.isEmpty()) return liteRtModelNames
        return (requestedNames + liteRtModelNames).distinct()
    }

    internal fun liteRtRequestedModelNames(requestedModel: String?): List<String> {
        val requested = requestedModel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val normalized = File(requested).name
            .removeSuffix(".litertlm")
            .lowercase()
            .replace('_', '-')
        return when (normalized) {
            "official",
            "official-e2b",
            "e2b",
            "gemma-e2b",
            "gemma-4-e2b-it",
            "gemma4-2b-v09-obfus-fix-all-modalities-thinking" -> {
                listOf(OFFICIAL_E2B_MODEL_NAME, EDGE_GALLERY_OFFICIAL_E2B_MODEL_NAME)
            }
            "v5",
            "gemmafit-v5",
            "gemmafit-v5-e2b",
            "gemmafit-v5-e2b-evidence-router" -> listOf(GEMMAFIT_V5_MODEL_NAME)
            else -> listOf(File(requested).name)
        }
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
