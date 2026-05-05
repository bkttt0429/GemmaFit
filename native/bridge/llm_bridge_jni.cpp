#include <jni.h>
#include <exception>
#include <fstream>
#include <string>

#include "llm_bridge.h"

namespace {

std::string json_escape(const std::string& value) {
    std::string escaped;
    escaped.reserve(value.size() + 16);
    for (unsigned char c : value) {
        switch (c) {
            case '\"':
                escaped += "\\\"";
                break;
            case '\\':
                escaped += "\\\\";
                break;
            case '\b':
                escaped += "\\b";
                break;
            case '\f':
                escaped += "\\f";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                if (c < 0x20) {
                    const char* hex = "0123456789abcdef";
                    escaped += "\\u00";
                    escaped += hex[(c >> 4) & 0x0F];
                    escaped += hex[c & 0x0F];
                } else {
                    escaped += static_cast<char>(c);
                }
                break;
        }
    }
    return escaped;
}

bool has_suffix(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
        value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool file_exists_and_not_empty(const std::string& path) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    return file.good() && file.tellg() > 0;
}

std::string args_or_empty_object(const std::string& args_json) {
    return args_json.empty() ? "{}" : args_json;
}

jstring safe_error_json(JNIEnv* env, const std::string& error_message) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    std::string response = "{\"success\":false,\"function\":\"\",\"args\":{},";
    response += "\"backend\":\"llama.cpp\",\"selection_basis\":\"jni_error\",";
    response += "\"evidence_refs\":[],\"model_info\":{\"backend\":\"llama.cpp\"},";
    response += "\"raw_response\":\"\",\"inference_time_ms\":0,";
    response += "\"error\":\"" + json_escape(error_message) + "\"}";
    return env->NewStringUTF(response.c_str());
}

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) {
            chars_ = env_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    bool ok() const {
        return value_ != nullptr && chars_ != nullptr;
    }

    const char* c_str() const {
        return chars_;
    }

    std::string str() const {
        return chars_ == nullptr ? std::string() : std::string(chars_);
    }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

}  // namespace

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

    try {
        ScopedUtfChars pattern(env, movementPatternJson);
        ScopedUtfChars safety(env, safetyJson);
        ScopedUtfChars muscle(env, muscleJson);
        ScopedUtfChars model(env, modelPath);
        ScopedUtfChars history(env, conversationHistory);
        if (!pattern.ok() || !safety.ok() || !muscle.ok() || !model.ok() || !history.ok()) {
            return safe_error_json(env, "Invalid or unavailable JNI string input");
        }
        std::string model_path = model.str();

        gemmafit::bridge::LlmInput input;
        input.movement_pattern_json = pattern.c_str();
        input.safety_json = safety.c_str();
        input.muscle_focus_json = muscle.c_str();
        input.conversation_history = history.c_str();

        gemmafit::bridge::LlmOutput output =
            gemmafit::bridge::run_llm_inference(input, model.c_str());

        // Build JSON response
        std::string response = "{";
        response += "\"success\":" + std::string(output.success ? "true" : "false") + ",";
        response += "\"function\":\"" + json_escape(output.function_call.function_name) + "\",";
        response += "\"args\":" + args_or_empty_object(output.function_call.args_json) + ",";
        response += "\"backend\":\"llama.cpp\",";
        response += "\"selection_basis\":\"" + json_escape(output.success ? "llama.cpp function call" : output.error_message) + "\",";
        response += "\"evidence_refs\":[],";
        response += "\"model_info\":{\"backend\":\"llama.cpp\",\"path\":\"" + json_escape(model_path) + "\"},";
        response += "\"raw_response\":\"" + json_escape(output.raw_response) + "\",";
        response += "\"inference_time_ms\":" + std::to_string(output.inference_time_ms);
        if (!output.error_message.empty()) {
            response += ",\"error\":\"" + json_escape(output.error_message) + "\"";
        }
        response += "}";

        return env->NewStringUTF(response.c_str());
    } catch (const std::exception& e) {
        return safe_error_json(env, e.what());
    } catch (...) {
        return safe_error_json(env, "Unknown native exception");
    }
}

// JNI wrapper for LLMBridge.validateModel()
extern "C" JNIEXPORT jboolean JNICALL
Java_com_gemmafit_jni_LLMBridge_validateModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath) {

    try {
        ScopedUtfChars path_chars(env, modelPath);
        if (!path_chars.ok()) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return JNI_FALSE;
        }
        std::string path = path_chars.str();
        bool valid = has_suffix(path, ".gguf") && file_exists_and_not_empty(path);
        return valid ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
}

// JNI wrapper for LLMBridge.getModelInfo()
extern "C" JNIEXPORT jstring JNICALL
Java_com_gemmafit_jni_LLMBridge_getModelInfo(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring modelPath) {

    try {
        ScopedUtfChars path_chars(env, modelPath);
        if (!path_chars.ok()) {
            return safe_error_json(env, "Invalid or unavailable model path");
        }
        std::string path = path_chars.str();
        bool valid = has_suffix(path, ".gguf") && file_exists_and_not_empty(path);
        std::string info = "{\"path\":\"" + json_escape(path) + "\",";
        info += "\"format\":\"GGUF\",";
        info += "\"backend\":\"llama.cpp\",";
        info += "\"available\":" + std::string(valid ? "true" : "false") + "}";

        return env->NewStringUTF(info.c_str());
    } catch (const std::exception& e) {
        return safe_error_json(env, e.what());
    } catch (...) {
        return safe_error_json(env, "Unknown native exception");
    }
}
