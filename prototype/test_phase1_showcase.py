"""
GemmaFit Phase 1 showcase test.

This script validates Phase 1 prototype modules and demonstrates a camera-free
end-to-end pipeline with mock landmarks only.

Run:
  cd prototype
  python test_phase1_showcase.py
"""

from __future__ import annotations

import re
import subprocess
import sys
import traceback
from dataclasses import dataclass, field
from types import SimpleNamespace
from typing import Any, Callable

import numpy as np


for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")


@dataclass
class SectionResult:
    name: str
    passed: int = 0
    total: int = 0
    details: list[tuple[str, bool, str]] = field(default_factory=list)

    def add(self, label: str, ok: bool, message: str = "") -> None:
        self.total += 1
        if ok:
            self.passed += 1
        self.details.append((label, ok, message))
        status = "PASS" if ok else "FAIL"
        suffix = f" - {message}" if message else ""
        print(f"  [{status}] {label}{suffix}")

    @property
    def ok(self) -> bool:
        return self.passed == self.total


@dataclass
class SuiteResult:
    label: str
    command: list[str]
    exit_code: int
    pass_count: int
    fail_count: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.exit_code == 0 and self.fail_count == 0


def expect(section: SectionResult, label: str, condition: bool, message: str = "") -> None:
    section.add(label, bool(condition), message)


def expect_close(section: SectionResult, label: str, got: float, expected: float, tol: float) -> None:
    ok = abs(got - expected) <= tol
    section.add(label, ok, f"got={got:.4f}, expected={expected:.4f}+/-{tol:g}")


def count_markers(stdout: str) -> tuple[int, int]:
    """Count explicit test markers and summary counts while ignoring '0 FAIL'."""
    pass_count = len(re.findall(r"\[PASS\]", stdout))
    fail_count = len(re.findall(r"\[FAIL\]", stdout))

    summary_passes = [int(v) for v in re.findall(r"(\d+)\s+PASS", stdout)]
    summary_fails = [int(v) for v in re.findall(r"(\d+)\s+FAIL", stdout)]
    if summary_passes:
        pass_count = max(pass_count, max(summary_passes))
    if summary_fails:
        fail_count = max(fail_count, max(summary_fails))
    return pass_count, fail_count


