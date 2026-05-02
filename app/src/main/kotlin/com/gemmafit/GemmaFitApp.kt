package com.gemmafit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gemmafit.ui.screens.OnboardingScreen
import com.gemmafit.ui.screens.SummaryScreen
import com.gemmafit.ui.screens.WorkoutScreen
import com.gemmafit.ui.theme.GemmaFitTheme
import com.gemmafit.video.SessionSummary

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Workout : Screen("workout")
    data object Summary : Screen("summary")
}

@Composable
fun GemmaFitApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var currentSession by remember { mutableStateOf(SessionSummary()) }

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
                }
            )
        }

        composable(Screen.Workout.route) {
            WorkoutScreen(
                onViewSummary = { session ->
                    currentSession = session
                    navController.navigate(Screen.Summary.route)
                },
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
    }
}
