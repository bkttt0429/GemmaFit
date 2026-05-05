package com.gemmafit

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gemmafit.settings.AppSettings
import com.gemmafit.settings.AppSettingsRepository
import com.gemmafit.ui.screens.OnboardingScreen
import com.gemmafit.ui.screens.SettingsScreen
import com.gemmafit.ui.screens.SummaryScreen
import com.gemmafit.ui.screens.WorkoutScreen
import com.gemmafit.video.SessionSummary
import com.gemmafit.voice.CoachVoice

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Workout : Screen("workout")
    data object Summary : Screen("summary")
    data object Settings : Screen("settings")
}

@Composable
fun GemmaFitApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val settingsRepository = remember { AppSettingsRepository(context) }
    val previewVoice = remember { CoachVoice(context.applicationContext) }
    var appSettings by remember { mutableStateOf(settingsRepository.load()) }
    var currentSession by remember { mutableStateOf(SessionSummary()) }

    DisposableEffect(previewVoice) {
        onDispose { previewVoice.shutdown() }
    }

    fun saveSettings(next: AppSettings) {
        appSettings = next
        settingsRepository.save(next)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Onboarding.route,
        modifier = modifier,
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.Workout.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
            )
        }

        composable(Screen.Workout.route) {
            WorkoutScreen(
                onViewSummary = { session ->
                    currentSession = session
                    navController.navigate(Screen.Summary.route)
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                settings = appSettings,
            )
        }

        composable(Screen.Summary.route) {
            SummaryScreen(
                session = currentSession,
                onBack = { navController.popBackStack() },
                onNewSession = {
                    navController.navigate(Screen.Workout.route) {
                        popUpTo(Screen.Workout.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                settings = appSettings,
                onSettingsChange = { saveSettings(it) },
                onBack = { navController.popBackStack() },
                onPreviewVoice = { previewVoice.preview(appSettings) },
                onClearLocalSettings = {
                    saveSettings(AppSettings())
                    Toast.makeText(
                        context,
                        if (appSettings.isChinese) "已清除本機設定" else "Local settings cleared",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}
