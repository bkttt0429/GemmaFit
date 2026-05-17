package com.gemmafit.senior

import com.gemmafit.video.PoseLandmarkData
import kotlin.math.hypot

data class SeniorGestureResult(
    val gesture: String,
    val confidence: Double,
    val evidenceRef: String,
    val fallbackReason: String = "",
)

/**
 * Deterministic low-impact gesture detector for Senior Dual-task Mode.
 *
 * It uses only current pose landmarks and a short wrist-distance window. It is
 * intended for bounded answers, not activity recognition or medical assessment.
 */
class SeniorGestureDetector {
    fun detect(
        landmarks: List<PoseLandmarkData>,
        wristDistanceHistory: List<Float> = emptyList(),
    ): SeniorGestureResult {
        if (landmarks.size < 33) {
            return noGesture("critical_joint_not_visible")
        }
        val leftShoulder = landmarks.visible(LEFT_SHOULDER) ?: return noGesture("left_shoulder_not_visible")
        val rightShoulder = landmarks.visible(RIGHT_SHOULDER) ?: return noGesture("right_shoulder_not_visible")
        val leftWrist = landmarks.visible(LEFT_WRIST)
        val rightWrist = landmarks.visible(RIGHT_WRIST)

        if (leftWrist == null && rightWrist == null) return noGesture("wrists_not_visible")

        val leftRaised = leftWrist != null && leftWrist.y < leftShoulder.y - ARM_RAISE_MARGIN
        val rightRaised = rightWrist != null && rightWrist.y < rightShoulder.y - ARM_RAISE_MARGIN
        if (leftRaised && rightRaised) {
            return SeniorGestureResult(
                gesture = "two_hand_raise",
                confidence = averageVisibility(leftWrist, rightWrist, leftShoulder, rightShoulder),
                evidenceRef = "metric.dual_task.gesture.two_hand_raise",
            )
        }
        if (leftRaised) {
            return SeniorGestureResult(
                gesture = "left_arm_raise",
                confidence = averageVisibility(leftWrist, leftShoulder),
                evidenceRef = "metric.dual_task.gesture.left_arm_raise",
            )
        }
        if (rightRaised) {
            return SeniorGestureResult(
                gesture = "right_arm_raise",
                confidence = averageVisibility(rightWrist, rightShoulder),
                evidenceRef = "metric.dual_task.gesture.right_arm_raise",
            )
        }
        if (leftWrist != null && rightWrist != null) {
            val distance = hypot((leftWrist.x - rightWrist.x).toDouble(), (leftWrist.y - rightWrist.y).toDouble()).toFloat()
            val closeHits = (wristDistanceHistory + distance).takeLast(CLAP_WINDOW).count { it < CLAP_DISTANCE }
            if (closeHits >= CLAP_HITS_REQUIRED) {
                return SeniorGestureResult(
                    gesture = "clap_twice",
                    confidence = averageVisibility(leftWrist, rightWrist),
                    evidenceRef = "metric.dual_task.gesture.clap_twice",
                )
            }
        }
        return noGesture("no_bounded_gesture")
    }

    private fun noGesture(reason: String): SeniorGestureResult {
        return SeniorGestureResult(
            gesture = "none",
            confidence = 0.0,
            evidenceRef = "capability.dual_task.gesture.blocked",
            fallbackReason = reason,
        )
    }

    private fun List<PoseLandmarkData>.visible(index: Int): PoseLandmarkData? {
        return getOrNull(index)?.takeIf { it.visibility >= MIN_VISIBILITY }
    }

    private fun averageVisibility(vararg landmarks: PoseLandmarkData?): Double {
        val visible = landmarks.filterNotNull()
        if (visible.isEmpty()) return 0.0
        return visible.map { it.visibility.toDouble() }.average().coerceIn(0.0, 1.0)
    }

    companion object {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val MIN_VISIBILITY = 0.45f
        const val ARM_RAISE_MARGIN = 0.04f
        const val CLAP_DISTANCE = 0.08f
        const val CLAP_WINDOW = 8
        const val CLAP_HITS_REQUIRED = 2
    }
}
