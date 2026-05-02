"""
Render formula validation overlays for downloaded test videos.

Outputs PNG contact sheets and individual annotated frames to:
  test_assets/outputs/visualizations/
"""

from __future__ import annotations

import csv
from pathlib import Path
from types import SimpleNamespace

import cv2
import numpy as np

from com_tracker_prototype import track_com


ROOT = Path.cwd().parent if Path.cwd().name == "prototype" else Path.cwd()
OUT = ROOT / "test_assets" / "outputs" / "visualizations"
OUT.mkdir(parents=True, exist_ok=True)

LANDMARK_NAMES = [
    "nose", "left_eye_inner", "left_eye", "left_eye_outer",
    "right_eye_inner", "right_eye", "right_eye_outer",
    "left_ear", "right_ear", "mouth_left", "mouth_right",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_pinky", "right_pinky",
    "left_index", "right_index", "left_thumb", "right_thumb",
    "left_hip", "right_hip", "left_knee", "right_knee",
    "left_ankle", "right_ankle", "left_heel", "right_heel",
    "left_foot_index", "right_foot_index",
]

BONES = [
    ("left_shoulder", "right_shoulder"), ("left_hip", "right_hip"),
    ("left_shoulder", "left_hip"), ("right_shoulder", "right_hip"),
    ("left_shoulder", "left_elbow"), ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"), ("right_elbow", "right_wrist"),
    ("left_hip", "left_knee"), ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"), ("right_knee", "right_ankle"),
    ("left_ankle", "left_heel"), ("left_heel", "left_foot_index"),
    ("right_ankle", "right_heel"), ("right_heel", "right_foot_index"),
]

CASES = [
    {
        "name": "squat_wikimedia",
        "video": ROOT / "test_assets" / "videos" / "squat_wikimedia_01.webm",
        "landmarks": ROOT / "prototype" / "data" / "processed" / "landmarks" / "squat_wikimedia_01_squat_wikimedia.csv",
        "angles": ROOT / "prototype" / "data" / "processed" / "angles" / "squat_wikimedia_01_squat_wikimedia_angles.csv",
    },
    {
        "name": "pushup_cdc",
        "video": ROOT / "test_assets" / "videos" / "pushup_cdc_01.webm",
        "landmarks": ROOT / "prototype" / "data" / "processed" / "landmarks" / "pushup_cdc_01_pushup_cdc.csv",
        "angles": ROOT / "prototype" / "data" / "processed" / "angles" / "pushup_cdc_01_pushup_cdc_angles.csv",
    },
]


def write_png(path: Path, image) -> None:
    ok, encoded = cv2.imencode(".png", image)
    if not ok:
        raise RuntimeError(f"Failed to encode PNG: {path}")
    encoded.tofile(str(path))


def read_rows(path: Path) -> list[dict[str, str]]:
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def as_float(value: str | None, default: float = 0.0) -> float:
    try:
        return float(value) if value is not None else default
    except ValueError:
        return default


def point(row: dict[str, str], name: str, width: int, height: int) -> tuple[int, int]:
    return (
        int(as_float(row[f"{name}_x"]) * width),
        int(as_float(row[f"{name}_y"]) * height),
    )


def landmarks_for_com(row: dict[str, str]) -> list[SimpleNamespace]:
    return [
        SimpleNamespace(
            x=as_float(row.get(f"{name}_x")),
            y=as_float(row.get(f"{name}_y")),
            z=as_float(row.get(f"{name}_z")),
        )
        for name in LANDMARK_NAMES
    ]


def put_label(
    image,
    text: str,
    x: int,
    y: int,
    color: tuple[int, int, int] = (255, 255, 255),
    scale: float = 0.55,
    thickness: int = 1,
) -> None:
    (text_w, text_h), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, thickness)
    cv2.rectangle(image, (x - 4, y - text_h - 6), (x + text_w + 4, y + 5), (0, 0, 0), -1)
    cv2.putText(image, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)


