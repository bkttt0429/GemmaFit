package com.gemmafit.ui.screens.video

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.localization.LocalAppStrings
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.CoachBoundaryState
import com.gemmafit.video.CoachBoundaryKind
import com.gemmafit.video.CoachModelResolver
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.ModelReadinessSnapshot
import com.gemmafit.video.ReviewCue
import com.gemmafit.video.SessionCoachModelStatus
import com.gemmafit.video.SubjectLockStatus
import com.gemmafit.video.TrustUiMapper
import com.gemmafit.video.VideoPhase
import kotlinx.coroutines.delay

private const val REVIEW_ADVICE_PLAYING_COOLDOWN_MS = 1_200L
private const val REVIEW_ADVICE_MIN_DISPLAY_MS = 1_800L
private const val REVIEW_ADVICE_CRITICAL_HOLD_MS = 2_000L
private const val REVIEW_ADVICE_SCRUB_IDLE_MS = 300L
private const val REVIEW_INFO_PLAYING_COOLDOWN_MS = 1_200L
private const val REVIEW_INFO_SCRUB_IDLE_MS = 180L

private data class ReviewAdjustmentAdvice(
    val text: String,
    val eventKey: String,
    val immediate: Boolean = false,
    val warningClass: String = "none",
    val limitedClass: String = "ok",
)

private data class ReviewAdjustmentStatusUi(
    val label: String,
    val detail: String,
    val color: Color,
    val animated: Boolean,
)

private data class ReviewInfoUi(
    val frameLabel: String,
    val primary: String,
    val secondary: String,
)

/**
 * Video file analysis dashboard.
 *
 * Layout architecture:
 *   ┌─ CompactTopBar (fixed, minimal) ─┐
 *   ├─ Hero Section (~45% height) ─────┤  Video + StatusHero
 *   ├─ Scrollable Detail (~55%) ───────┤  Scrubber, Metrics, Coach, Evidence
 *   └─ MinimalBottomBar (fixed) ───────┘
 */
