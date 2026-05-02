#include "../kinematics/safety_monitor.h"

#include <cmath>
#include <iostream>

using gemmafit::kinematics::check_rule6_rapid_movement;
using gemmafit::kinematics::kRapidMovementThresholdDegPerSecond;
using gemmafit::kinematics::kSevereRapidMovementThresholdDegPerSecond;

namespace {

int passed = 0;
int failed = 0;

void check(const char* name, bool condition) {
    if (condition) {
        ++passed;
        std::cout << "[PASS] " << name << "\n";
    } else {
        ++failed;
        std::cout << "[FAIL] " << name << "\n";
    }
}

bool near(double lhs, double rhs, double tolerance = 1e-9) {
    return std::abs(lhs - rhs) <= tolerance;
}

}  // namespace

int main() {
    {
        const auto safe = check_rule6_rapid_movement(
            "knee", kRapidMovementThresholdDegPerSecond);
        check("Rule 6 does not trigger at threshold", safe.rule == 0);
    }

    {
        const auto violation = check_rule6_rapid_movement(
            "knee", kRapidMovementThresholdDegPerSecond + 1.0);
        check("Rule 6 triggers above deg/s threshold", violation.rule == 6);
        check("Rule 6 metric is deg/s", violation.metric == "angular_velocity_deg_s");
        check("Rule 6 threshold is 600 deg/s", near(violation.threshold, 600.0));
        check("Rule 6 moderate severity below severe threshold", near(violation.severity, 0.5));
    }

    {
        const auto violation = check_rule6_rapid_movement(
            "hip", -(kSevereRapidMovementThresholdDegPerSecond + 1.0));
        check("Rule 6 uses absolute angular velocity", violation.rule == 6);
        check("Rule 6 severe threshold works", near(violation.severity, 0.9));
        check("Rule 6 keeps signed value for reporting", violation.value < 0.0);
    }

    std::cout << "Result: " << passed << " PASS, " << failed << " FAIL\n";
    return failed == 0 ? 0 : 1;
}
