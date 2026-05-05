package com.gemmafit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import com.gemmafit.debug.GemmaFitDebugApi
import com.gemmafit.ui.theme.GemmaFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GemmaFitDebugApi.initialize(applicationContext)
        GemmaFitDebugApi.record(
            category = "app",
            message = "main_activity_created",
            data = mapOf("debug_build" to BuildConfig.DEBUG),
        )
        setContent {
            GemmaFitTheme {
                GemmaFitApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
