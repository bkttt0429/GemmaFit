package com.gemmafit.ui.overlay

import com.gemmafit.video.SafetyWarning

data class PoseCueOverlaySelection(
    val safetyJoints: Set<Int> = emptySet(),
    val watchJoints: Set<Int> = emptySet(),
    val safetySegments: Set<Pair<Int, Int>> = emptySet(),
    val watchSegments: Set<Pair<Int, Int>> = emptySet(),
)

object PoseCueMapper {
    fun fromWarnings(warnings: List<SafetyWarning>): PoseCueOverlaySelection {
        val safetyJoints = linkedSetOf<Int>()
        val watchJoints = linkedSetOf<Int>()
        val safetySegments = linkedSetOf<Pair<Int, Int>>()
        val watchSegments = linkedSetOf<Pair<Int, Int>>()

        warnings.forEach { warning ->
            val jointIndex = jointIndexFromName(warning.joint)
            val segment = segmentFromJointName(warning.joint)
            if (warning.isHighSeverityCue()) {
                jointIndex?.let { safetyJoints += it }
                segment?.let { safetySegments += it }
            } else {
                jointIndex?.let { watchJoints += it }
                segment?.let { watchSegments += it }
            }
        }

        // Safety cues win if the same joint/segment is present in both sets.
        watchJoints.removeAll(safetyJoints)
        watchSegments.removeAll(safetySegments)

        return PoseCueOverlaySelection(
            safetyJoints = safetyJoints,
            watchJoints = watchJoints,
            safetySegments = safetySegments,
            watchSegments = watchSegments,
        )
    }

    private fun SafetyWarning.isHighSeverityCue(): Boolean {
        val normalized = severity.lowercase()
        return normalized == "critical" || normalized == "high"
    }

    private fun jointIndexFromName(joint: String): Int? {
        val normalized = joint.lowercase()
        return when {
            "left_knee" in normalized -> 25
            "right_knee" in normalized -> 26
            "knee" in normalized -> 26
            "left_hip" in normalized -> 23
            "right_hip" in normalized -> 24
            "hip" in normalized -> 24
            "left_elbow" in normalized -> 13
            "right_elbow" in normalized -> 14
            "elbow" in normalized -> 14
            "left_shoulder" in normalized -> 11
            "right_shoulder" in normalized -> 12
            "shoulder" in normalized -> 12
            "left_ankle" in normalized -> 27
            "right_ankle" in normalized -> 28
            "ankle" in normalized -> 28
            else -> null
        }
    }

    private fun segmentFromJointName(joint: String): Pair<Int, Int>? {
        val normalized = joint.lowercase()
        return when {
            "left_knee" in normalized -> 25 to 27
            "right_knee" in normalized -> 26 to 28
            "left_hip" in normalized -> 23 to 25
            "right_hip" in normalized -> 24 to 26
            "left_elbow" in normalized -> 13 to 15
            "right_elbow" in normalized -> 14 to 16
            else -> null
        }
    }
}
