#pragma once

#include <string>

namespace gemmafit::bridge {

// ── JNI LLM Bridge ──────────────────────────────────────────────────
// Interfaces with llama.cpp engine via JNI.
// Sends safety/movement JSON to Gemma 4 and returns FC call result.

struct LlmInput {
    std::string movement_pattern_json;
    std::string safety_json;
    std::string muscle_focus_json;
    // Optional: conversation history for multi-turn coaching.
    std::string conversation_history;
};

struct FunctionCall {
    std::string function_name;   // e.g. "correct_knee_alignment"
    std::string args_json;       // e.g. {"side":"left","ratio":0.72}
};

struct LlmOutput {
    bool success = false;
    FunctionCall function_call;
    std::string raw_response;    // full llama.cpp output for debugging
    double inference_time_ms = 0.0;
    std::string error_message;
};

// Build the prompt from biomechanics JSON → send to llama.cpp → parse FC.
// model_path: path to GGUF model file on the Android filesystem.
LlmOutput run_llm_inference(
    const LlmInput& input,
    const std::string& model_path);

// Parse a Function Calling response from llama.cpp output.
// Gemma 4 outputs JSON with "function" and "args" fields.
FunctionCall parse_function_call(const std::string& raw_output);

// Build the system prompt with Function Calling tool schemas.
std::string build_system_prompt();

}  // namespace gemmafit::bridge
