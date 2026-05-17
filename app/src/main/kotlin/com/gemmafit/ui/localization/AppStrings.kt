package com.gemmafit.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.gemmafit.settings.AppLanguage
import java.util.Locale

data class AppStrings(
    val settings: String,
    val back: String,
    val share: String,
    val continueAction: String,
    val skip: String,
    val startWorkout: String,
    val grantCameraAccess: String,
    val cameraAccessGranted: String,
    val onboardingPages: List<OnboardingCopy>,
    val liveCamera: String,
    val pickVideo: String,
    val hideTrajectory: String,
    val showTrajectory: String,
    val subjectAutoSelecting: String,
    val tapYourself: String,
    val subjectHold: String,
    val subjectLocked: String,
    val autoSelectedSubject: String,
    val subjectLost: String,
    val singleSubject: String,
    val reps: String,
    val form: String,
    val phase: String,
    val summary: String,
    val videoAnalysis: String,
    val uploadVideoTitle: String,
    val uploadVideoDescription: String,
    val chooseVideo: String,
    val loadingVideo: String,
    val loadingPoseModel: String,
    val preparingPreview: String,
    val previewAnalysis: String,
    val fullAnalysis: String,
    val analysisComplete: String,
    val analyzingVideo: String,
    val analysisFailed: String,
    val backToCamera: String,
    val retry: String,
    val fps: String,
    val poseHit: String,
    val analyzingMovement: String,
    val preparingFrames: String,
    val partialSummary: String,
    val viewSummary: String,
    val play: String,
    val pause: String,
    val liveMetrics: String,
    val less: String,
    val more: String,
    val edit: String,
    val selectMetrics: String,
    val evidence: String,
    val collapse: String,
    val expand: String,
    val trustFlags: String,
    val cannotJudgeFromView: String,
    val notApplicableReason: String,
    val viewLimitedReason: String,
    val lowConfidenceReason: String,
    val skipped: String,
    val feedbackBoundary: String,
    val coachTitle: String,
    val workoutSummary: String,
    val totalReps: String,
    val formScore: String,
    val duration: String,
    val clean: String,
    val frames: String,
    val formScoreTrend: String,
    val safetyEvents: String,
    val movement: String,
    val muscleFocus: String,
    val muscleFocusBoundary: String,
    val noSafetyEvents: String,
    val critical: String,
    val warning: String,
    val coachTips: String,
    val newSession: String,
    val allHistory: String,
    val localAiCoachSummary: String,
    val whatISaw: String,
    val whyItMatters: String,
    val notJudged: String,
    val nextFocus: String,
    val evidenceRefs: String,
) {
    fun loadingLabel(subPhase: String): String = when (subPhase) {
        "video_loading" -> loadingVideo
        "loading_model" -> loadingPoseModel
        "preview_loading" -> preparingPreview
        "preview_analysis" -> previewAnalysis
        "full_analysis" -> fullAnalysis
        "complete" -> analysisComplete
        else -> subPhase.replace("_", " ").ifBlank { analyzingVideo }
    }

    fun statusTitle(status: String): String = when (status) {
        "CRITICAL" -> if (this === zhTw) "立即修正" else "CORRECT NOW"
        "WARNING" -> if (this === zhTw) "警告" else "WARNING"
        "MONITOR" -> if (this === zhTw) "注意" else "WATCH"
        "VIEW_LIMITED" -> if (this === zhTw) "視角受限" else "VIEW LIMITED"
        "LOW_CONFIDENCE" -> if (this === zhTw) "信心不足" else "LOW CONFIDENCE"
        "NOT_APPLICABLE" -> if (this === zhTw) "不適用" else "NOT APPLICABLE"
        "OK" -> if (this === zhTw) "穩定" else "CLEAN"
        else -> status
    }

    fun exerciseLabel(exercise: String): String = when (exercise) {
        "squat" -> if (this === zhTw) "蹲起" else "Squat"
        "push_up" -> if (this === zhTw) "伏地挺身" else "Push-up"
        "lunge" -> if (this === zhTw) "弓箭步" else "Lunge"
        "deadlift" -> if (this === zhTw) "髖鉸鏈" else "Deadlift"
        "unknown" -> if (this === zhTw) "偵測中" else "Detecting"
        else -> exercise.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    fun displayMetricKey(key: String): String {
        val english = key
            .replace("_angle_deg", "")
            .replace("_angle", "")
            .replace("_deg", "")
            .replace("_pct", "")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
        if (this !== zhTw) return english
        return when (english.lowercase()) {
            "left knee", "knee" -> "膝角度"
            "right knee" -> "右膝"
            "left hip", "hip" -> "髖角度"
            "right hip" -> "右髖"
            "back", "trunk" -> "軀幹"
            "symmetry" -> "對稱"
            "com offset" -> "重心偏移"
            "tempo" -> "節奏"
            "elbow", "left elbow" -> "肘角度"
            "shoulder", "left shoulder" -> "肩角度"
            "ankle", "left ankle" -> "踝角度"
            else -> english
        }
    }

    fun unsupportedJudgmentLabel(judgment: String): String = when (judgment) {
        "joint_force" -> if (this === zhTw) "關節受力" else "Joint force"
        "clinical_injury_risk" -> if (this === zhTw) "臨床受傷風險" else "Injury risk"
        "medical_diagnosis" -> if (this === zhTw) "醫療診斷" else "Medical diagnosis"
        "muscle_activation_percentage" -> if (this === zhTw) "肌肉活化百分比" else "Muscle activation %"
        else -> judgment.replace("_", " ").replaceFirstChar(Char::titlecase)
    }

    fun unsupportedReason(judgment: String): String = when (judgment) {
        "joint_force" -> if (this === zhTw) "這是姿態估計，不是受力測量" else "Pose-based, not measured force"
        "clinical_injury_risk" -> if (this === zhTw) "不做臨床風險判定" else "Out of scope - not a clinical tool"
        "medical_diagnosis" -> if (this === zhTw) "只做動作品質回饋，不做診斷" else "Movement quality only, not diagnosis"
        "muscle_activation_percentage" -> if (this === zhTw) "姿態推估，不是 EMG" else "Pose-based estimate, not EMG"
        else -> if (this === zhTw) "超出單鏡頭姿態可判斷範圍" else "Out of scope for single-camera pose"
    }

    companion object {
        val en = AppStrings(
            settings = "Settings",
            back = "Back",
            share = "Share",
            continueAction = "Continue",
            skip = "Skip",
            startWorkout = "Start Workout",
            grantCameraAccess = "Grant Camera Access",
            cameraAccessGranted = "Camera access granted",
            onboardingPages = listOf(
                OnboardingCopy("Your Pocket Trainer", "Real-time form coaching\nwithout leaving your phone", "AI-powered form coaching that works offline, on your phone."),
                OnboardingCopy("How It Works", "Your phone sees, AI coaches", "Camera tracks your movement. AI analyzes joint angles and gives instant voice feedback when form needs correction."),
                OnboardingCopy("100% Private", "Everything stays on your phone", "No internet needed. No account required. No video is uploaded for coaching."),
                OnboardingCopy("Almost Ready", "We need camera access", "Camera permission is required to analyze posture in real time."),
            ),
            liveCamera = "Live Camera",
            pickVideo = "Pick video",
            hideTrajectory = "Hide trajectory",
            showTrajectory = "Show trajectory",
            subjectAutoSelecting = "Auto-selecting subject...",
            tapYourself = "Tap yourself to start",
            subjectHold = "Subject hold",
            subjectLocked = "Subject locked",
            autoSelectedSubject = "Auto-selected subject - tap to change",
            subjectLost = "Subject lost",
            singleSubject = "Single subject",
            reps = "REPS",
            form = "FORM",
            phase = "PHASE",
            summary = "Summary",
            videoAnalysis = "Video Analysis",
            uploadVideoTitle = "Upload a video to analyze",
            uploadVideoDescription = "Select a workout video and get real-time\nform analysis and coaching feedback.",
            chooseVideo = "Choose Video",
            loadingVideo = "Loading video",
            loadingPoseModel = "Loading pose model",
            preparingPreview = "Preparing preview",
            previewAnalysis = "Preview analysis",
            fullAnalysis = "Full analysis",
            analysisComplete = "Analysis complete",
            analyzingVideo = "Analyzing video",
            analysisFailed = "Analysis Failed",
            backToCamera = "Back to Camera",
            retry = "Retry",
            fps = "FPS",
            poseHit = "Pose Hit",
            analyzingMovement = "Analyzing Movement",
            preparingFrames = "Preparing frames...",
            partialSummary = "Partial Summary",
            viewSummary = "View Summary",
            play = "Play",
            pause = "Pause",
            liveMetrics = "Live Metrics",
            less = "Less",
            more = "More",
            edit = "Edit",
            selectMetrics = "Select metrics to display",
            evidence = "Evidence",
            collapse = "Collapse",
            expand = "Expand",
            trustFlags = "Trust flags",
            cannotJudgeFromView = "Cannot judge from this view",
            notApplicableReason = "Not applicable to this exercise / view",
            viewLimitedReason = "Camera angle limits this judgment",
            lowConfidenceReason = "Pose tracking unstable",
            skipped = "Skipped",
            feedbackBoundary = "Pose-based feedback - not medical diagnosis",
            coachTitle = "GemmaFit Coach",
            workoutSummary = "Workout Summary",
            totalReps = "Total reps",
            formScore = "Form score",
            duration = "Duration",
            clean = "clean",
            frames = "frames",
            formScoreTrend = "Form Score Trend",
            safetyEvents = "Safety Events",
            movement = "Movement",
            muscleFocus = "Muscle Focus",
            muscleFocusBoundary = "Pose-estimated muscle focus, not direct muscle activation.",
            noSafetyEvents = "No safety events - great form!",
            critical = "Critical",
            warning = "Warning",
            coachTips = "Coach Tips",
            newSession = "New Session",
            allHistory = "All History",
            localAiCoachSummary = "Local AI Coach Summary",
            whatISaw = "What I saw",
            whyItMatters = "Why it matters",
            notJudged = "What I did not judge",
            nextFocus = "Next focus",
            evidenceRefs = "Evidence refs",
        )

        val zhTw = en.copy(
            settings = "設定",
            back = "返回",
            share = "分享",
            continueAction = "繼續",
            skip = "略過",
            startWorkout = "開始訓練",
            grantCameraAccess = "允許相機權限",
            cameraAccessGranted = "相機權限已開啟",
            onboardingPages = listOf(
                OnboardingCopy("口袋健身教練", "即時姿勢指導\n不用離開手機", "離線 AI 姿勢教練，直接在手機上運作。"),
                OnboardingCopy("運作方式", "手機看動作，AI 給提示", "相機追蹤你的動作，AI 分析關節角度，姿勢需要修正時即時語音提醒。"),
                OnboardingCopy("100% 私密", "所有資料留在手機", "不需要網路、不需要帳號，訓練影片不會上傳。"),
                OnboardingCopy("快準備好了", "需要相機權限", "需要相機權限才能即時分析姿勢。"),
            ),
            liveCamera = "即時相機",
            pickVideo = "選擇影片",
            hideTrajectory = "隱藏軌跡",
            showTrajectory = "顯示軌跡",
            subjectAutoSelecting = "正在自動選取主體...",
            tapYourself = "點選自己開始",
            subjectHold = "暫時保留主體",
            subjectLocked = "主體已鎖定",
            autoSelectedSubject = "已自動選取主體，可點選切換",
            subjectLost = "主體遺失",
            singleSubject = "單一主體",
            reps = "次數",
            form = "姿勢",
            phase = "階段",
            summary = "總結",
            videoAnalysis = "影片分析",
            uploadVideoTitle = "上傳影片進行分析",
            uploadVideoDescription = "選擇訓練影片，取得即時姿勢分析與教練回饋。",
            chooseVideo = "選擇影片",
            loadingVideo = "正在載入影片",
            loadingPoseModel = "正在載入姿態模型",
            preparingPreview = "正在準備預覽",
            previewAnalysis = "預覽分析",
            fullAnalysis = "完整分析",
            analysisComplete = "分析完成",
            analyzingVideo = "正在分析影片",
            analysisFailed = "分析失敗",
            backToCamera = "回到相機",
            retry = "重試",
            fps = "FPS",
            poseHit = "偵測率",
            analyzingMovement = "正在分析動作",
            preparingFrames = "正在準備影格...",
            partialSummary = "暫時總結",
            viewSummary = "查看總結",
            play = "播放",
            pause = "暫停",
            liveMetrics = "即時指標",
            less = "收合",
            more = "更多",
            edit = "編輯",
            selectMetrics = "選擇要顯示的指標",
            evidence = "證據",
            collapse = "收合",
            expand = "展開",
            trustFlags = "信任旗標",
            cannotJudgeFromView = "此視角無法判斷",
            notApplicableReason = "不適用於此動作或視角",
            viewLimitedReason = "相機角度限制此判斷",
            lowConfidenceReason = "姿態追蹤不穩定",
            skipped = "已略過",
            feedbackBoundary = "姿態回饋，不是醫療診斷",
            coachTitle = "GemmaFit 教練",
            workoutSummary = "訓練總結",
            totalReps = "總次數",
            formScore = "姿勢分數",
            duration = "時間",
            clean = "穩定",
            frames = "影格",
            formScoreTrend = "姿勢分數趨勢",
            safetyEvents = "安全事件",
            movement = "動作",
            muscleFocus = "肌群推估",
            muscleFocusBoundary = "基於姿態的肌群推估，不是直接肌肉活化測量。",
            noSafetyEvents = "沒有安全事件，姿勢表現穩定！",
            critical = "嚴重",
            warning = "警告",
            coachTips = "教練提示",
            newSession = "新訓練",
            allHistory = "歷史紀錄",
            localAiCoachSummary = "本機 AI 教練總結",
            whatISaw = "我看到的重點",
            whyItMatters = "為什麼重要",
            notJudged = "未判斷項目",
            nextFocus = "下一步重點",
            evidenceRefs = "證據 refs",
        )

        fun forLanguage(language: AppLanguage): AppStrings {
            return when (language) {
                AppLanguage.ENGLISH -> en
                AppLanguage.TRADITIONAL_CHINESE -> zhTw
                AppLanguage.SYSTEM -> if (Locale.getDefault().language.startsWith("zh")) zhTw else en
            }
        }
    }
}

data class OnboardingCopy(
    val title: String,
    val subtitle: String,
    val description: String,
)

val LocalAppStrings = staticCompositionLocalOf { AppStrings.en }

@Composable
fun ProvideAppStrings(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppStrings provides AppStrings.forLanguage(language)) {
        content()
    }
}
