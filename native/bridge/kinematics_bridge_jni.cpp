#include <jni.h>
#include <string>
#include <vector>

#include "kinematics_bridge.h"

// JNI wrapper for KinematicsBridge.processFrame()
// Signature: (Ljava/lang/String;[F[FF)Ljava/lang/String;
extern "C" JNIEXPORT jstring JNICALL
Java_com_gemmafit_jni_KinematicsBridge_processFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jfloatArray landmarks,
    jfloatArray prevLandmarks,
    jfloat visibilityThreshold) {

    // Extract float array from Java
    jsize len = env->GetArrayLength(landmarks);
    if (len != 99) {
        return env->NewStringUTF("{\"error\":\"Invalid landmarks array size\"}");
    }

    jfloat* landmarks_ptr = env->GetFloatArrayElements(landmarks, nullptr);
    std::vector<float> landmarks_vec(landmarks_ptr, landmarks_ptr + len);
    env->ReleaseFloatArrayElements(landmarks, landmarks_ptr, JNI_ABORT);

    // Optional previous landmarks
    std::vector<float> prev_landmarks_vec;
    float* prev_ptr = nullptr;
    if (prevLandmarks != nullptr) {
        jsize prev_len = env->GetArrayLength(prevLandmarks);
        if (prev_len == 99) {
            jfloat* prev = env->GetFloatArrayElements(prevLandmarks, nullptr);
            prev_landmarks_vec.assign(prev, prev + prev_len);
            prev_ptr = prev_landmarks_vec.data();
            env->ReleaseFloatArrayElements(prevLandmarks, prev, JNI_ABORT);
        }
    }

    // Run pipeline
    auto output = gemmafit::bridge::run_biomechanics_pipeline(
        landmarks_vec.data(),
        prev_ptr,
        static_cast<double>(visibilityThreshold)
    );

    if (!output.success) {
        return env->NewStringUTF("{\"error\":\"Pipeline failed\"}");
    }

    return env->NewStringUTF(output.combined_json.c_str());
}

// JNI wrapper for KinematicsBridge companion methods
// These can be used for debugging or accessing individual pipeline stages

extern "C" JNIEXPORT jstring JNICALL
Java_com_gemmafit_jni_KinematicsBridge_getJointAnglesJson(
    JNIEnv* env,
    jobject /*thiz*/,
    jfloatArray landmarks) {

    jsize len = env->GetArrayLength(landmarks);
    if (len != 99) {
        return env->NewStringUTF("{\"error\":\"Invalid size\"}");
    }

    jfloat* ptr = env->GetFloatArrayElements(landmarks, nullptr);
    auto landmarks_arr = gemmafit::kinematics::landmarks_from_float99(ptr, len);
    auto angles = gemmafit::kinematics::compute_all_joint_angles(landmarks_arr);
    std::string json = gemmafit::bridge::to_json(angles);
    env->ReleaseFloatArrayElements(landmarks, ptr, JNI_ABORT);

    return env->NewStringUTF(json.c_str());
}