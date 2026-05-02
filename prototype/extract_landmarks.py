"""
extract_landmarks.py

從各公開資料集擷取 MediaPipe 格式的 33 個 landmarks，
統一輸出為 CSV 存放到 data/processed/landmarks/。

支援三種輸入：
  1. 影像資料夾（Zenodo Squat Dataset 等）
  2. 影片檔案
  3. 已是 landmarks CSV（Kaggle Time Series）直接轉換格式

用法：
  python extract_landmarks.py --mode images  --input data/raw/squat_zenodo    --label good
  python extract_landmarks.py --mode images  --input data/raw/squat_zenodo    --label bad_back
  python extract_landmarks.py --mode images  --input data/raw/squat_zenodo    --label bad_heel
  python extract_landmarks.py --mode video   --input data/raw/my_video.mp4    --label good
  python extract_landmarks.py --mode csv     --input data/raw/exercise_timeseries/data.csv
"""

import argparse
import csv
import os
import sys

import cv2
import mediapipe as mp
import numpy as np

OUTPUT_DIR = os.path.join("data", "processed", "landmarks")
os.makedirs(OUTPUT_DIR, exist_ok=True)

mp_pose = mp.solutions.pose

LANDMARK_NAMES = [lm.name.lower() for lm in mp_pose.PoseLandmark]

HEADER = ["source", "frame", "label"] + [
    f"{name}_{axis}" for name in LANDMARK_NAMES for axis in ("x", "y", "z", "vis")
]


def landmarks_to_row(source: str, frame_idx: int, label: str, landmarks) -> list:
    """把 MediaPipe landmark 物件展平成一列 CSV 數值。"""
    row = [source, frame_idx, label]
    for lm in landmarks.landmark:
        row += [round(lm.x, 6), round(lm.y, 6), round(lm.z, 6), round(lm.visibility, 4)]
    return row


def process_image(image_path: str, pose) -> list | None:
    """讀取單張影像，回傳 MediaPipe landmarks 或 None（偵測失敗）。"""
    image = cv2.imread(image_path)
    if image is None:
        print(f"  [skip] 無法讀取：{image_path}")
        return None
    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    image_rgb.flags.writeable = False
    result = pose.process(image_rgb)
    return result.pose_landmarks


def extract_from_images(input_dir: str, label: str, output_csv: str):
    """從影像資料夾逐張擷取 landmarks（適用 Zenodo 類靜態圖資料集）。"""
    image_exts = {".jpg", ".jpeg", ".png", ".bmp"}
    paths = [
        os.path.join(input_dir, f)
        for f in sorted(os.listdir(input_dir))
        if os.path.splitext(f)[1].lower() in image_exts
    ]
    if not paths:
        print(f"[錯誤] 在 {input_dir} 找不到影像檔。")
        sys.exit(1)

    print(f"共 {len(paths)} 張影像，label={label}")

    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)

        with mp_pose.Pose(
            static_image_mode=True,
            model_complexity=2,
            min_detection_confidence=0.5,
        ) as pose:
            for idx, path in enumerate(paths):
                landmarks = process_image(path, pose)
                if landmarks is None:
                    continue
                source = os.path.basename(path)
                writer.writerow(landmarks_to_row(source, idx, label, landmarks))
                if (idx + 1) % 50 == 0:
                    print(f"  已處理 {idx + 1}/{len(paths)}")

    print(f"[完成] 輸出：{output_csv}")


def extract_from_video(video_path: str, label: str, output_csv: str, sample_every: int = 3):
    """從影片每 N 幀擷取一次 landmarks（適用影片格式資料）。"""
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        print(f"[錯誤] 無法開啟影片：{video_path}")
        sys.exit(1)

    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    print(f"影片總幀數：{total}，每 {sample_every} 幀取樣一次，label={label}")

    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)

        with mp_pose.Pose(
            min_detection_confidence=0.5,
            min_tracking_confidence=0.5,
        ) as pose:
            frame_idx = 0
            saved = 0
            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    break
                if frame_idx % sample_every == 0:
                    image_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                    image_rgb.flags.writeable = False
                    result = pose.process(image_rgb)
                    if result.pose_landmarks:
                        source = os.path.basename(video_path)
                        writer.writerow(
                            landmarks_to_row(source, frame_idx, label, result.pose_landmarks)
                        )
                        saved += 1
                frame_idx += 1

    cap.release()
    print(f"[完成] 已儲存 {saved} 幀 → {output_csv}")


def extract_from_csv(input_csv: str, output_csv: str):
    """
    Kaggle Time Series 資料集已有 33 landmarks，
    只做欄位對齊與格式統一後直接複製。
    假設輸入 CSV 有欄位：label, x1..x33, y1..y33, z1..z33
    實際欄位名依資料集調整。
    """
    print(f"讀取 CSV：{input_csv}")
    rows_out = []

    with open(input_csv, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        print(f"  原始欄位（前 10）：{fieldnames[:10]}")

        for i, row in enumerate(reader):
            label = row.get("label", row.get("class", "unknown"))
            # 嘗試直接轉存，未對齊欄位的部分填 0
            out_row = [input_csv, i, label]
            for name in LANDMARK_NAMES:
                for axis in ("x", "y", "z", "vis"):
                    key = f"{name}_{axis}"
                    out_row.append(row.get(key, 0.0))
            rows_out.append(out_row)

    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)
        writer.writerows(rows_out)

    print(f"[完成] 共 {len(rows_out)} 列 → {output_csv}")
    print("  注意：若欄位名稱不符，請手動調整此腳本中的 key 對應邏輯。")


def make_output_path(input_path: str, label: str) -> str:
    """根據輸入路徑與 label 自動產生輸出檔名。"""
    base = os.path.splitext(os.path.basename(input_path))[0]
    name = f"{base}_{label}.csv" if label else f"{base}.csv"
    return os.path.join(OUTPUT_DIR, name)


def main():
    parser = argparse.ArgumentParser(description="GemmaFit Landmark Extractor")
    parser.add_argument(
        "--mode",
        choices=["images", "video", "csv"],
        required=True,
        help="輸入類型：images（影像資料夾）/ video（影片檔）/ csv（已有 landmarks 的 CSV）",
    )
    parser.add_argument("--input", required=True, help="輸入路徑（資料夾或檔案）")
    parser.add_argument(
        "--label",
        default="",
        help="姿態標籤，例如：good / bad_back / bad_heel（images/video 模式必填）",
    )
    parser.add_argument(
        "--output",
        default="",
        help="輸出 CSV 路徑（預設自動產生到 data/processed/landmarks/）",
    )
    parser.add_argument(
        "--sample_every",
        type=int,
        default=3,
        help="video 模式：每幾幀取樣一次（預設 3）",
    )
    args = parser.parse_args()

    output_csv = args.output or make_output_path(args.input, args.label)

    if args.mode == "images":
        if not args.label:
            print("[錯誤] images 模式需要指定 --label")
            sys.exit(1)
        extract_from_images(args.input, args.label, output_csv)

    elif args.mode == "video":
        if not args.label:
            print("[錯誤] video 模式需要指定 --label")
            sys.exit(1)
        extract_from_video(args.input, args.label, output_csv, args.sample_every)

    elif args.mode == "csv":
        extract_from_csv(args.input, output_csv)


if __name__ == "__main__":
    main()
