"""
visualize_angles.py

即時深蹲姿態分析可視化（攝影機或影片）。
在畫面上疊加：關節角度、錯誤熱區、側邊數值面板、深蹲階段。

用法：
  python visualize_angles.py              # 攝影機
  python visualize_angles.py --video path/to/squat.mp4
"""

import argparse
import math

import cv2
import mediapipe as mp
import numpy as np

from compute_angles import (
    calculate_joint_angle,
    detect_back_slack,
    detect_heels_off,
    detect_knee_over_toes,
    detect_knee_valgus,
    get_squat_phase,
    heel_angle,
)

mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils

# ── 顏色常數 (BGR) ───────────────────────────────────────────────
C_OK    = (60, 200, 60)
C_WARN  = (0, 165, 255)
C_ERR   = (0, 0, 220)
C_WHITE = (255, 255, 255)
C_BLACK = (0, 0, 0)
C_PANEL = (30, 30, 30)

PHASE_COLOR = {
    "top":        (200, 200, 200),
    "descending": (100, 200, 255),
    "bottom":     (60, 200, 60),
    "ascending":  (255, 180, 60),
}


# ── 繪圖工具 ─────────────────────────────────────────────────────

def px(landmark, w, h):
    """正規化座標 → pixel tuple。"""
    return (int(landmark.x * w), int(landmark.y * h))


def put_label(img, text, pos, color=C_WHITE, scale=0.55, thickness=1):
    """帶黑底的文字，確保任何背景都清晰。"""
    (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, thickness)
    x, y = pos
    cv2.rectangle(img, (x - 2, y - th - 3), (x + tw + 2, y + 3), C_BLACK, -1)
    cv2.putText(img, text, pos, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)


def draw_angle_arc(img, vertex, p1, p2, angle, color, radius=35):
    """在關節頂點畫角度弧與數值。"""
    cv2.putText(
        img, f"{angle:.0f}°",
        (vertex[0] + 10, vertex[1] - 10),
        cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA,
    )


def draw_bone(img, a, b, color, thickness=4):
    cv2.line(img, a, b, color, thickness, cv2.LINE_AA)


def draw_joint(img, pt, color, r=6):
    cv2.circle(img, pt, r, color, -1)
    cv2.circle(img, pt, r + 2, C_WHITE, 1)


# ── 側邊數值面板 ─────────────────────────────────────────────────

def draw_panel(img, metrics: dict, phase: str, reps: int):
    """在畫面右側畫深色面板顯示所有數值。"""
    h, w = img.shape[:2]
    panel_w = 210
    panel = np.full((h, panel_w, 3), C_PANEL, dtype=np.uint8)

    y = 30
    def row(label, value, ok=True):
        nonlocal y
        color = C_OK if ok else C_ERR
        cv2.putText(panel, label, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.45, C_WHITE, 1, cv2.LINE_AA)
        cv2.putText(panel, str(value), (130, y), cv2.FONT_HERSHEY_SIMPLEX, 0.45, color, 1, cv2.LINE_AA)
        y += 22

    # Phase badge
    ph_color = PHASE_COLOR.get(phase, C_WHITE)
    cv2.rectangle(panel, (6, 5), (panel_w - 6, 28), ph_color, -1)
    cv2.putText(panel, f"Phase: {phase.upper()}", (10, 22),
                cv2.FONT_HERSHEY_SIMPLEX, 0.5, C_BLACK, 1, cv2.LINE_AA)
    y = 50

    row("Reps",            reps)
    cv2.line(panel, (6, y - 5), (panel_w - 6, y - 5), (80, 80, 80), 1)

    row("Knee angle",      f"{metrics['knee_angle']:.1f}°",   90 <= metrics['knee_angle'] <= 130 or phase == "top")
    row("Hip  angle",      f"{metrics['hip_angle']:.1f}°",    70 <= metrics['hip_angle'] <= 120  or phase == "top")
    row("Back angle",      f"{metrics['back_angle']:.1f}°",   60 <= metrics['back_angle'] <= 100 or phase == "top")
    cv2.line(panel, (6, y - 5), (panel_w - 6, y - 5), (80, 80, 80), 1)

    row("Torso ratio",     f"{metrics['torso_ratio']:.2f}",   metrics['torso_ratio'] >= 0.93)
    row("Heel Δ",          f"{metrics['heel_delta']:+.1f}°",  metrics['heel_delta'] <= 3)
    row("Valgus ratio",    f"{metrics['valgus']:.2f}",        metrics['valgus'] >= 0.75)
    cv2.line(panel, (6, y - 5), (panel_w - 6, y - 5), (80, 80, 80), 1)

    row("Knee valgus",     "YES" if metrics['knee_valgus'] else "ok", not metrics['knee_valgus'])
    row("Back slack",      "YES" if metrics['back_slack']  else "ok", not metrics['back_slack'])
    row("Heels off",       "YES" if metrics['heels_off']   else "ok", not metrics['heels_off'])
    row("Knee/toes",       "YES" if metrics['knee_toes']   else "ok", not metrics['knee_toes'])

    return np.hstack([img, panel])