@Composable
fun VideoAnalysisLayout(
    live: LiveWorkoutState,
    isPaused: Boolean,
    isProcessing: Boolean,
    videoUri: Uri?,
    analysisProgress: Float = 0f,
    analysisCurrentFrame: Int = 0,
    analysisTotalFrames: Int = 0,
    analysisLabel: String = "",
    showTrajectory: Boolean = false,
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTrajectory: () -> Unit = {},
    onResetCamera: () -> Unit,
    videoAudioEnabled: Boolean = false,
    videoAudioVolume: Float = 0f,
    onToggleVideoAudio: () -> Unit = {},
    onVideoAudioVolumeChange: (Float) -> Unit = {},
    onPrevFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onJumpToLatestFrame: () -> Unit,
    onPlaybackPosition: (Long) -> Unit,
    onPlaybackLimitReached: () -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
    onReanalyzeSelectedSubject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    val context = LocalContext.current.applicationContext
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    val modelReadiness = remember(
        context,
        live.coachInsight.backend,
        live.coachInsight.fallback,
        live.coachInsight.selectionBasis,
    ) {
        val app = context as? Application
        ModelReadinessSnapshot.from(
            liteRtModelPath = app?.let { CoachModelResolver.resolveLiteRtModelPath(it) },
            backend = live.coachInsight.backend,
            fallback = live.coachInsight.fallback,
            fallbackReason = live.coachInsight.selectionBasis,
        )
    }

    // Haptic feedback on critical warnings
    val criticalCount = live.activeWarnings.count {
        it.severity == "critical" || it.severity == "high"
    }
    androidx.compose.runtime.LaunchedEffect(criticalCount) {
        if (criticalCount > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val canPlayProcessedVideo = videoUri != null && live.totalFramesAnalyzed > 0
    val isReviewPlaying = !isPaused && canPlayProcessedVideo
    val isPlaying = isReviewPlaying && live.totalFramesAnalyzed > 1
    val playbackLimitMs = if (isProcessing) {
        live.latestProcessedTimestampMs
    } else {
        Long.MAX_VALUE
    }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val coachSourceBadge = TrustUiMapper.sourceBadge(
        backend = live.coachInsight.backend,
        fallback = live.coachInsight.fallback,
    )
    val coachBoundary = TrustUiMapper.coachBoundaryState(live)
    val isGeneratingAiSummary = live.coachInsight.modelStatus == SessionCoachModelStatus.PENDING
    val summaryReady = !isProcessing && !isGeneratingAiSummary && live.totalFramesAnalyzed > 0
    val currentReviewCue = nearestReviewCue(live.reviewCues, live.currentFrameIndex)
    val rawReviewAdvice = reviewAdjustmentAdvice(live, coachBoundary)
    val reviewAdviceText = rememberStableReviewAdjustmentText(
        rawAdvice = rawReviewAdvice,
        frameTimestampMs = live.currentFrameTimestampMs,
        frameIndex = live.currentFrameIndex,
        isPlaying = isPlaying,
    )
    val reviewAdviceStatus = reviewAdjustmentStatusUi(
        advice = rawReviewAdvice,
        boundary = coachBoundary,
        isPlaying = isPlaying,
    )
    val effectiveCoachMessage = if (isPlaying && live.coachPriority !in setOf("high", "critical")) {
        ""
    } else {
        live.coachMessage
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        // ── Compact top bar ─────────────────────────────────────────
        CompactTopBar(
            onPickVideo = onPickVideo,
            onOpenSettings = onOpenSettings,
            showTrajectory = showTrajectory,
            onToggleTrajectory = onToggleTrajectory,
            onResetCamera = onResetCamera,
        )

        // ── Hero section (fixed ~45%) ───────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val useLoopingAnalysisProgress = shouldUseLoopingLoadingProgress(analysisLabel)
            val analysisStatusText = if (isProcessing && useLoopingAnalysisProgress) {
                copy.loadingLabel(analysisLabel)
            } else {
                ""
            }
            VideoHero(
                live = live,
                videoUri = videoUri,
                isPlaying = isReviewPlaying,
                isAnalyzing = isProcessing,
                analysisProgress = analysisProgress,
                analysisFrameText = if (useLoopingAnalysisProgress) {
                    ""
                } else {
                    analysisFrameText(
                        frameLabel = if (isProcessing) "processing" else copy.frames,
                        currentFrame = analysisCurrentFrame,
                        totalFrames = analysisTotalFrames,
                        includeTotal = true,
                    )
                },
                analysisStatusText = analysisStatusText,
                useLoopingAnalysisProgress = useLoopingAnalysisProgress,
                maxPlaybackPositionMs = playbackLimitMs,
                videoAudioEnabled = videoAudioEnabled,
                videoAudioVolume = videoAudioVolume,
                showTrajectory = showTrajectory,
                onPlaybackPosition = onPlaybackPosition,
                onPlaybackLimitReached = onPlaybackLimitReached,
                onSubjectTap = onSubjectTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (live.totalFramesAnalyzed > 0) {
                val timelineTotal = if (analysisTotalFrames > 0) {
                    analysisTotalFrames
                } else {
                    live.totalFramesAnalyzed
                }
                ProcessingPreviewScrubber(
                    label = if (isProcessing) {
                        copy.loadingLabel(analysisLabel)
                    } else if (isGeneratingAiSummary) {
                        "Local Gemma is preparing the coach summary"
                    } else {
                        copy.analysisComplete
                    },
                    totalFrames = timelineTotal,
                    processedFrames = live.totalFramesAnalyzed,
                    scannedFrame = analysisCurrentFrame,
                    currentFrame = live.currentFrameIndex,
                    timestampMs = live.currentFrameTimestampMs,
                    isPlaying = isPlaying,
                    isProcessing = isProcessing,
                    videoAudioEnabled = videoAudioEnabled,
                    videoAudioVolume = videoAudioVolume,
                    reviewCues = live.reviewCues,
                    onToggleVideoAudio = onToggleVideoAudio,
                    onVideoAudioVolumeChange = onVideoAudioVolumeChange,
                    onPrev = onPrevFrame,
                    onNext = onNextFrame,
                    onSeekFrame = onSeekFrame,
                    onJumpToLatest = onJumpToLatestFrame,
                    onTogglePlay = onTogglePause,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (live.sessionStatus.ready) {
                StatusHero(
                    live = live,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SessionStatusPendingCard(
                    isProcessing = isProcessing,
                    isGeneratingAiSummary = isGeneratingAiSummary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (live.totalFramesAnalyzed > 0) {
                ReviewAdjustmentCard(
                    text = currentReviewCue?.suggestion ?: reviewAdviceText,
                    status = reviewAdviceStatus,
                    cue = currentReviewCue,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Scrollable detail section (~55%) ────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            ReviewInfoBar(
                live = live,
                boundary = coachBoundary,
                isProcessing = isProcessing,
                isPlaying = isPlaying,
                analysisCurrentFrame = analysisCurrentFrame,
                analysisTotalFrames = analysisTotalFrames,
                expanded = detailsExpanded,
                onToggleExpanded = { detailsExpanded = !detailsExpanded },
                onReanalyzeSelectedSubject = onReanalyzeSelectedSubject,
            )

            AnimatedVisibility(
                visible = detailsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricsGrid(
                        metrics = live.templateMetrics,
                        qualityFlags = live.qualityFlags,
                    )
                    ModelReadinessCard(snapshot = modelReadiness)
                    MotionZipStatusCard(state = live.motionZipStatus)
                    CoachPanel(
                        message = effectiveCoachMessage,
                        priority = live.coachPriority,
                        source = if (live.coachMessage.isNotEmpty()) coachSourceBadge.label else "",
                    )
                    EvidencePanel(
                        card = live.evidenceCard,
                        qualityFlags = live.qualityFlags,
                        boundary = coachBoundary,
                    )
                }
            }

            Spacer(Modifier.height(72.dp)) // breathing room for bottom bar
        }

        // ── Minimal bottom bar ──────────────────────────────────────
        MinimalBottomBar(
            summaryLabel = if (isProcessing || isGeneratingAiSummary || live.analysisStage == "Full analysis running") {
                copy.partialSummary
            } else {
                copy.viewSummary
            },
            summaryBusy = isGeneratingAiSummary,
            summaryReady = summaryReady,
            onOpenSettings = onOpenSettings,
            onResetCamera = onResetCamera,
            onPickVideo = onPickVideo,
            onViewSummary = onViewSummary,
        )
    }
}

// ── CompactTopBar ───────────────────────────────────────────────────

@Composable
private fun SessionStatusPendingCard(
    isProcessing: Boolean,
    isGeneratingAiSummary: Boolean,
    modifier: Modifier = Modifier,
) {
    val title = when {
        isProcessing -> "Analyzing movement"
        isGeneratingAiSummary -> "Preparing session summary"
        else -> "Session status pending"
    }
    val detail = when {
        isProcessing -> "Status will appear after full analysis completes."
        isGeneratingAiSummary -> "Movement status will appear as soon as video analysis completes."
        else -> "Pick a video or run full analysis to update this card."
    }
    Surface(
        modifier = modifier,
        color = Background,
        shape = RoundedCornerShape(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SlowMotionVideo,
                contentDescription = title,
                tint = Blue,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp,
                )
                Text(
                    text = detail,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberStableReviewAdjustmentText(
    rawAdvice: ReviewAdjustmentAdvice,
    frameTimestampMs: Long,
    frameIndex: Int,
    isPlaying: Boolean,
): String {
    var displayedAdvice by remember { mutableStateOf(rawAdvice) }
    var lastUpdateFrameIndex by remember { mutableStateOf(frameIndex) }
    var lastUpdateVideoMs by remember { mutableStateOf(frameTimestampMs) }
    var lastUpdateWallMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(rawAdvice.eventKey, frameTimestampMs, frameIndex, isPlaying) {
        if (rawAdvice.eventKey == displayedAdvice.eventKey) return@LaunchedEffect

        val nowMs = SystemClock.elapsedRealtime()
        val wallSinceUpdateMs = nowMs - lastUpdateWallMs
        val videoMovedBackward = frameTimestampMs < lastUpdateVideoMs || frameIndex < lastUpdateFrameIndex
        val videoSinceUpdateMs = if (videoMovedBackward) {
            Long.MAX_VALUE
        } else {
            frameTimestampMs - lastUpdateVideoMs
        }
        val minHoldMs = if (displayedAdvice.immediate) {
            REVIEW_ADVICE_CRITICAL_HOLD_MS
        } else {
            REVIEW_ADVICE_MIN_DISPLAY_MS
        }

        val shouldUpdate = when {
            rawAdvice.immediate -> true
            displayedAdvice.immediate && wallSinceUpdateMs < minHoldMs -> false
            !isPlaying -> {
                delay(REVIEW_ADVICE_SCRUB_IDLE_MS)
                true
            }
            wallSinceUpdateMs < minHoldMs -> false
            videoSinceUpdateMs >= REVIEW_ADVICE_PLAYING_COOLDOWN_MS -> true
            else -> false
        }

        if (shouldUpdate) {
            displayedAdvice = rawAdvice
            lastUpdateFrameIndex = frameIndex
            lastUpdateVideoMs = frameTimestampMs
            lastUpdateWallMs = SystemClock.elapsedRealtime()
        }
    }

    return displayedAdvice.text
}

@Composable
private fun rememberStableReviewInfo(
    raw: ReviewInfoUi,
    frameTimestampMs: Long,
    frameIndex: Int,
    isPlaying: Boolean,
): ReviewInfoUi {
    var displayed by remember { mutableStateOf(raw) }
    var lastUpdateFrameIndex by remember { mutableStateOf(frameIndex) }
    var lastUpdateVideoMs by remember { mutableStateOf(frameTimestampMs) }
    var lastUpdateWallMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(raw, frameTimestampMs, frameIndex, isPlaying) {
        if (raw == displayed) return@LaunchedEffect

        val nowMs = SystemClock.elapsedRealtime()
        val wallSinceUpdateMs = nowMs - lastUpdateWallMs
        val videoMovedBackward = frameTimestampMs < lastUpdateVideoMs || frameIndex < lastUpdateFrameIndex
        val videoSinceUpdateMs = if (videoMovedBackward) {
            Long.MAX_VALUE
        } else {
            frameTimestampMs - lastUpdateVideoMs
        }
        val shouldUpdate = when {
            raw.frameLabel == "Playback" && displayed.frameLabel != "Playback" -> true
            !isPlaying -> {
                delay(REVIEW_INFO_SCRUB_IDLE_MS)
                true
            }
            wallSinceUpdateMs >= REVIEW_INFO_PLAYING_COOLDOWN_MS &&
                videoSinceUpdateMs >= REVIEW_INFO_PLAYING_COOLDOWN_MS -> true
            else -> false
        }
        if (shouldUpdate) {
            displayed = raw
            lastUpdateFrameIndex = frameIndex
            lastUpdateVideoMs = frameTimestampMs
            lastUpdateWallMs = SystemClock.elapsedRealtime()
        }
    }

    return displayed
}

@Composable
private fun ReviewInfoBar(
    live: LiveWorkoutState,
    boundary: CoachBoundaryState,
    isProcessing: Boolean,
    isPlaying: Boolean,
    analysisCurrentFrame: Int,
    analysisTotalFrames: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onReanalyzeSelectedSubject: () -> Unit,
) {
    val copy = LocalAppStrings.current
    val reviewFrame = if (live.totalFramesAnalyzed > 0) {
        "${live.currentFrameIndex + 1}/${live.totalFramesAnalyzed}"
    } else if (analysisTotalFrames > 0) {
        "${(analysisCurrentFrame + 1).coerceIn(1, analysisTotalFrames)}/$analysisTotalFrames"
    } else {
        "--"
    }
    val rawPrimary = reviewPrimaryText(live, boundary, copy)
    val rawSecondary = reviewSecondaryText(live, boundary, isProcessing, copy)
    val info = rememberStableReviewInfo(
        raw = ReviewInfoUi(
            frameLabel = if (isPlaying) "Playback" else "Frame $reviewFrame",
            primary = rawPrimary,
            secondary = rawSecondary,
        ),
        frameTimestampMs = live.currentFrameTimestampMs,
        frameIndex = live.currentFrameIndex,
        isPlaying = isPlaying,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .animateContentSize(animationSpec = tween(220)),
        color = SurfaceColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = Color(0xFF1E2530),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = info.frameLabel,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (expanded) copy.less else copy.more,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) copy.collapse else copy.expand,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = info.primary,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = info.secondary,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (live.reviewTargetChangedAfterAnalysis && live.targetReanalysisAvailable) {
                Button(
                    onClick = onReanalyzeSelectedSubject,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Reanalyze this person",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewAdjustmentCard(
    text: String,
    status: ReviewAdjustmentStatusUi,
    cue: ReviewCue? = null,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank() && cue == null) return
    val animatedDots = rememberAnimatedDots(status.animated)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220)),
        color = Color(0xFF172033),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(status.color),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = cue?.title ?: "Coach adjustment",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        color = status.color.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = status.label + animatedDots,
                            color = status.color,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    text = cue?.suggestion ?: text,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = cue?.let { "${formatCueTime(it.timestampMs)} | ${it.evidenceRef}" } ?: status.detail,
                    color = TextSecondary.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatCueTime(timestampMs: Long): String {
    val totalSec = timestampMs.coerceAtLeast(0L) / 1000L
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

@Composable
private fun rememberAnimatedDots(active: Boolean): String {
    var dotCount by remember { mutableStateOf(0) }
    LaunchedEffect(active) {
        while (active) {
            dotCount = (dotCount + 1) % 4
            delay(420L)
        }
        dotCount = 0
    }
    return if (active) ".".repeat(dotCount) else ""
}

private fun reviewPrimaryText(
    live: LiveWorkoutState,
    boundary: CoachBoundaryState,
    copy: com.gemmafit.ui.localization.AppStrings,
): String {
    val warning = live.activeWarnings.firstOrNull()
    return when {
        live.targetReanalysisActive -> {
            "Reanalyzing selected subject"
        }
        live.reviewTargetChangedAfterAnalysis -> {
            "Review target changed"
        }
        boundary.kind in setOf(CoachBoundaryKind.MONITOR_ONLY, CoachBoundaryKind.REFUSED) -> {
            boundary.title
        }
        !live.reviewFrameStatus.poseAvailable &&
            live.reviewFrameStatus.noPoseReason == "no_person_detected" -> {
            "No pose detected in this frame"
        }
        live.subjectLockStatus == SubjectLockStatus.NEEDS_SELECTION -> {
            if ("MULTI_PERSON" in live.subjectTrustFlags || live.poseCandidates.size > 1) {
                copy.tapYourself
            } else {
                copy.subjectAutoSelecting
            }
        }
        live.subjectLockStatus == SubjectLockStatus.LOCKED -> {
            if ("subject_hold" in live.subjectTrustFlags) copy.subjectHold else copy.subjectLocked
        }
        live.subjectLockStatus == SubjectLockStatus.AUTO_LOCKED -> copy.autoSelectedSubject
        live.subjectLockStatus == SubjectLockStatus.SUBJECT_LOST -> copy.subjectLost
        warning != null -> warning.message
        live.evidenceCard.verdict != "OK" -> TrustUiMapper.whyNotJudgedSummary(live.evidenceCard, live.qualityFlags)
        else -> "Reviewing analyzed frame"
    }
}

private fun reviewSecondaryText(
    live: LiveWorkoutState,
    boundary: CoachBoundaryState,
    isProcessing: Boolean,
    copy: com.gemmafit.ui.localization.AppStrings,
): String {
    val whyNotJudged = TrustUiMapper.whyNotJudgedSummary(live.evidenceCard, live.qualityFlags)
    val timestamp = if (live.currentFrameTimestampMs > 0L) {
        "%.1fs".format(live.currentFrameTimestampMs / 1000.0)
    } else {
        ""
    }
    val stage = when {
        isProcessing && live.analysisStage.isNotBlank() -> live.analysisStage
        live.movementPhase.isNotBlank() && live.movementPhase != "unknown" -> "${copy.phase}: ${live.movementPhase}"
        else -> ""
    }
    val targetNote = if (live.reviewTargetChangedAfterAnalysis) {
        "Analysis summary not updated for selected subject"
    } else if (live.targetReanalysisActive) {
        "Rebuilding evidence from frame 0"
    } else {
        ""
    }
    val compactBoundary = boundary.summary.takeIf {
        boundary.kind in setOf(CoachBoundaryKind.MONITOR_ONLY, CoachBoundaryKind.REFUSED)
    } ?: whyNotJudged
    return listOf(timestamp, targetNote, stage, compactBoundary)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" | ")
        .ifBlank { copy.feedbackBoundary }
}

private fun reviewAdjustmentAdvice(
    live: LiveWorkoutState,
    boundary: CoachBoundaryState,
): ReviewAdjustmentAdvice {
    val statuses = live.qualityFlags.map { it.status.uppercase() }.toSet()
    val reasonText = listOf(
        live.reviewFrameStatus.noPoseReason,
        live.evidenceCard.reason,
        live.evidenceCard.trustFlags.joinToString(" "),
        live.qualityFlags.joinToString(" ") { "${it.id} ${it.reason} ${it.evidence}" },
    ).joinToString(" ").lowercase()
    val exercise = live.detectedExercise.takeUnless { it == "unknown" }.orEmpty()
    val phase = live.movementPhase.takeUnless { it == "unknown" }.orEmpty()
    val topWarning = live.activeWarnings.firstOrNull()
    val warningClass = topWarning?.let { warningEventClass(it) } ?: "none"
    val limitedClass = limitedEventClass(statuses, reasonText, boundary)
    val eventKey = listOf(
        exercise.ifBlank { "unknown" },
        phase.ifBlank { "unknown" },
        warningClass,
        limitedClass,
    ).joinToString("|")
    val immediate = statuses.any { it == "CRITICAL" } ||
        topWarning?.severity?.lowercase() in setOf("critical", "high")
    val frameCue = topWarning?.let { warningAdjustmentText(it) }
        ?: movementAdjustmentText(exercise, phase)
    val isLimitedCue = boundary.kind in setOf(CoachBoundaryKind.MONITOR_ONLY, CoachBoundaryKind.REFUSED) ||
        statuses.any { it in setOf("VIEW_LIMITED", "LOW_CONFIDENCE", "NOT_APPLICABLE") }

    if (isLimitedCue) {
        val limitedSuffix = when {
            statuses.contains("LOW_CONFIDENCE") || reasonText.contains("low_confidence") -> {
                "Tracking low; repeat slower if needed."
            }
            statuses.contains("VIEW_LIMITED") ||
                reasonText.contains("visibility") ||
                reasonText.contains("view") ||
                reasonText.contains("crop") -> {
                "Keep full body and support in frame."
            }
            statuses.contains("NOT_APPLICABLE") || reasonText.contains("not applicable") -> {
                "Monitor-only; compare with a full rep."
            }
            else -> "Observation cue, not a hard judgment."
        }
        return ReviewAdjustmentAdvice(
            text = "${frameCue.trimEnd('.')} - $limitedSuffix",
            eventKey = eventKey,
            immediate = immediate,
            warningClass = warningClass,
            limitedClass = limitedClass,
        )
    }

    return ReviewAdjustmentAdvice(
        text = frameCue,
        eventKey = eventKey,
        immediate = immediate,
        warningClass = warningClass,
        limitedClass = limitedClass,
    )
}

private fun reviewAdjustmentStatusUi(
    advice: ReviewAdjustmentAdvice,
    boundary: CoachBoundaryState,
    isPlaying: Boolean,
): ReviewAdjustmentStatusUi {
    val limited = advice.limitedClass != "ok" ||
        boundary.kind in setOf(CoachBoundaryKind.MONITOR_ONLY, CoachBoundaryKind.REFUSED)
    return when {
        advice.immediate -> ReviewAdjustmentStatusUi(
            label = if (isPlaying) "Safety cue" else "Review cue",
            detail = "Red marker means a high-severity joint cue is active.",
            color = Red,
            animated = isPlaying,
        )
        limited -> ReviewAdjustmentStatusUi(
            label = if (isPlaying) "Watch cue" else "Evidence limited",
            detail = "Yellow marker means watch/limited evidence, not a hard judgment.",
            color = Orange,
            animated = isPlaying,
        )
        advice.warningClass != "none" -> ReviewAdjustmentStatusUi(
            label = if (isPlaying) "Watch cue" else "Frame cue",
            detail = "Yellow marker shows where the current cue applies.",
            color = Orange,
            animated = isPlaying,
        )
        else -> ReviewAdjustmentStatusUi(
            label = if (isPlaying) "Stable" else "Ready",
            detail = "Evidence is stable, so the cue is not rewritten every frame.",
            color = Green,
            animated = isPlaying,
        )
    }
}

private fun nearestReviewCue(
    cues: List<ReviewCue>,
    frameIndex: Int,
): ReviewCue? {
    return cues
        .asSequence()
        .map { cue -> cue to kotlin.math.abs(cue.frameIndex - frameIndex) }
        .filter { (_, distance) -> distance <= 2 }
        .minWithOrNull(
            compareBy<Pair<ReviewCue, Int>> { it.second }
                .thenByDescending { reviewCueSeverityWeight(it.first.severity) },
        )
        ?.first
}

private fun reviewCueSeverityWeight(severity: String): Int {
    return when (severity.lowercase()) {
        "critical" -> 3
        "warning" -> 2
        else -> 1
    }
}

private fun movementAdjustmentText(
    exercise: String,
    phase: String,
): String {
    return when {
        exercise == "lunge" -> when (phase) {
            "descent" -> "Lunge descent: knee over toes, lower slowly."
            "ascent" -> "Lunge ascent: press through front foot; chair steady."
            "bottom" -> "Lunge bottom: pause with knee stacked over foot."
            "top" -> "Lunge top: stand tall and reset posture."
            else -> "Lunge: keep front knee aligned and move slowly."
        }
        exercise == "squat" -> when (phase) {
            "descent" -> "Squat descent: torso steady, lower under control."
            "ascent" -> "Squat ascent: press evenly; avoid rushing upward."
            "bottom" -> "Squat bottom: pause, knees tracking over toes."
            "top" -> "Squat top: stand tall and reset posture."
            else -> "Squat: keep torso steady and move under control."
        }
        phase.isNotBlank() -> {
            "${phase.replace('_', ' ').replaceFirstChar { it.uppercase() }} cue: repeat one slow controlled rep."
        }
        else -> "Use this frame as a reference; repeat one slow rep."
    }
}

private fun warningAdjustmentText(warning: com.gemmafit.video.SafetyWarning): String {
    val fn = warning.functionName.lowercase()
    val joint = warning.joint.lowercase()
    return when {
        "knee" in fn || "knee" in joint -> {
            "Knee cue: track over middle toes; slow descent."
        }
        "spinal" in fn || "neck" in fn || "spine" in joint || "neck" in joint -> {
            "Posture cue: torso tall, neck neutral."
        }
        "asymmetry" in fn -> {
            "Balance cue: move slower; keep both sides level."
        }
        "com" in fn || "center" in warning.message.lowercase() -> {
            "Stability cue: keep weight centered over support."
        }
        "rapid" in fn -> {
            "Tempo cue: reduce speed and pause briefly."
        }
        "range" in fn || "rom" in fn -> {
            "Range cue: use a controlled, comfortable range."
        }
        else -> warning.message.ifBlank {
            "Reset posture, slow the next rep."
        }
    }
}

private fun warningEventClass(warning: com.gemmafit.video.SafetyWarning): String {
    val fn = warning.functionName.lowercase()
    val joint = warning.joint.lowercase()
    return when {
        "knee" in fn || "knee" in joint -> "knee"
        "spinal" in fn || "neck" in fn || "spine" in joint || "neck" in joint -> "posture"
        "asymmetry" in fn -> "asymmetry"
        "com" in fn || "center" in warning.message.lowercase() -> "center_of_mass"
        "rapid" in fn -> "rapid"
        "range" in fn || "rom" in fn -> "range"
        else -> warning.functionName.ifBlank { warning.severity }.ifBlank { "warning" }
    }
}

private fun limitedEventClass(
    statuses: Set<String>,
    reasonText: String,
    boundary: CoachBoundaryState,
): String {
    return when {
        statuses.contains("LOW_CONFIDENCE") || reasonText.contains("low_confidence") -> "low_confidence"
        statuses.contains("VIEW_LIMITED") ||
            reasonText.contains("visibility") ||
            reasonText.contains("view") ||
            reasonText.contains("crop") -> "view_limited"
        statuses.contains("NOT_APPLICABLE") || reasonText.contains("not applicable") -> "not_applicable"
        boundary.kind == CoachBoundaryKind.REFUSED -> "refused"
        boundary.kind == CoachBoundaryKind.MONITOR_ONLY -> "monitor_only"
        else -> "ok"
    }
}

@Composable
private fun CompactTopBar(
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    showTrajectory: Boolean,
    onToggleTrajectory: () -> Unit,
    onResetCamera: () -> Unit,
) {
    val copy = LocalAppStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(SurfaceColor.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onResetCamera,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = copy.backToCamera,
                tint = TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(4.dp))

        // Live indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Green),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = copy.videoAnalysis,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.weight(1f))

        IconButton(
            onClick = onPickVideo,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = copy.pickVideo,
                tint = Green,
                modifier = Modifier.size(22.dp),
            )
        }

        IconButton(
            onClick = onToggleTrajectory,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.SlowMotionVideo,
                contentDescription = if (showTrajectory) copy.hideTrajectory else copy.showTrajectory,
                tint = if (showTrajectory) Green else TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = copy.settings,
                tint = TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ── MinimalBottomBar ────────────────────────────────────────────────

@Composable
private fun MinimalBottomBar(
    summaryLabel: String,
    summaryBusy: Boolean,
    summaryReady: Boolean,
    onOpenSettings: () -> Unit,
    onResetCamera: () -> Unit,
    onPickVideo: () -> Unit,
    onViewSummary: () -> Unit,
) {
    val copy = LocalAppStrings.current
    val actionHeight = 60.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor.copy(alpha = 0.85f))
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(0.7f)
                .height(actionHeight),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = copy.settings,
                tint = TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        Button(
            onClick = onResetCamera,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1.15f)
                .height(actionHeight),
        ) {
            Text(
                copy.liveCamera,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }

        Button(
            onClick = onPickVideo,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1.15f)
                .height(actionHeight),
        ) {
            Text(
                copy.pickVideo,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }

        Box(
            modifier = Modifier
                .weight(2f)
                .height(actionHeight),
        ) {
            Button(
                onClick = onViewSummary,
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (summaryBusy) {
                    BusyDot(color = TextPrimary)
                } else {
                    Icon(Icons.Filled.BarChart, null, tint = TextPrimary)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    summaryLabel,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            if (summaryReady) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(Red),
                )
            }
        }
    }
}

@Composable
private fun AiSummaryProgressBanner(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF172033),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BusyDot(color = Blue)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Local Gemma is generating the session summary",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "You can keep reviewing frames while it finishes.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BusyDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun AnalysisProgressBanner(
    label: String,
    progress: Float,
    frameText: String,
) {
    val copy = LocalAppStrings.current
    val safeProgress = progress.coerceIn(0f, 0.99f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${(safeProgress * 100).toInt()}%",
                color = Green,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safeProgress)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Blue),
            )
        }
        Text(
            text = frameText.ifBlank { copy.preparingFrames },
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

private fun analysisFrameText(
    frameLabel: String,
    currentFrame: Int,
    totalFrames: Int,
    includeTotal: Boolean,
): String {
    val displayFrame = if (includeTotal && totalFrames > 0) {
        (currentFrame + 1).coerceIn(1, totalFrames)
    } else {
        currentFrame.coerceAtLeast(0)
    }
    return if (includeTotal && totalFrames > 0) {
        "${frameLabel.replaceFirstChar { it.uppercase() }} $displayFrame / $totalFrames"
    } else if (displayFrame > 0) {
        "${frameLabel.replaceFirstChar { it.uppercase() }} $displayFrame"
    } else {
        ""
    }
}
