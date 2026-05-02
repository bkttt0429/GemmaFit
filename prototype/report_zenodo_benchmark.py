"""
Generate the Phase 1 Zenodo squat benchmark report.

The Zenodo squat asset is an image dataset, so this report only evaluates
static-image rules that map to its labels:
  - Rule 2 Bad Back via the back_slack heuristic
  - Bad Heel via the heels_off heuristic

It explicitly excludes Rule 6 and frontal-plane knee-valgus/FPPA claims.
"""

from __future__ import annotations

import argparse
import csv
import json
from dataclasses import asdict, dataclass
from pathlib import Path


DEFAULT_ANGLES = Path("data") / "processed" / "angles" / "zenodo_squat_full_angles.csv"
DEFAULT_LABELS = Path("data") / "validation" / "zenodo_squat" / "zenodo_squat_full_labels.csv"
DEFAULT_RUN_REPORT = Path("data") / "validation" / "zenodo_squat" / "zenodo_squat_full_report.json"
DEFAULT_OUTPUT_MD = Path("..") / "docs" / "benchmark" / "zenodo_squat_phase1_benchmark.md"
DEFAULT_OUTPUT_JSON = Path("..") / "docs" / "benchmark" / "zenodo_squat_phase1_benchmark.json"


@dataclass
class Confusion:
    true_positive: int = 0
    false_positive: int = 0
    false_negative: int = 0
    true_negative: int = 0

    @property
    def precision(self) -> float | None:
        denom = self.true_positive + self.false_positive
        return self.true_positive / denom if denom else None

    @property
    def recall(self) -> float | None:
        denom = self.true_positive + self.false_negative
        return self.true_positive / denom if denom else None

    @property
    def f1(self) -> float | None:
        precision = self.precision
        recall = self.recall
        if precision is None or recall is None or precision + recall == 0:
            return None
        return 2 * precision * recall / (precision + recall)


@dataclass
class MetricResult:
    key: str
    label: str
    prediction_column: str
    expected_column: str
    evidence: str
    frames: int
    predicted_positive: int
    expected_positive: int
    confusion: Confusion
    interpretation: str