# ── 主骨架繪製 ───────────────────────────────────────────────────

def draw_skeleton(img, lm, w, h, metrics):
    """用顏色區分正常/錯誤的骨架線段與角度標示。"""

    def pt(name):
        return px(lm[mp_pose.PoseLandmark[name].value], w, h)

    l_sho = pt("LEFT_SHOULDER");  r_sho = pt("RIGHT_SHOULDER")
    l_hip = pt("LEFT_HIP");       r_hip = pt("RIGHT_HIP")
    l_kne = pt("LEFT_KNEE");      r_kne = pt("RIGHT_KNEE")
    l_ank = pt("LEFT_ANKLE");     r_ank = pt("RIGHT_ANKLE")
    l_hee = pt("LEFT_HEEL");      l_toe = pt("LEFT_FOOT_INDEX")
    r_hee = pt("RIGHT_HEEL");     r_toe = pt("RIGHT_FOOT_INDEX")

    # 背部顏色
    back_c = C_ERR if metrics['back_slack'] else C_OK
    # 膝蓋顏色
    knee_c = C_ERR if metrics['knee_valgus'] else C_OK
    # 腳跟顏色
    heel_c = C_WARN if metrics['heels_off'] else C_OK

    # 骨架線
    draw_bone(img, l_sho, l_hip,  back_c)
    draw_bone(img, r_sho, r_hip,  back_c)
    draw_bone(img, l_sho, r_sho,  C_WHITE, 2)
    draw_bone(img, l_hip, r_hip,  C_WHITE, 2)

    draw_bone(img, l_hip, l_kne,  knee_c)
    draw_bone(img, l_kne, l_ank,  knee_c)
    draw_bone(img, r_hip, r_kne,  knee_c)
    draw_bone(img, r_kne, r_ank,  knee_c)

    draw_bone(img, l_ank, l_hee,  heel_c)
    draw_bone(img, l_hee, l_toe,  heel_c)
    draw_bone(img, r_ank, r_hee,  heel_c)
    draw_bone(img, r_hee, r_toe,  heel_c)

    # 關節點
    for pt_pos, color in [
        (l_sho, back_c), (r_sho, back_c),
        (l_hip, back_c), (r_hip, back_c),
        (l_kne, knee_c), (r_kne, knee_c),
        (l_ank, heel_c), (r_ank, heel_c),
    ]:
        draw_joint(img, pt_pos, color)

    # 角度標示
    draw_angle_arc(img, l_kne, l_hip, l_ank, metrics['knee_angle'], knee_c)
    draw_angle_arc(img, l_hip, l_sho, l_kne, metrics['hip_angle'],  back_c)

    # 錯誤警告文字
    warn_y = 40
    if metrics['knee_valgus']:
        put_label(img, "! KNEE VALGUS",  (10, warn_y), C_ERR, 0.7, 2); warn_y += 35
    if metrics['back_slack']:
        put_label(img, "! ROUNDED BACK", (10, warn_y), C_ERR, 0.7, 2); warn_y += 35
    if metrics['heels_off']:
        put_label(img, "! HEELS OFF",    (10, warn_y), C_WARN, 0.7, 2); warn_y += 35
    if metrics['knee_toes']:
        put_label(img, "! KNEES/TOES",   (10, warn_y), C_WARN, 0.7, 2)


