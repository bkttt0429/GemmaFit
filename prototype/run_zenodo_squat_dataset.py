"""
run_zenodo_squat_dataset.py

Run a reproducible smoke benchmark on the Zenodo squat image dataset.

The dataset zip is read in place; images are not fully extracted. The script:
  1. samples Good / Bad Back / Bad Heel images from train and test folders,
  2. extracts MediaPipe Pose landmarks,
  3. computes GemmaFit angle/rule flags,
  4. writes a simple label CSV for threshold validation.

This is image data, not video, so Rule 6 angular velocity is not evaluated from
this dataset. Each image is emitted as a separate source with frame 0, which
keeps temporal velocity at 0 in compute_angles.py.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np

from compute_angles import process_csv


DEFAULT_ZIP = Path("../test_assets/datasets/zenodo_squat_good_bad_back_bad_heel/Dataset.zip")
OUT_DIR = Path("data/validation/zenodo_squat")
LANDMARK_DIR = Path("data/processed/landmarks")
ANGLE_DIR = Path("data/processed/angles")

mp_pose = mp.solutions.pose
LANDMARK_NAMES = [lm.name.lower() for lm in mp_pose.PoseLandmark]
HEADER = ["source", "frame", "label"] + [
    f"{name}_{axis}" for name in LANDMARK_NAMES for axis in ("x", "y", "z", "vis")
]

CLASS_MAP = {
    "good": "good",
    "bad back": "bad_back",
    "bad heel": "bad_heel",
}


def normalized_label(path: str) -> str:
    parts = path.replace("\\", "/").split("/")
    if len(parts) < 3:
        return "unknown"
    return CLASS_MAP.get(parts[1].strip().lower(), "unknown")


def split_name(path: str) -> str:
    return path.replace("\\", "/").split("/", 1)[0].strip().lower()


def select_entries(zip_path: Path, max_per_class: int) -> list[str]:
    buckets: dict[tuple[str, str], list[str]] = defaultdict(list)
    with zipfile.ZipFile(zip_path) as archive:
        for name in archive.namelist():
            if not name.lower().endswith((".jpg", ".jpeg", ".png")):
                continue
            label = normalized_label(name)
            split = split_name(name)
            if label == "unknown" or split not in {"train", "test"}:
                continue
            buckets[(split, label)].append(name)

    selected: list[str] = []
    for key in sorted(buckets):
        selected.extend(sorted(buckets[key])[:max_per_class])
    return selected


def landmarks_to_row(source: str, label: str, landmarks) -> list[object]:
    row: list[object] = [source, 0, label]
    for lm in landmarks.landmark:
        row += [round(lm.x, 6), round(lm.y, 6), round(lm.z, 6), round(lm.visibility, 4)]
    return row


def decode_image(raw: bytes) -> np.ndarray | None:
    array = np.frombuffer(raw, dtype=np.uint8)
    return cv2.imdecode(array, cv2.IMREAD_COLOR)


def write_label_row(writer: csv.DictWriter, source: str, label: str) -> None:
    writer.writerow(
        {
            "source": source,
            "frame": "0",
            "label": label,
            "rule2_expected": int(label == "bad_back"),
            "rule6_expected": 0,
            "heels_off_expected": int(label == "bad_heel"),
        }
    )


def summarize_angles(path: Path) -> dict[str, object]:
    by_label: dict[str, Counter] = defaultdict(Counter)
    total = Counter()
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            label = row.get("label", "unknown")
            total["frames"] += 1
            by_label[label]["frames"] += 1
            for flag in ("back_slack", "heels_off", "knee_valgus", "rapid_movement"):
                active = str(row.get(flag, "0")).strip() in {"1", "true", "True"}
                if active:
                    total[flag] += 1
                    by_label[label][flag] += 1

    return {
        "total": dict(total),
        "by_label": {label: dict(counter) for label, counter in sorted(by_label.items())},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run Zenodo squat image dataset through GemmaFit formulas.")
    parser.add_argument("--zip", default=str(DEFAULT_ZIP), help="Path to Dataset.zip.")
    parser.add_argument("--max-per-class", type=int, default=60, help="Sample count per split/class.")
    parser.add_argument("--output-prefix", default="zenodo_squat_sample")
    args = parser.parse_args()

    zip_path = Path(args.zip)
    if not zip_path.exists():
        print(f"[ERROR] Dataset zip not found: {zip_path}")
        return 1

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    LANDMARK_DIR.mkdir(parents=True, exist_ok=True)
    ANGLE_DIR.mkdir(parents=True, exist_ok=True)

    entries = select_entries(zip_path, args.max_per_class)
    if not entries:
        print("[ERROR] No image entries selected from zip")
        return 1

    landmarks_csv = LANDMARK_DIR / f"{args.output_prefix}_landmarks.csv"
    angles_csv = ANGLE_DIR / f"{args.output_prefix}_angles.csv"
    labels_csv = OUT_DIR / f"{args.output_prefix}_labels.csv"
    report_json = OUT_DIR / f"{args.output_prefix}_report.json"

    detected = 0
    missed = 0
    counts = Counter()

    with zipfile.ZipFile(zip_path) as archive, \
            landmarks_csv.open("w", encoding="utf-8", newline="") as landmark_handle, \
            labels_csv.open("w", encoding="utf-8", newline="") as label_handle:
        landmark_writer = csv.writer(landmark_handle)
        landmark_writer.writerow(HEADER)

        label_writer = csv.DictWriter(
            label_handle,
            fieldnames=["source", "frame", "label", "rule2_expected", "rule6_expected", "heels_off_expected"],
        )
        label_writer.writeheader()

        with mp_pose.Pose(
            static_image_mode=True,
            model_complexity=2,
            min_detection_confidence=0.5,
        ) as pose:
            for index, entry in enumerate(entries, start=1):
                label = normalized_label(entry)
                image = decode_image(archive.read(entry))
                if image is None:
                    missed += 1
                    continue

                rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
                rgb.flags.writeable = False
                result = pose.process(rgb)
                if result.pose_landmarks is None:
                    missed += 1
                    continue

                detected += 1
                counts[(split_name(entry), label)] += 1
                landmark_writer.writerow(landmarks_to_row(entry, label, result.pose_landmarks))
                write_label_row(label_writer, entry, label)

                if index % 50 == 0:
                    print(f"processed {index}/{len(entries)} entries, detected={detected}, missed={missed}")

    process_csv(str(landmarks_csv), str(angles_csv), fps=0.0)
    angle_summary = summarize_angles(angles_csv)

    report = {
        "dataset_zip": str(zip_path),
        "max_per_class": args.max_per_class,
        "selected_entries": len(entries),
        "landmark_frames": detected,
        "missed_images": missed,
        "class_counts": {f"{split}/{label}": count for (split, label), count in sorted(counts.items())},
        "landmarks_csv": str(landmarks_csv),
        "angles_csv": str(angles_csv),
        "labels_csv": str(labels_csv),
        "angle_summary": angle_summary,
        "notes": [
            "Zenodo squat dataset is image-based; Rule 6 velocity is not evaluated here.",
            "Bad Back maps to rule2_expected for prototype threshold calibration.",
            "Bad Heel is recorded as heels_off_expected, not full COM/BoS validation.",
        ],
    }
    report_json.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"Wrote {landmarks_csv}")
    print(f"Wrote {angles_csv}")
    print(f"Wrote {labels_csv}")
    print(f"Wrote {report_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
