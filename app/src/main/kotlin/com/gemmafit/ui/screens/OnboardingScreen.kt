package com.gemmafit.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.BackgroundGradientEnd
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentPage by remember { mutableIntStateOf(0) }

    val pages = listOf(
        OnboardingPage(
            icon = Icons.Filled.FitnessCenter,
            title = "Your Pocket Trainer",
            subtitle = "Real-time form coaching\nwithout leaving your phone",
            description = "AI-powered form coaching that works offline, on your phone.",
        ),
        OnboardingPage(
            icon = Icons.Filled.PhoneAndroid,
            title = "How It Works",
            subtitle = "Your phone sees, AI coaches",
            description = "Camera tracks your movement. AI analyzes joint angles and gives instant voice feedback when form needs correction.",
        ),
        OnboardingPage(
            icon = Icons.Filled.Security,
            title = "100% Private",
            subtitle = "Everything stays on your phone",
            description = "No internet needed. No account required. No video is uploaded for coaching.",
        ),
        OnboardingPage(
            icon = Icons.Filled.Camera,
            title = "Almost Ready",
            subtitle = "We need camera access",
            description = "Camera permission is required to analyze posture in real time.",
            isPermissionPage = true,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, BackgroundGradientEnd),
                ),
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "onboarding_page",
            ) { pageIndex ->
                OnboardingPageContent(pages[pageIndex])
            }

            Spacer(modifier = Modifier.height(48.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                pages.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (index == currentPage) 12.dp else 8.dp)
                            .background(
                                if (index == currentPage) Green else TextSecondary,
                                RoundedCornerShape(50),
                            ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (currentPage < pages.size - 1) {
                        currentPage++
                    } else {
                        onComplete()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (currentPage < pages.size - 1) "Continue" else "Start Workout",
                    color = Background,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            if (currentPage < pages.size - 1) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onComplete) {
                    Text("Skip", color = TextSecondary)
                }
            }
        }
    }
}

data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val description: String,
    val isPermissionPage: Boolean = false,
)

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = page.icon,
            contentDescription = null,
            tint = Green,
            modifier = Modifier.size(80.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = page.title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = page.subtitle,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
            color = Green,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        if (page.isPermissionPage) {
            Spacer(modifier = Modifier.height(24.dp))
            if (!permissionGranted) {
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Grant Camera Access", color = TextPrimary)
                }
            } else {
                Text(
                    text = "Camera access granted",
                    color = Green,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
