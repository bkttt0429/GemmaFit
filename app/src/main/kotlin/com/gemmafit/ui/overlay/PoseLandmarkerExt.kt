package com.gemmafit.ui.overlay

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

fun PoseLandmarkerResult.toPoseOverlayState(
    violationSegments: Set<Pair<Int, Int>> = emptySet(),
    violationJoints: Set<Int> = emptySet(),
): PoseOverlayState {
    val landmarks = this.landmarks().firstOrNull() ?: return PoseOverlayState()

    val poseLandmarks = landmarks.mapIndexed { index, landmark ->
        PoseLandmark(
            x = landmark.x(),
            y = landmark.y(),
            visibility = landmark.visibility().orElse(1.0f),
        )
    }

    return PoseOverlayState(
        landmarks = poseLandmarks,
        violationSegments = violationSegments,
        violationJoints = violationJoints,
    )
}

fun List<PoseLandmark>.toFloatArray(): FloatArray {
    val floatArray = FloatArray(99)
    for (i in 0 until minOf(size, 33)) {
        floatArray[i * 3] = get(i).x
        floatArray[i * 3 + 1] = get(i).y
        floatArray[i * 3 + 2] = get(i).visibility
    }
    return floatArray
}