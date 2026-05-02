"""
mock_gemma_feedback.py — Deterministic mock coaching messages

Consumes the structured motion report JSON and generates
plain-language coaching messages for the dashboard.

Every output is tagged with "source": "mock_gemma_feedback"
to distinguish from real Gemma inference.
"""

from typing import Dict, List, Optional


def generate_mock_feedback(report: Dict) -> Dict:
    """
    Generate a deterministic coaching message from a structured motion report.
    
    Returns:
        {
            "source": "mock_gemma_feedback",
            "message": "...",
            "safety_note": "...",
            "priority": "high|medium|low"
        }
    """
    exercise = report.get("exercise", "unknown")
    phase = report.get("phase", "unknown")
    metrics = report.get("metrics", {})
    quality_flags = report.get("quality_flags", [])
    overall_status = report.get("overall_status", "OK")
    
    message = ""
    priority = "low"
    
    # Handle low confidence / view limited first
    if overall_status == "LOW_CONFIDENCE":
        message = "Please adjust your position so your full body is visible to the camera."
        priority = "medium"
    elif overall_status == "VIEW_LIMITED":
        message = "The current view angle makes some feedback unreliable. Try to face the camera more directly."
        priority = "medium"
    elif overall_status == "OK":
        # Positive reinforcement
        if exercise == "push_up":
            message = "Good form! Keep your body in a straight line from shoulders to ankles."
        elif exercise == "squat":
            message = "Nice depth! Keep your chest up and knees tracking over your toes."
        elif exercise == "lunge":
            message = "Good lunge form. Keep your front knee over your ankle."
        elif exercise == "deadlift":
            message = "Good hip hinge. Keep the bar close to your body."
        else:
            message = "Good movement quality. Keep it up!"
        priority = "low"
    else:
        # Build message from active flags
        critical_flags = [f for f in quality_flags if f["status"] == "CRITICAL"]
        warning_flags = [f for f in quality_flags if f["status"] == "WARNING"]
        
        if critical_flags:
            flag = critical_flags[0]
            message = _flag_to_message(flag, exercise, phase, metrics)
            priority = "high"
        elif warning_flags:
            flag = warning_flags[0]
            message = _flag_to_message(flag, exercise, phase, metrics)
            priority = "medium"
        else:
            # Monitor flags
            monitor_flags = [f for f in quality_flags if f["status"] == "MONITOR"]
            if monitor_flags:
                flag = monitor_flags[0]
                message = _flag_to_message(flag, exercise, phase, metrics)
                priority = "low"
    
    return {
        "source": "mock_gemma_feedback",
        "message": message,
        "safety_note": "Pose-based movement quality feedback, not a medical diagnosis.",
        "priority": priority,
    }


def _flag_to_message(flag: Dict, exercise: str, phase: str, metrics: Dict) -> str:
    """Convert a quality flag into a coaching message."""
    flag_id = flag["id"]
    value = flag.get("value", 0.0)
    
    # Exercise-specific messages
    if exercise == "push_up":
        if "hip_sag" in flag_id or "body_line" in flag_id:
            return f"Your hips are sagging {value:.1f} degrees. Brace your core and keep shoulder, hip, and ankle in one line."
        elif "elbow" in flag_id:
            elbow = metrics.get("elbow_angle", 180.0)
            if elbow > 90:
                return f"Lower your chest closer to the ground. Current elbow angle: {elbow:.0f} degrees."
            else:
                return f"Good elbow depth at {elbow:.0f} degrees. Keep your core tight."
        elif "rapid" in flag_id:
            return "Slow down on the way down. Control the descent."
    
    elif exercise == "squat":
        if "knee_valgus" in flag_id:
            return "Push your knees outward so they track over your toes."
        elif "spine" in flag_id or "trunk" in flag_id:
            return "Keep your chest up and maintain a neutral spine."
        elif "depth" in flag_id:
            depth = metrics.get("depth", 0.0)
            if depth < 0.3:
                return "Try to squat a bit deeper if comfortable."
            else:
                return f"Good depth! Current: {depth:.0%}"
        elif "rapid" in flag_id:
            return "Control the tempo. Don't drop into the bottom too fast."
    
    elif exercise == "lunge":
        if "front_knee" in flag_id:
            return "Keep your front knee over your ankle, not past your toes."
        elif "trunk" in flag_id:
            return "Stay upright. Don't lean forward excessively."
    
    elif exercise == "deadlift":
        if "trunk_angle" in flag_id:
            return "Hinge at your hips and keep your back straight."
        elif "hip_hinge" in flag_id:
            return "Focus on pushing your hips back while keeping your chest up."
    
    # Generic fallback messages
    if "knee_valgus" in flag_id:
        return "Keep your knees aligned with your toes."
    elif "spine" in flag_id or "trunk" in flag_id:
        return "Maintain a neutral spine position."
    elif "asymmetry" in flag_id:
        return "Try to keep both sides even and balanced."
    elif "com" in flag_id:
        return "Center your weight over your base of support."
    elif "rapid" in flag_id:
        return "Slow down and control the movement."
    elif "rom" in flag_id:
        return "Move through your full comfortable range of motion."
    elif "neck" in flag_id:
        return "Keep your head in a neutral position."
    elif "joint_overextension" in flag_id:
        return "Avoid locking your joints. Keep a slight bend."
    
    return f"Adjust your form. Flag: {flag_id}"


def generate_session_summary(reports: List[Dict]) -> Dict:
    """Generate a session summary from all frame reports."""
    if not reports:
        return {"error": "No reports available"}
    
    exercises = [r["exercise"] for r in reports if r["exercise"] != "unknown_or_mixed"]
    from collections import Counter
    exercise_counts = Counter(exercises)
    main_exercise = exercise_counts.most_common(1)[0][0] if exercise_counts else "unknown"
    
    total_frames = len(reports)
    critical_frames = sum(1 for r in reports if r["overall_status"] == "CRITICAL")
    warning_frames = sum(1 for r in reports if r["overall_status"] == "WARNING")
    ok_frames = sum(1 for r in reports if r["overall_status"] == "OK")
    
    reps = max(r["rep"] for r in reports) if reports else 0
    
    # Most common issue
    all_flags = []
    for r in reports:
        all_flags.extend([f["id"] for f in r.get("quality_flags", [])])
    flag_counts = Counter(all_flags)
    top_issue = flag_counts.most_common(1)[0] if flag_counts else ("none", 0)
    
    summary_message = f"Session complete: {reps} reps of {main_exercise}. "
    if critical_frames > total_frames * 0.1:
        summary_message += f"Several critical form issues detected ({critical_frames} frames). Focus on correcting these before increasing intensity."
    elif warning_frames > total_frames * 0.2:
        summary_message += f"Some form warnings ({warning_frames} frames). Good progress, but watch your technique."
    else:
        summary_message += "Good overall form! Keep practicing."
    
    return {
        "source": "mock_gemma_feedback",
        "type": "session_summary",
        "message": summary_message,
        "stats": {
            "total_frames": total_frames,
            "reps": reps,
            "main_exercise": main_exercise,
            "critical_frames": critical_frames,
            "warning_frames": warning_frames,
            "ok_frames": ok_frames,
            "top_issue": top_issue[0],
            "top_issue_count": top_issue[1],
        },
        "safety_note": "Pose-based movement quality feedback, not a medical diagnosis.",
    }