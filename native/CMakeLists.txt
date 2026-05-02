cmake_minimum_required(VERSION 3.18)

project(GemmaFitNative LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Kinematics library (core biomechanics) ───────────────────────────

add_library(gemmafit_kinematics
    kinematics/com_tracker.cpp
    kinematics/joint_angles.cpp
    kinematics/body_segments.cpp
    kinematics/symmetry.cpp
    kinematics/safety_monitor.cpp
    kinematics/movement_classifier.cpp
    kinematics/motion_quality.cpp
    kinematics/muscle_focus.cpp
    kinematics/confidence_gate.cpp
)

target_include_directories(gemmafit_kinematics
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/kinematics
)

# ── Bridge library (JNI interface layer) ────────────────────────────

# Android NDK provides JNI headers automatically
if(ANDROID)
    find_library(log-lib log)
endif()

set(GEMMAFIT_BRIDGE_SOURCES
    bridge/kinematics_bridge.cpp
    bridge/llm_bridge.cpp
)

if(ANDROID)
    list(APPEND GEMMAFIT_BRIDGE_SOURCES
        bridge/kinematics_bridge_jni.cpp
        bridge/llm_bridge_jni.cpp
    )
endif()

add_library(gemmafit_bridge SHARED
    ${GEMMAFIT_BRIDGE_SOURCES}
)

target_include_directories(gemmafit_bridge
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/bridge
        ${CMAKE_CURRENT_SOURCE_DIR}/kinematics
)

target_link_libraries(gemmafit_bridge
    PUBLIC
        gemmafit_kinematics
)

if(ANDROID)
    target_link_libraries(gemmafit_bridge PUBLIC ${log-lib})
endif()

# ── Unit tests ───────────────────────────────────────────────────────

# Helper macro for test executables
macro(add_kinematics_test test_name)
    add_executable(${test_name}
        tests/${test_name}.cpp
    )
    target_link_libraries(${test_name}
        PRIVATE
            gemmafit_kinematics
    )
    add_test(NAME ${test_name} COMMAND ${test_name})
endmacro()

add_kinematics_test(test_com_tracker)
add_kinematics_test(test_safety_monitor)
add_kinematics_test(test_kinematics_pipeline)
add_kinematics_test(test_motion_quality)
# Tests for new modules — stub .cpp files to be added later:
# add_kinematics_test(test_joint_angles)
# add_kinematics_test(test_body_segments)
# add_kinematics_test(test_symmetry)
# add_kinematics_test(test_movement_classifier)
# add_kinematics_test(test_muscle_focus)
# add_kinematics_test(test_confidence_gate)

# ── Bridge tests ─────────────────────────────────────────────────────

# add_executable(test_kinematics_bridge
#     tests/test_kinematics_bridge.cpp
# )
# target_link_libraries(test_kinematics_bridge
#     PRIVATE
#         gemmafit_bridge
#         gemmafit_kinematics
# )

# Enable CTest
enable_testing()