def select_frames(frames: list[int], angle_by_frame: dict[int, dict[str, str]]) -> list[int]:
    knee_sorted = sorted(frames, key=lambda frame: as_float(angle_by_frame[frame].get("knee_angle")))
    selected = [
        frames[0],
        frames[len(frames) // 3],
        knee_sorted[0],
        frames[(2 * len(frames)) // 3],
        frames[-1],
    ]
    return list(dict.fromkeys(selected))[:5]


def draw_case(case: dict) -> Path:
    landmark_rows = read_rows(case["landmarks"])
    angle_rows = read_rows(case["angles"])
    landmark_by_frame = {int(float(row["frame"])): row for row in landmark_rows}
    angle_by_frame = {int(float(row["frame"])): row for row in angle_rows}
    frames = sorted(set(landmark_by_frame) & set(angle_by_frame))
    if not frames:
        raise RuntimeError(f"No overlapping frames for {case['name']}")

    cap = cv2.VideoCapture(str(case["video"]))
    tiles = []

    for frame_idx in select_frames(frames, angle_by_frame):
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)
        ok, image = cap.read()
        if not ok:
            continue

        height, width = image.shape[:2]
        landmark_row = landmark_by_frame[frame_idx]
        angle_row = angle_by_frame[frame_idx]
        knee_flag = int(float(angle_row.get("knee_valgus", "0"))) == 1
        rapid_flag = int(float(angle_row.get("rapid_movement", "0"))) == 1
        com_result = track_com(landmarks_for_com(landmark_row), contact="bipedal")

        if com_result.support_polygon:
            overlay = image.copy()
            polygon = np.array(
                [(int(p.x * width), int(p.y * height)) for p in com_result.support_polygon],
                dtype=np.int32,
            )
            cv2.fillPoly(overlay, [polygon], color=(0, 100, 100))
            image = cv2.addWeighted(overlay, 0.2, image, 0.8, 0)
            cv2.polylines(image, [polygon], isClosed=True, color=(0, 215, 255), thickness=2, lineType=cv2.LINE_AA)

        for a, b in BONES:
            color = (60, 220, 60)
            if "knee" in a or "knee" in b:
                color = (0, 0, 230) if knee_flag else (60, 220, 60)
            cv2.line(image, point(landmark_row, a, width, height), point(landmark_row, b, width, height), color, 4, cv2.LINE_AA)

        joints = [
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_hip", "right_hip",
            "left_knee", "right_knee", "left_ankle", "right_ankle",
            "left_heel", "right_heel", "left_foot_index", "right_foot_index",
        ]
        for name in joints:
            cv2.circle(image, point(landmark_row, name, width, height), 5, (255, 255, 255), -1, cv2.LINE_AA)

        com_x, com_y = int(com_result.com.x * width), int(com_result.com.y * height)
        cv2.circle(image, (com_x, com_y), 9, (255, 0, 255), -1, cv2.LINE_AA)
        cv2.circle(image, (com_x, com_y), 13, (255, 255, 255), 2, cv2.LINE_AA)

        com_color = (60, 220, 60) if com_result.inside else (0, 0, 230)
        knee_color = (0, 0, 230) if knee_flag else (255, 255, 255)
        velocity_color = (0, 0, 230) if rapid_flag else (255, 255, 255)

        put_label(image, f"{case['name']} frame {frame_idx}", 12, 28, scale=0.65, thickness=2)
        put_label(image, f"knee {as_float(angle_row.get('knee_angle')):.1f}  hip {as_float(angle_row.get('hip_angle')):.1f}  back {as_float(angle_row.get('back_angle')):.1f}", 12, 58)
        put_label(image, f"valgus ratio {as_float(angle_row.get('valgus_ratio')):.2f}  max FPPA {as_float(angle_row.get('max_fppa')):.1f}", 12, 86, knee_color)
        put_label(image, f"vel {as_float(angle_row.get('max_angular_velocity_dps')):.1f} deg/s  rapid {'YES' if rapid_flag else 'no'}", 12, 114, velocity_color)
        put_label(image, f"COM inside {'YES' if com_result.inside else 'NO'}  offset {com_result.offset_ratio:.2f}", 12, 142, com_color)
        put_label(image, "yellow=BoS  magenta=COM  red=knee flag", 12, height - 18, scale=0.5)

        frame_path = OUT / f"{case['name']}_frame_{frame_idx}.png"
        write_png(frame_path, image)
        tiles.append(cv2.resize(image, (480, 270), interpolation=cv2.INTER_AREA))

    cap.release()

    if not tiles:
        raise RuntimeError(f"No frames rendered for {case['name']}")

    while len(tiles) < 6:
        tiles.append(np.zeros_like(tiles[0]))

    sheet = np.vstack([np.hstack(tiles[:3]), np.hstack(tiles[3:6])])
    sheet_path = OUT / f"{case['name']}_contact_sheet.png"
    write_png(sheet_path, sheet)
    return sheet_path


def main() -> None:
    for case in CASES:
        sheet_path = draw_case(case)
        print(sheet_path)


if __name__ == "__main__":
    main()
