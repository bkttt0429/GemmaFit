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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.gemmafit.ui.localization.LocalAppStrings
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.BackgroundGradientEnd
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onOpenVideoMode: () -> Unit = onComplete,
    onOpenSettings: () -> Unit = {},
    onOpenSeniorDemo: (() -> Unit)? = null,
) {
    val copy = LocalAppStrings.current
    var currentPage by remember { mutableIntStateOf(0) }
    val pageCopy = copy.onboardingPages

    val pages = listOf(
        OnboardingPage(
            icon = Icons.Filled.FitnessCenter,
            title = pageCopy[0].title,
            subtitle = pageCopy[0].subtitle,
            description = pageCopy[0].description,
        ),
        OnboardingPage(
            icon = Icons.Filled.PhoneAndroid,
            title = pageCopy[1].title,
            subtitle = pageCopy[1].subtitle,
            description = pageCopy[1].description,
        ),
        OnboardingPage(
            icon = Icons.Filled.Security,
            title = pageCopy[2].title,
            subtitle = pageCopy[2].subtitle,
            description = pageCopy[2].description,
        ),
        OnboardingPage(
            icon = Icons.Filled.Camera,
            title = pageCopy[3].title,
            subtitle = pageCopy[3].subtitle,
            description = pageCopy[3].description,
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
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = copy.settings,
                tint = TextPrimary,
            )
        }

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
                    text = if (currentPage < pages.size - 1) copy.continueAction else copy.startWorkout,
                    color = Background,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onOpenVideoMode,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = Green,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Video Mode",
                    color = Green,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }

            if (currentPage < pages.size - 1) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onComplete) {
                    Text(copy.skip, color = TextSecondary)
                }
            }

            onOpenSeniorDemo?.let { openSenior ->
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = openSenior) {
                    Text("Senior Hero Demo", color = Green, fontWeight = FontWeight.Bold)
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
    val copy = LocalAppStrings.current
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
                    Text(copy.grantCameraAccess, color = TextPrimary)
                }
            } else {
                Text(
                    text = copy.cameraAccessGranted,
                    color = Green,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
