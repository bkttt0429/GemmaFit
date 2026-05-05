#include <cassert>
#include <string>

#include "llm_bridge.h"

using gemmafit::bridge::LlmInput;
using gemmafit::bridge::parse_function_call;
using gemmafit::bridge::run_llm_inference;

int main() {
    LlmInput input;
    input.movement_pattern_json = R"({"exercise":"squat"})";
    input.safety_json = R"({"session_summary":{"safety_events":0}})";
    input.muscle_focus_json = R"({"boundary":"pose_estimated_load_focus_not_muscle_activation"})";

    const auto output = run_llm_inference(input, "missing.gguf");
    assert(!output.success);
    assert(output.error_message == "llama_cpp_backend_not_linked");
    assert(output.function_call.function_name.empty());
    assert(output.function_call.args_json == "{}");

    const auto parsed = parse_function_call(
        R"({"function":"warn_rapid_movement","args":{"joint":"knee","velocity":72}})"
    );
    assert(parsed.function_name == "warn_rapid_movement");
    assert(parsed.args_json == "{}");

    return 0;
}
