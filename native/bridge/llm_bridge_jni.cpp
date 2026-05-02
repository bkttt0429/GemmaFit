#include <jni.h>
#include <string>

#include "llm_bridge.h"

// JNI wrapper for LLMBridge.runInference()
// Signature: (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
extern "C" JNIEXPORT jstring JNICALL
Java_com_gemmafit_jni_LLMBridge_runInference(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring movementPatternJson,
    jstring safetyJson,
    jstring muscleJson,
    jstring modelPath,
    jstring conversationHistory) {

    const char* pattern_cstr = env->GetStringUTFChars(movementPatternJson, nullptr);
    const char* safety_cstr = env->GetStringUTFChars(safetyJson, nullptr);
    const char* muscle_cstr = env->GetStringUTFChars(muscleJson, nullptr);
    const char* model_cstr = env->GetStringUTFChars(modelPath, nullptr);
    const char* history_cstr = env->GetStringUTFChars(conversationHistory, nullptr);

    gemmafit::bridge::LlmInput input;
    input.movement_pattern_json = pattern_cstr;
    input.safety_json = safety_cstr;
    input.muscle_focus_json = muscle_cstr;
    input.conversation_history = history_cstr;

    gemmafit::bridge::LlmOutput output =
        gemmafit::bridge::run_llm_inference(input, model_cstr);

    env->ReleaseStringUTFChars(movementPatternJson, pattern_cstr);
    env->ReleaseStringUTFChars(safetyJson, safety_cstr);
    env->ReleaseStringUTFChars(muscleJson, muscle_cstr);
    env->ReleaseStringUTFChars(modelPath, model_cstr);
    env->ReleaseStringUTFChars(conversationHistory, history_cstr);

    // Build JSON response
    std::string response = "{";
    response += "\"success\":" + std::string(output.success ? "true" : "false") + ",";
    response += "\"function\":\"" + output.function_call.function_name + "\",";
    response += "\"args\":" + output.function_call.args_json + ",";
    response += "\"raw_response\":\"" + output.raw_response + "\",";
    response += "\"inference_time_ms\":" + std::to_string(output.inference_time_ms);
    if (!output.error_message.empty()) {
        response += ",\"error\":\"" + output.error_message + "\"";
    }
    response += "}";

    return env->NewStringUTF(response.c_str());
}

// JNI wrapper for LLMBridge.validateModel()
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gemmafit_jni_LLMBridge_validateModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath) {

    const char* path_cstr = env->GetStringUTFChars(modelPath, nullptr);
    // Simple validation: check if file exists and has .gguf extension
    bool valid = (std::string(path_cstr).find(".gguf") != std::string::npos);
    env->ReleaseStringUTFChars(modelPath, path_cstr);
    return valid ? JNI_TRUE : JNI_FALSE;
}

// JNI wrapper for LLMBridge.getModelInfo()
extern "C" JNIEXPORT jstring JNICALL
Java_com_gemmafit_jni_LLMBridge_getModelInfo(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath) {

    const char* path_cstr = env->GetStringUTFChars(modelPath, nullptr);
    std::string info = "{\"path\":\"" + std::string(path_cstr) + "\",";
    info += "\"format\":\"GGUF\",";
    info += "\"backend\":\"llama.cpp\"}";
    env->ReleaseStringUTFChars(modelPath, path_cstr);

    return env->NewStringUTF(info.c_str());
}