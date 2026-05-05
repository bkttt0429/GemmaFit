#include "llm_bridge.h"

#include <sstream>

namespace gemmafit::bridge {

LlmOutput run_llm_inference(
    const LlmInput& input, const std::string& model_path) {
    LlmOutput output;
    output.success = false;
    output.function_call.args_json = "{}";

    // Build the prompt with system message + biomechanics data
    std::string prompt = build_system_prompt();
    prompt += "\n\nCurrent movement data:\n";
    prompt += input.movement_pattern_json + "\n";
    prompt += input.safety_json + "\n";
    prompt += input.muscle_focus_json + "\n";

    if (!input.conversation_history.empty()) {
        prompt += "\nPrevious interactions:\n";
        prompt += input.conversation_history + "\n";
    }

    prompt += "\nSelect the most appropriate function to call based on the safety data above.";

    (void)input;
    (void)model_path;
    (void)prompt;

    output.error_message = "llama_cpp_backend_not_linked";
    return output;
}

FunctionCall parse_function_call(const std::string& raw_output) {
    FunctionCall fc;
    // Parse JSON from llama.cpp output.
    // Expected format: {"function": "...", "args": {...}}
    //
    // Simple parser for the known 8 function names.
    for (const auto& name : {
             "correct_knee_alignment",
             "correct_spinal_alignment",
             "correct_joint_angle",
             "correct_asymmetry",
             "warn_com_offset",
             "warn_rapid_movement",
             "increase_range_of_motion",
             "positive_reinforcement",
         }) {
        if (raw_output.find(name) != std::string::npos) {
            fc.function_name = name;
            fc.args_json = "{}";
            return fc;
        }
    }
    return fc;
}

std::string build_system_prompt() {
    return R"(You are a biomechanics-based fitness coach AI. Your role is to analyze
real-time movement data and provide corrective guidance through
Function Calling. Never diagnose medical conditions. Only provide
evidence-based movement corrections based on the detected safety
violations.

Available functions:

1. correct_knee_alignment(side, ratio, severity)
   - Use when knee/ankle distance ratio < 0.8

2. correct_spinal_alignment(deviation, region)
   - Use when spine or neck deviation > 15°

3. correct_joint_angle(joint, current, safe_range)
   - Use when joint is hyperextended or locked

4. correct_asymmetry(joint, left, right)
   - Use when bilateral joint angle difference > 10°

5. warn_com_offset(direction, distance)
   - Use when center of mass leaves support base

6. warn_rapid_movement(joint, velocity)
   - Use when angular velocity > 600 deg/s

7. increase_range_of_motion(joint, current, target)
   - Use when ROM < 50% of expected

8. positive_reinforcement(pattern, primary_muscles, streak)
   - Use when 30+ frames show no safety violations

Select ONE function based on the most severe violation.
Priority: knee_alignment > spinal_alignment > joint_angle >
asymmetry > com_offset > rapid_movement > rom > reinforcement.)";
}

}  // namespace gemmafit::bridge