def parse_bool(value: str | None) -> bool:
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "y", "bad", "active"}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def load_report(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def metric_for(
    rows: list[dict[str, str]],
    labels: dict[tuple[str, str], dict[str, str]],
    key: str,
    label: str,
    prediction_column: str,
    expected_column: str,
    interpretation: str,
) -> MetricResult:
    confusion = Confusion()
    frames = predicted_positive = expected_positive = 0

    for row in rows:
        label_row = labels.get((row.get("source", ""), row.get("frame", "")))
        if label_row is None or expected_column not in label_row:
            continue

        frames += 1
        pred = parse_bool(row.get(prediction_column))
        expected = parse_bool(label_row.get(expected_column))
        predicted_positive += int(pred)
        expected_positive += int(expected)

        if pred and expected:
            confusion.true_positive += 1
        elif pred and not expected:
            confusion.false_positive += 1
        elif not pred and expected:
            confusion.false_negative += 1
        else:
            confusion.true_negative += 1

    return MetricResult(
        key=key,
        label=label,
        prediction_column=prediction_column,
        expected_column=expected_column,
        evidence="prototype_threshold",
        frames=frames,
        predicted_positive=predicted_positive,
        expected_positive=expected_positive,
        confusion=confusion,
        interpretation=interpretation,
    )


def fmt(value: float | None, digits: int = 3) -> str:
    return "-" if value is None else f"{value:.{digits}f}"


def write_markdown(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)

    run = payload["run"]
    metrics: list[MetricResult] = payload["metrics"]
    class_counts = run.get("class_counts", {})
    notes = payload["notes"]

    lines = [
        "# Zenodo Squat Phase 1 Benchmark",
        "",
        "This benchmark is generated from the completed Zenodo squat image run.",
        "It is public-dataset evidence for static posture heuristics only.",
        "",
        "## Dataset Run",
        "",
        f"- Selected images: {run.get('selected_entries')}",
        f"- MediaPipe landmark frames: {run.get('landmark_frames')}",
        f"- Missed images: {run.get('missed_images')}",
        f"- Angles CSV: `{payload['inputs']['angles']}`",
        f"- Labels CSV: `{payload['inputs']['labels']}`",
        "",
        "## Class Counts",
        "",
        "| Split/Class | Images |",
        "| --- | ---: |",
    ]
    for key in sorted(class_counts):
        lines.append(f"| {key} | {class_counts[key]} |")

    lines.extend([
        "",
        "## Applicable Metrics",
        "",
        "| Item | Prediction | Expected label | Frames | Pred + | Expected + | TP | FP | FN | TN | Precision | Recall | F1 | Evidence |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ])

    for item in metrics:
        c = item.confusion
        lines.append(
            "| "
            f"{item.label} | {item.prediction_column} | {item.expected_column} | "
            f"{item.frames} | {item.predicted_positive} | {item.expected_positive} | "
            f"{c.true_positive} | {c.false_positive} | {c.false_negative} | {c.true_negative} | "
            f"{fmt(c.precision)} | {fmt(c.recall)} | {fmt(c.f1)} | {item.evidence} |"
        )

    lines.extend([
        "",
        "## Interpretation",
        "",
    ])
    for item in metrics:
        lines.append(f"- {item.label}: {item.interpretation}")

    lines.extend([
        "",
        "## Explicit Exclusions",
        "",
    ])
    for note in notes:
        lines.append(f"- {note}")

    lines.extend([
        "",
        "## Report Language",
        "",
        "- `prototype_threshold` means implemented and reportable, not clinically validated.",
        "- This report should be cited as a Phase 1 benchmark, not as final threshold validation.",
        "",
    ])

    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate Zenodo Phase 1 benchmark report.")
    parser.add_argument("--angles", default=str(DEFAULT_ANGLES))
    parser.add_argument("--labels", default=str(DEFAULT_LABELS))
    parser.add_argument("--run-report", default=str(DEFAULT_RUN_REPORT))
    parser.add_argument("--output-md", default=str(DEFAULT_OUTPUT_MD))
    parser.add_argument("--output-json", default=str(DEFAULT_OUTPUT_JSON))
    args = parser.parse_args()

    angles_path = Path(args.angles)
    labels_path = Path(args.labels)
    run_report_path = Path(args.run_report)

    rows = read_csv(angles_path)
    label_rows = read_csv(labels_path)
    labels = {(row.get("source", ""), row.get("frame", "")): row for row in label_rows}
    run_report = load_report(run_report_path)

    metrics = [
        metric_for(
            rows,
            labels,
            key="rule2_bad_back",
            label="Rule 2 Bad Back",
            prediction_column="back_slack",
            expected_column="rule2_expected",
            interpretation=(
                "precision is high but recall is low on the full image set, "
                "so the current back_slack rule is conservative."
            ),
        ),
        metric_for(
            rows,
            labels,
            key="bad_heel_proxy",
            label="Bad Heel proxy",
            prediction_column="heels_off",
            expected_column="heels_off_expected",
            interpretation=(
                "heel lift has a stronger signal for Bad Heel and is suitable "
                "as the first demo benchmark, while still remaining a prototype heuristic."
            ),
        ),
    ]

    notes = [
        "Rule 6 rapid movement is excluded because the Zenodo asset is image-only and has no time sequence.",
        "Rule 1 FPPA/knee-ratio is excluded because the dataset is side-view squat imagery, not a frontal knee-valgus benchmark.",
        "Rule 5 COM-vs-BoS is not fully validated here; Bad Heel is reported only as a heel-lift proxy.",
    ]

    payload = {
        "inputs": {
            "angles": str(angles_path),
            "labels": str(labels_path),
            "run_report": str(run_report_path),
        },
        "run": run_report,
        "metrics": metrics,
        "notes": notes,
    }

    output_json = Path(args.output_json)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(
        json.dumps(
            {
                "inputs": payload["inputs"],
                "run": run_report,
                "metrics": [asdict(item) for item in metrics],
                "notes": notes,
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    write_markdown(Path(args.output_md), payload)

    print(f"Rows: {len(rows)}")
    for item in metrics:
        c = item.confusion
        print(
            f"{item.label}: precision={fmt(c.precision)}, "
            f"recall={fmt(c.recall)}, f1={fmt(c.f1)}"
        )
    print(f"Wrote {output_json}")
    print(f"Wrote {args.output_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