# ── 主迴圈 ───────────────────────────────────────────────────────

def run(source):
    cap = cv2.VideoCapture(source)
    if not cap.isOpened():
        print(f"[錯誤] 無法開啟：{source}")
        return

    baseline_torso = None
    baseline_heel_a = None
    prev_phase = "top"
    reps = 0
    prev_knee_angle = 180.0

    with mp_pose.Pose(
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    ) as pose:
        while True:
            ret, frame = cap.read()
            if not ret:
                break

            frame = cv2.flip(frame, 1) if source == 0 else frame
            h, w = frame.shape[:2]

            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            rgb.flags.writeable = False
            results = pose.process(rgb)
            rgb.flags.writeable = True
            img = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)

            if not results.pose_landmarks:
                out = draw_panel(img, {
                    'knee_angle': 0, 'hip_angle': 0, 'back_angle': 0,
                    'torso_ratio': 1, 'heel_delta': 0, 'valgus': 1,
                    'knee_valgus': False, 'back_slack': False,
                    'heels_off': False, 'knee_toes': False,
                }, prev_phase, reps)
                cv2.imshow("GemmaFit – Squat Visualizer", out)
                if cv2.waitKey(5) & 0xFF == ord('q'):
                    break
                continue

            lm = results.pose_landmarks.landmark

            def arr(name):
                l = lm[mp_pose.PoseLandmark[name].value]
                return np.array([l.x, l.y])

            l_sho = arr("LEFT_SHOULDER")
            l_hip = arr("LEFT_HIP")
            l_kne = arr("LEFT_KNEE")
            l_ank = arr("LEFT_ANKLE")
            l_hee = arr("LEFT_HEEL")
            l_toe = arr("LEFT_FOOT_INDEX")
            r_kne = arr("RIGHT_KNEE")
            r_ank = arr("RIGHT_ANKLE")

            # 角度計算
            knee_a  = calculate_joint_angle(l_hip, l_kne, l_ank)
            hip_a   = calculate_joint_angle(l_sho, l_hip, l_kne)
            back_a  = calculate_joint_angle(l_sho, l_hip, l_ank)
            torso   = float(np.linalg.norm(l_sho - l_hip))
            h_ang   = heel_angle(l_hee, l_toe)
            knee_d  = float(np.linalg.norm(l_kne - r_kne))
            ank_d   = float(np.linalg.norm(l_ank - r_ank))
            valgus  = knee_d / (ank_d + 1e-9)

            # 站立基準（膝蓋幾乎打直）
            if knee_a > 167:
                baseline_torso = torso
                baseline_heel_a = h_ang

            torso_ratio = torso / (baseline_torso or torso)
            heel_delta  = h_ang - (baseline_heel_a or h_ang)

            # 深蹲階段
            phase = get_squat_phase(knee_a, prev_phase)

            # Rep 計數：bottom → ascending 轉換時 +1
            if prev_phase == "bottom" and phase == "ascending":
                reps += 1
            prev_phase = phase
            prev_knee_angle = knee_a

            # 錯誤偵測
            metrics = {
                'knee_angle':  knee_a,
                'hip_angle':   hip_a,
                'back_angle':  back_a,
                'torso_ratio': torso_ratio,
                'heel_delta':  heel_delta,
                'valgus':      valgus,
                'knee_valgus': detect_knee_valgus(knee_d, ank_d),
                'back_slack':  detect_back_slack(torso, baseline_torso or torso),
                'heels_off':   detect_heels_off(h_ang, baseline_heel_a or h_ang),
                'knee_toes':   detect_knee_over_toes(l_hip, l_kne, l_toe),
            }

            draw_skeleton(img, lm, w, h, metrics)
            out = draw_panel(img, metrics, phase, reps)

            cv2.imshow("GemmaFit – Squat Visualizer", out)
            if cv2.waitKey(5) & 0xFF == ord('q'):
                break

    cap.release()
    cv2.destroyAllWindows()


def main():
    parser = argparse.ArgumentParser(description="GemmaFit 深蹲可視化")
    parser.add_argument("--video", default=None, help="影片路徑（省略則使用攝影機）")
    args = parser.parse_args()
    run(args.video if args.video else 0)


if __name__ == "__main__":
    main()
