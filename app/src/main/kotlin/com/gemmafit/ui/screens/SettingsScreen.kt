package com.gemmafit.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.settings.AppCueStyle
import com.gemmafit.settings.AppLanguage
import com.gemmafit.settings.AppSettings
import com.gemmafit.settings.AppTrainingMode
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onPreviewVoice: () -> Unit,
    onClearLocalSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = SettingsCopy.forLanguage(settings.language)
    val scale = settings.fontScale

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsHeader(
            title = copy.title,
            subtitle = copy.subtitle,
            onBack = onBack,
            scale = scale,
        )

        SettingsSection(
            title = copy.languageTitle,
            subtitle = copy.languageSubtitle,
            icon = Icons.Filled.Language,
            scale = scale,
        ) {
            OptionRow(
                options = listOf(
                    AppLanguage.SYSTEM to copy.systemLanguage,
                    AppLanguage.ENGLISH to "English",
                    AppLanguage.TRADITIONAL_CHINESE to "繁體中文",
                ),
                selected = settings.language,
                onSelected = { onSettingsChange(settings.copy(language = it)) },
            )
        }

        SettingsSection(
            title = copy.modeTitle,
            subtitle = copy.modeSubtitle,
            icon = Icons.Filled.FitnessCenter,
            scale = scale,
        ) {
            OptionRow(
                options = listOf(
                    AppTrainingMode.GENERAL to copy.generalMode,
                    AppTrainingMode.SENIOR to copy.seniorMode,
                ),
                selected = settings.trainingMode,
                onSelected = {
                    onSettingsChange(settings.withTrainingMode(it))
                },
            )
        }

        SettingsSection(
            title = copy.voiceTitle,
            subtitle = copy.voiceSubtitle,
            icon = Icons.Filled.VolumeUp,
            scale = scale,
        ) {
            ToggleRow(
                title = copy.voiceEnabled,
                subtitle = copy.voiceEnabledSubtitle,
                checked = settings.voiceEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(voiceEnabled = it)) },
                scale = scale,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = copy.voiceLanguage,
                color = TextSecondary,
                fontSize = (14 * scale).sp,
            )
            Spacer(Modifier.height(8.dp))
            OptionRow(
                options = listOf(
                    AppLanguage.SYSTEM to copy.systemLanguage,
                    AppLanguage.ENGLISH to "English",
                    AppLanguage.TRADITIONAL_CHINESE to "中文",
                ),
                selected = settings.voiceLanguage,
                onSelected = { onSettingsChange(settings.copy(voiceLanguage = it)) },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "${copy.voiceSpeed}: ${settings.voiceSpeed.formatOneDecimal()}x",
                color = TextPrimary,
                fontSize = (16 * scale).sp,
                fontWeight = FontWeight.Bold,
            )
            Slider(
                value = settings.voiceSpeed,
                onValueChange = {
                    onSettingsChange(settings.copy(voiceSpeed = it.coerceIn(0.7f, 1.3f)))
                },
                valueRange = 0.7f..1.3f,
                steps = 5,
            )
            OutlinedButton(
                onClick = onPreviewVoice,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(copy.previewVoice)
            }
        }

        SettingsSection(
            title = copy.accessibilityTitle,
            subtitle = copy.accessibilitySubtitle,
            icon = Icons.Filled.AccessibilityNew,
            scale = scale,
        ) {
            Text(
                text = "${copy.fontSize}: ${copy.fontScaleLabel(settings.fontScale)}",
                color = TextPrimary,
                fontSize = (16 * scale).sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            OptionRow(
                options = listOf(
                    1.0f to copy.normal,
                    1.5f to copy.large,
                    2.0f to copy.extraLarge,
                ),
                selected = settings.fontScale,
                onSelected = { onSettingsChange(settings.copy(fontScale = it)) },
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = copy.highContrast,
                subtitle = copy.highContrastSubtitle,
                checked = settings.highContrast,
                onCheckedChange = { onSettingsChange(settings.copy(highContrast = it)) },
                scale = scale,
            )
            ToggleRow(
                title = copy.voiceFirst,
                subtitle = copy.voiceFirstSubtitle,
                checked = settings.voiceFirst,
                onCheckedChange = { onSettingsChange(settings.copy(voiceFirst = it)) },
                scale = scale,
            )
            ToggleRow(
                title = copy.reduceMotion,
                subtitle = copy.reduceMotionSubtitle,
                checked = settings.reduceMotion,
                onCheckedChange = { onSettingsChange(settings.copy(reduceMotion = it)) },
                scale = scale,
            )
        }

        SettingsSection(
            title = copy.coachingTitle,
            subtitle = copy.coachingSubtitle,
            icon = Icons.Filled.Settings,
            scale = scale,
        ) {
            OptionRow(
                options = listOf(
                    AppCueStyle.ENCOURAGING to copy.encouraging,
                    AppCueStyle.TERSE to copy.terse,
                    AppCueStyle.DETAILED to copy.detailed,
                ),
                selected = settings.cueStyle,
                onSelected = { onSettingsChange(settings.copy(cueStyle = it)) },
            )
        }

        SettingsSection(
            title = copy.privacyTitle,
            subtitle = copy.privacySubtitle,
            icon = Icons.Filled.Security,
            scale = scale,
        ) {
            PrivacyBullet(copy.localOnly, scale)
            PrivacyBullet(copy.noRawVideo, scale)
            PrivacyBullet(copy.nonClinical, scale)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClearLocalSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(copy.clearSettings, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = (28 * scale).sp,
                lineHeight = (32 * scale).sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = (14 * scale).sp,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    scale: Float,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Green.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = Green)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = (18 * scale).sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = (13 * scale).sp,
                        lineHeight = (17 * scale).sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun <T> OptionRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Button(
                onClick = { onSelected(value) },
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Green else Background,
                    contentColor = if (active) Color.Black else TextPrimary,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = label,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = (16 * scale).sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = (13 * scale).sp,
                lineHeight = (17 * scale).sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacyBullet(text: String, scale: Float) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("•", color = Orange, fontSize = (16 * scale).sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = (14 * scale).sp,
            lineHeight = (19 * scale).sp,
        )
    }
}

private fun Float.formatOneDecimal(): String = String.format("%.1f", this)

@Immutable
private data class SettingsCopy(
    val title: String,
    val subtitle: String,
    val languageTitle: String,
    val languageSubtitle: String,
    val systemLanguage: String,
    val modeTitle: String,
    val modeSubtitle: String,
    val generalMode: String,
    val seniorMode: String,
    val voiceTitle: String,
    val voiceSubtitle: String,
    val voiceEnabled: String,
    val voiceEnabledSubtitle: String,
    val voiceLanguage: String,
    val voiceSpeed: String,
    val previewVoice: String,
    val accessibilityTitle: String,
    val accessibilitySubtitle: String,
    val fontSize: String,
    val normal: String,
    val large: String,
    val extraLarge: String,
    val highContrast: String,
    val highContrastSubtitle: String,
    val voiceFirst: String,
    val voiceFirstSubtitle: String,
    val reduceMotion: String,
    val reduceMotionSubtitle: String,
    val coachingTitle: String,
    val coachingSubtitle: String,
    val encouraging: String,
    val terse: String,
    val detailed: String,
    val privacyTitle: String,
    val privacySubtitle: String,
    val localOnly: String,
    val noRawVideo: String,
    val nonClinical: String,
    val clearSettings: String,
) {
    fun fontScaleLabel(value: Float): String = when (value) {
        1.0f -> normal
        1.5f -> large
        else -> extraLarge
    }

    companion object {
        fun forLanguage(language: AppLanguage): SettingsCopy {
            return if (language == AppLanguage.TRADITIONAL_CHINESE) zhTw else en
        }

        private val en = SettingsCopy(
            title = "Settings",
            subtitle = "Language, voice, accessibility, and privacy",
            languageTitle = "Language",
            languageSubtitle = "Controls app text for GemmaFit screens.",
            systemLanguage = "System",
            modeTitle = "Training mode",
            modeSubtitle = "Senior mode increases scale and slows voice cues.",
            generalMode = "General",
            seniorMode = "Senior",
            voiceTitle = "Coach voice",
            voiceSubtitle = "Offline cues only. Voice can be disabled at any time.",
            voiceEnabled = "Voice feedback",
            voiceEnabledSubtitle = "Speak key coaching cues after confidence gates pass.",
            voiceLanguage = "Voice language",
            voiceSpeed = "Voice speed",
            previewVoice = "Preview cue",
            accessibilityTitle = "Accessibility",
            accessibilitySubtitle = "Make repeated workouts easier to read and control.",
            fontSize = "Font size",
            normal = "Normal",
            large = "Large",
            extraLarge = "XL",
            highContrast = "High contrast",
            highContrastSubtitle = "Keep controls readable over camera preview.",
            voiceFirst = "Voice-first controls",
            voiceFirstSubtitle = "Prefer spoken cues and larger touch targets.",
            reduceMotion = "Reduce motion",
            reduceMotionSubtitle = "Limit decorative transitions during coaching.",
            coachingTitle = "Cue style",
            coachingSubtitle = "Controls how concise the coaching text should be.",
            encouraging = "Encourage",
            terse = "Terse",
            detailed = "Detail",
            privacyTitle = "Privacy & memory",
            privacySubtitle = "Local evidence records, not medical assumptions.",
            localOnly = "Core coaching runs locally on the device.",
            noRawVideo = "Raw video is not stored by default.",
            nonClinical = "Exports and summaries are movement-quality notes, not diagnosis.",
            clearSettings = "Clear local settings",
        )

        private val zhTw = SettingsCopy(
            title = "設定",
            subtitle = "語言、語音、輔助使用與隱私",
            languageTitle = "語言",
            languageSubtitle = "控制 GemmaFit 介面文字。",
            systemLanguage = "跟隨系統",
            modeTitle = "訓練模式",
            modeSubtitle = "長者模式會放大介面並放慢語音提示。",
            generalMode = "一般",
            seniorMode = "長者",
            voiceTitle = "教練語音",
            voiceSubtitle = "只播放離線提示，可隨時關閉。",
            voiceEnabled = "語音回饋",
            voiceEnabledSubtitle = "通過信心閘門後播放重點提示。",
            voiceLanguage = "語音語言",
            voiceSpeed = "語速",
            previewVoice = "試聽提示",
            accessibilityTitle = "輔助使用",
            accessibilitySubtitle = "讓重複訓練更容易閱讀與操作。",
            fontSize = "字級",
            normal = "標準",
            large = "大",
            extraLarge = "特大",
            highContrast = "高對比",
            highContrastSubtitle = "讓相機畫面上的控制保持清楚。",
            voiceFirst = "語音優先",
            voiceFirstSubtitle = "偏好語音提示與大型觸控目標。",
            reduceMotion = "減少動畫",
            reduceMotionSubtitle = "訓練時減少裝飾性轉場。",
            coachingTitle = "提示風格",
            coachingSubtitle = "控制教練文字的簡潔程度。",
            encouraging = "鼓勵",
            terse = "簡短",
            detailed = "詳細",
            privacyTitle = "隱私與記憶",
            privacySubtitle = "保存動作證據，不保存醫療假設。",
            localOnly = "核心訓練回饋在裝置本機執行。",
            noRawVideo = "預設不保存原始影片。",
            nonClinical = "匯出與摘要是動作品質紀錄，不是診斷。",
            clearSettings = "清除本機設定",
        )
    }
}
