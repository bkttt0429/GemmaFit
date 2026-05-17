package com.gemmafit

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import com.gemmafit.debug.GemmaFitDebugApi
import com.gemmafit.ui.theme.GemmaFitTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GemmaFitDebugApi.initialize(applicationContext)
        GemmaFitDebugApi.record(
            category = "app",
            message = "main_activity_created",
            data = mapOf("debug_build" to BuildConfig.DEBUG),
        )
        GemmaFitDebugApi.startAppLaunchLiteRtPrewarm(
            applicationContext,
            requestedModel = "official",
            requestedMaxNumImages = "1",
        )
        val debugVideoUri = debugVideoUriFromIntent()
        setContent {
            GemmaFitTheme {
                GemmaFitApp(
                    modifier = Modifier.fillMaxSize(),
                    initialVideoUri = debugVideoUri,
                    initialVideoLabel = debugVideoUri?.toString(),
                )
            }
        }
    }

    private fun debugVideoUriFromIntent(): Uri? {
        if (!BuildConfig.DEBUG) return null
        val filePath = intent.getStringExtra("debug_video_file")?.takeIf { it.isNotBlank() }
        if (filePath != null) return Uri.fromFile(File(filePath))
        return intent.data?.takeIf { intent.type?.startsWith("video/") == true }
    }
}
