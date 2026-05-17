package com.gemmafit

import android.widget.Toast
import android.net.Uri
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
import com.gemmafit.ui.localization.AppStrings
import com.gemmafit.ui.localization.ProvideAppStrings
import com.gemmafit.ui.screens.OnboardingScreen
import com.gemmafit.ui.screens.SettingsScreen
import com.gemmafit.ui.screens.SummaryScreen
import com.gemmafit.ui.screens.WorkoutScreen
import com.gemmafit.ui.screens.senior.SeniorDemoRoute
import com.gemmafit.video.SessionSummary
import com.gemmafit.voice.CoachVoice

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Workout : Screen("workout")
    data object Summary : Screen("summary")
    data object Settings : Screen("settings")
    data object SeniorDemo : Screen("senior_demo")
}

@Composable
fun GemmaFitApp(
    modifier: Modifier = Modifier,
    initialVideoUri: Uri? = null,
    initialVideoLabel: String? = null,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val settingsRepository = remember { AppSettingsRepository(context) }
    val previewVoice = remember { CoachVoice(context.applicationContext) }
    var appSettings by remember { mutableStateOf(settingsRepository.load()) }
    var currentSession by remember { mutableStateOf(SessionSummary()) }
    var openVideoPickerOnWorkout by remember { mutableStateOf(false) }

    DisposableEffect(previewVoice) {
        onDispose { previewVoice.shutdown() }
    }

    fun saveSettings(next: AppSettings) {
        appSettings = next
        settingsRepository.save(next)
    }

    fun navigateToWorkout() {
        navController.navigate(Screen.Workout.route) {
            launchSingleTop = true
        }
    }

    fun navigateToSettings() {
        navController.navigate(Screen.Settings.route) {
            launchSingleTop = true
        }
    }

    fun navigateToSeniorDemo() {
        navController.navigate(Screen.SeniorDemo.route) {
            launchSingleTop = true
        }
    }

    fun navigateBackOrWorkout() {
        if (!navController.popBackStack()) {
            navigateToWorkout()
        }
    }

    ProvideAppStrings(language = appSettings.language) {
        NavHost(
            navController = navController,
            startDestination = if (initialVideoUri != null) Screen.Workout.route else Screen.Onboarding.route,
            modifier = modifier,
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onComplete = { navigateToWorkout() },
                    onOpenVideoMode = {
                        openVideoPickerOnWorkout = true
                        navigateToWorkout()
                    },
                    onOpenSettings = { navigateToSettings() },
                    onOpenSeniorDemo = {
                        saveSettings(appSettings.withTrainingMode(com.gemmafit.settings.AppTrainingMode.SENIOR))
                        navigateToSeniorDemo()
                    },
                )
            }

            composable(Screen.Workout.route) {
                WorkoutScreen(
                    onViewSummary = { session ->
                        currentSession = session
                        navController.navigate(Screen.Summary.route)
                    },
                    onOpenSettings = { navigateToSettings() },
                    settings = appSettings,
                    initialVideoUri = initialVideoUri,
                    initialVideoLabel = initialVideoLabel,
                    openVideoPickerOnStart = openVideoPickerOnWorkout,
                    onVideoPickerStartConsumed = { openVideoPickerOnWorkout = false },
                )
            }

            composable(Screen.Summary.route) {
                SummaryScreen(
                    session = currentSession,
                    onBack = { navigateBackOrWorkout() },
                    onNewSession = {
                        navController.navigate(Screen.Workout.route) {
                            popUpTo(Screen.Workout.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    settings = appSettings,
                    onSettingsChange = { saveSettings(it) },
                    onBack = { navigateBackOrWorkout() },
                    onPreviewVoice = { previewVoice.preview(appSettings) },
                    onClearLocalSettings = {
                        val copy = AppStrings.forLanguage(appSettings.language)
                        saveSettings(AppSettings())
                        Toast.makeText(
                            context,
                            if (copy === AppStrings.zhTw) "已清除本機設定" else "Local settings cleared",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }

            composable(Screen.SeniorDemo.route) {
                SeniorDemoRoute(
                    onExit = { navigateBackOrWorkout() },
                )
            }
        }
    }
}