def run_suite(label: str, command: list[str]) -> SuiteResult:
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    pass_count, fail_count = count_markers(completed.stdout)
    if completed.returncode == 0 and pass_count == 0 and fail_count == 0:
        pass_count = 1

    return SuiteResult(
        label=label,
        command=command,
        exit_code=completed.returncode,
        pass_count=pass_count,
        fail_count=fail_count,
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


def section_1_imports() -> tuple[SectionResult, dict[str, Any]]:
    print("\nSECTION 1: Module import health check")
    section = SectionResult("Module imports")
    imports: dict[str, Any] = {}

    import_specs: list[tuple[str, list[str], Callable[[], dict[str, Any]]]] = [
        (
            "compute_angles",
            [
                "calculate_joint_angle",
                "calculate_angular_velocity_dps",
                "detect_knee_valgus",
                "detect_back_slack",
                "detect_rapid_movement",
                "get_squat_phase",
                "RAPID_MOVEMENT_THRESHOLD_DPS",
            ],
            lambda: __import__(
                "compute_angles",
                fromlist=[
                    "calculate_joint_angle",
                    "calculate_angular_velocity_dps",
                    "detect_knee_valgus",
                    "detect_back_slack",
                    "detect_rapid_movement",
                    "get_squat_phase",
                    "RAPID_MOVEMENT_THRESHOLD_DPS",
                ],
            ).__dict__,
        ),
        (
            "movement_classifier_prototype",
            ["classify_movement", "MovementPattern"],
            lambda: __import__(
                "movement_classifier_prototype",
                fromlist=["classify_movement", "MovementPattern"],
            ).__dict__,
        ),
        (
            "muscle_focus_prototype",
            ["estimate_muscle_focus", "MuscleFocusEstimate"],
            lambda: __import__(
                "muscle_focus_prototype",
                fromlist=["estimate_muscle_focus", "MuscleFocusEstimate"],
            ).__dict__,
        ),
        (
            "com_tracker_prototype",
            [
                "track_com",
                "whole_body_com",
                "support_polygon",
                "_make_mock_landmarks",
                "Point2D",
            ],
            lambda: __import__(
                "com_tracker_prototype",
                fromlist=[
                    "track_com",
                    "whole_body_com",
                    "support_polygon",
                    "_make_mock_landmarks",
                    "Point2D",
                    "LM",
                ],
            ).__dict__,
        ),
        (
            "smooth_angle",
            [
                "SavitzkyGolay",
                "compute_angular_velocity",
                "detect_rapid_movement",
                "RAPID_MOVEMENT_THRESHOLD_DPS",
            ],
            lambda: __import__(
                "smooth_angle",
                fromlist=[
                    "SavitzkyGolay",
                    "compute_angular_velocity",
                    "detect_rapid_movement",
                    "RAPID_MOVEMENT_THRESHOLD_DPS",
                ],
            ).__dict__,
        ),
        (
            "rep_counter",
            ["RepCounter", "RepPhase"],
            lambda: __import__("rep_counter", fromlist=["RepCounter", "RepPhase"]).__dict__,
        ),
    ]

    for module_name, required_symbols, importer in import_specs:
        try:
            module_dict = importer()
            missing = [name for name in required_symbols if name not in module_dict]
            if missing:
                raise AttributeError(f"missing symbols: {', '.join(missing)}")
            imports.update({name: module_dict[name] for name in required_symbols})
            if "LM" in module_dict:
                imports["LM"] = module_dict["LM"]
            section.add(module_name, True)
        except (ImportError, AttributeError) as exc:
            section.add(module_name, False, str(exc))

    return section, imports


def section_2_builtin_suites() -> tuple[SectionResult, list[SuiteResult]]:
    print("\nSECTION 2: Built-in unit and demo suites")
    section = SectionResult("Built-in suites")
    commands = [
        ("compute_angles (test_angles)", [sys.executable, "test_angles.py"]),
        ("muscle_focus_prototype", [sys.executable, "muscle_focus_prototype.py", "--test"]),
        ("com_tracker_prototype", [sys.executable, "com_tracker_prototype.py", "--test"]),
        ("movement_classifier_prototype", [sys.executable, "movement_classifier_prototype.py", "--test"]),
        ("8 safety rules", [sys.executable, "test_8rules.py"]),
        ("smooth_angle A1 demo", [sys.executable, "smooth_angle.py", "--demo"]),
        ("rep_counter A3 demo", [sys.executable, "rep_counter.py", "--demo"]),
    ]

    suite_results: list[SuiteResult] = []
    for label, command in commands:
        result = run_suite(label, command)
        suite_results.append(result)
        message = f"exit={result.exit_code}, pass={result.pass_count}, fail={result.fail_count}"
        section.add(label, result.ok, message)
        if not result.ok:
            print(f"\n--- stdout: {label} ---")
            print(result.stdout)
            if result.stderr:
                print(f"\n--- stderr: {label} ---")
                print(result.stderr)

    return section, suite_results


def call_knee_valgus_detector(
    detector: Callable[..., bool],
    left_knee: SimpleNamespace,
    right_knee: SimpleNamespace,
    left_ankle: SimpleNamespace,
    right_ankle: SimpleNamespace,
) -> bool:
    """Support both landmark-object and distance-based detector signatures."""
    try:
        return bool(detector(left_knee, right_knee, left_ankle, right_ankle))
    except TypeError:
        d_knee = abs(right_knee.x - left_knee.x)
        d_ankle = abs(right_ankle.x - left_ankle.x)
        return bool(detector(d_knee, d_ankle))


def section_3_integration(imports: dict[str, Any]) -> SectionResult:
    print("\nSECTION 3: Integration pipeline - mock squat frame")
    section = SectionResult("Integration pipeline")

    calculate_joint_angle = imports["calculate_joint_angle"]
    estimate_muscle_focus = imports["estimate_muscle_focus"]
    track_com = imports["track_com"]
    make_mock_landmarks = imports["_make_mock_landmarks"]
    compute_angular_velocity = imports["compute_angular_velocity"]
    sg_detect_rapid = imports["detect_rapid_movement"]
    rapid_threshold = imports["RAPID_MOVEMENT_THRESHOLD_DPS"]
    rep_counter_cls = imports["RepCounter"]

    lms = make_mock_landmarks()

    print("  Step A - COM tracking")
    result = track_com(lms, sex="male", contact="bipedal")
    expect(section, "standing COM inside BoS", result.inside is True)
    expect(section, "COM offset ratio in [0, 1]", 0.0 <= result.offset_ratio <= 1.0)
    expect(section, "COM evidence label", result.evidence == "prototype_threshold")
    expect(section, "COM note label", result.note == "estimated_com_not_force_plate")

    print("  Step B - Muscle focus estimation")
    pattern = {"pattern": "knee_dominant_bilateral", "phase": "descent"}
    mf = estimate_muscle_focus(pattern)
    expect(section, "knee-dominant confidence high", mf.confidence == "high")
    expect(section, "quadriceps in primary", "quadriceps" in mf.estimated_primary)
    expect(section, "gluteus_maximus in primary", "gluteus_maximus" in mf.estimated_primary)
    expect(section, "muscle note label", mf.note == "pose_based_estimate_not_emg")

    print("  Step C - Muscle focus fallback chain")
    c1 = estimate_muscle_focus({"primary_joint": "knee"})
    expect(section, "fallback knee pattern", c1.pattern_matched == "knee_dominant_bilateral")
    expect(section, "fallback knee confidence", c1.confidence == "medium")

    c2 = estimate_muscle_focus({"base": "unipedal"})
    expect(section, "fallback unipedal pattern", c2.pattern_matched == "unilateral_stability")
    expect(section, "fallback unipedal confidence", c2.confidence == "medium")

    c3 = estimate_muscle_focus({})
    expect(section, "fallback empty confidence", c3.confidence == "unknown")
    expect(section, "fallback empty primary", c3.estimated_primary == [])

    print("  Step D - Joint angle smoke test")
    angle_90 = calculate_joint_angle(
        np.array([0.0, 0.0]),
        np.array([1.0, 0.0]),
        np.array([1.0, 1.0]),
    )
    expect_close(section, "known 90 degree angle", angle_90, 90.0, 1.0)

    angle_180 = calculate_joint_angle(
        np.array([0.0, 0.0]),
        np.array([0.5, 0.0]),
        np.array([1.0, 0.0]),
    )
    expect_close(section, "known 180 degree angle", angle_180, 180.0, 1.0)

    print("  Step E - Savitzky-Golay Rule 6 smoke test")
    rapid_angles = np.concatenate([
        np.full(12, 60.0),
        np.linspace(60.0, 180.0, 4),
        np.full(34, 180.0),
    ])
    velocities = compute_angular_velocity(rapid_angles, fps=30.0)
    max_velocity = float(np.max(np.abs(velocities)))
    rapid_flags = sg_detect_rapid(velocities, threshold=rapid_threshold, consecutive_frames=3)
    expect(section, "Rule 6 threshold is 600 deg/s", rapid_threshold == 600.0)
    expect(section, "SG rapid movement crosses threshold", max_velocity > rapid_threshold,
           f"max_velocity={max_velocity:.1f}")
    expect(section, "SG rapid movement detected", bool(np.any(rapid_flags)))

    print("  Step F - Rep counter smoke test")
    counter = rep_counter_cls(primary_joint="left_knee", fps=30.0)
    squat_angles = np.concatenate([
        np.full(8, 175.0),
        np.linspace(175.0, 90.0, 25),
        np.full(4, 90.0),
        np.linspace(90.0, 175.0, 25),
        np.full(8, 175.0),
    ])
    for angle in squat_angles:
        counter.update(float(angle), safety_flags=0)
    expect(section, "rep counter completes one rep", counter.rep_count == 1,
           f"reps={counter.rep_count}")
    expect(section, "rep quality remains high", counter.last_rep_quality >= 95.0,
           f"quality={counter.last_rep_quality:.1f}")

    return section


def section_4_safety_quick_check(imports: dict[str, Any]) -> SectionResult:
    print("\nSECTION 4: Safety rule quick-check - bad pose detection")
    section = SectionResult("Safety rule quick-check")

    detect_knee_valgus = imports["detect_knee_valgus"]
    track_com = imports["track_com"]
    make_mock_landmarks = imports["_make_mock_landmarks"]
    lm = imports.get("LM")

    if lm is None:
        left_heel, right_heel = 29, 30
        left_foot, right_foot = 31, 32
    else:
        left_heel, right_heel = lm.LEFT_HEEL, lm.RIGHT_HEEL
        left_foot, right_foot = lm.LEFT_FOOT_INDEX, lm.RIGHT_FOOT_INDEX

    print("  Test 4A - Knee valgus (Rule #1)")
    knee_l = SimpleNamespace(x=0.47, y=0.70)
    knee_r = SimpleNamespace(x=0.53, y=0.70)
    ankle_l = SimpleNamespace(x=0.37, y=0.88)
    ankle_r = SimpleNamespace(x=0.63, y=0.88)

    d_knee = abs(knee_r.x - knee_l.x)
    d_ankle = abs(ankle_r.x - ankle_l.x)
    ratio = d_knee / d_ankle
    expect(section, "valgus ratio below threshold", ratio < 0.75, f"ratio={ratio:.4f}")
    expect(
        section,
        "detect_knee_valgus catches bad pose",
        call_knee_valgus_detector(detect_knee_valgus, knee_l, knee_r, ankle_l, ankle_r),
    )

    print("  Test 4B - COM offset (Rule #5)")
    offset_lms = make_mock_landmarks({
        left_heel: (0.80, 0.92),
        right_heel: (0.90, 0.92),
        left_foot: (0.80, 0.98),
        right_foot: (0.90, 0.98),
    })
    result = track_com(offset_lms, contact="bipedal")
    expect(section, "shifted BoS puts COM outside", result.inside is False)
    expect(section, "shifted BoS offset ratio > 1", result.offset_ratio > 1.0,
           f"ratio={result.offset_ratio:.4f}")

    return section


def fmt_count(passed: int, total: int) -> str:
    status = "PASS" if passed == total else "FAIL"
    return f"{passed}/{total} {status}"


def box_row(left: str, right: str) -> str:
    return f"| {left:<42} | {right:>14} |"


def print_summary(
    import_section: SectionResult,
    suite_section: SectionResult,
    suite_results: list[SuiteResult],
    integration_section: SectionResult,
    safety_section: SectionResult,
) -> None:
    suite_pass = sum(s.pass_count for s in suite_results if s.ok)
    suite_total = sum(s.pass_count + s.fail_count for s in suite_results)
    if suite_total == 0:
        suite_total = suite_pass

    total_pass = (
        import_section.passed
        + suite_pass
        + integration_section.passed
        + safety_section.passed
    )
    total_count = (
        import_section.total
        + suite_total
        + integration_section.total
        + safety_section.total
    )

    print("\n+------------------------------------------------------------+")
    print("|             GemmaFit Phase 1 Showcase Test                |")
    print("+------------------------------------------------------------+")
    print(box_row("Section 1: Module imports", fmt_count(import_section.passed, import_section.total)))
    print(box_row("Section 2: Built-in suites", fmt_count(suite_pass, suite_total)))
    for suite in suite_results:
        suite_total_i = suite.pass_count + suite.fail_count
        right = fmt_count(suite.pass_count, suite_total_i)
        if suite.exit_code != 0:
            right = f"exit {suite.exit_code}"
        print(box_row(f"  - {suite.label}", right))
    print(box_row("Section 3: Integration pipeline", fmt_count(integration_section.passed, integration_section.total)))
    print(box_row("Section 4: Safety rule quick-check", fmt_count(safety_section.passed, safety_section.total)))
    print("+------------------------------------------------------------+")
    print(box_row("TOTAL", fmt_count(total_pass, total_count)))
    print("+------------------------------------------------------------+")


def main() -> int:
    try:
        import_section, imports = section_1_imports()
        suite_section, suite_results = section_2_builtin_suites()

        if not import_section.ok:
            integration_section = SectionResult("Integration pipeline")
            safety_section = SectionResult("Safety rule quick-check")
        else:
            integration_section = section_3_integration(imports)
            safety_section = section_4_safety_quick_check(imports)

        print_summary(
            import_section,
            suite_section,
            suite_results,
            integration_section,
            safety_section,
        )

        all_ok = (
            import_section.ok
            and suite_section.ok
            and integration_section.ok
            and safety_section.ok
        )
        return 0 if all_ok else 1
    except Exception:
        print("\n[FAIL] Unhandled showcase error")
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
