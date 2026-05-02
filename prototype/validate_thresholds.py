"""
validate_thresholds.py

Summarize GemmaFit prototype safety-rule thresholds from angle CSV files.

Default mode works without labeled datasets and reports how often each prototype
threshold fires. When a label CSV is provided, the script also computes
precision/recall/F1 for matching rule columns.

Examples:
  python validate_thresholds.py
  python validate_thresholds.py --angles data/processed/angles/*squat*.csv
  python validate_thresholds.py --labels data/validation/threshold_labels.csv

Expected optional label CSV columns:
  source, frame, rule1_expected, rule2_expected, rule5_expected, rule6_expected

The label column names are intentionally simple so the same file can be created
from public datasets or hand-labeled demo clips.
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable


DEFAULT_ANGLE_GLOB = "data/processed/angles/*.csv"
DEFAULT_OUTPUT_JSON = "data/validation/results/threshold_validation_summary.json"
DEFAULT_OUTPUT_MD = "data/validation/results/threshold_validation_summary.md"


@dataclass(frozen=True)
class RuleSpec:
    key: str
    rule: int
    name: str
    flag_column: str
    metric_column: str
    threshold: float
    comparison: str
    evidence: str = "prototype_threshold"


RULE_SPECS = [
    RuleSpec(
        key="rule1_ratio",
        rule=1,
        name="Knee lateral deviation ratio",
        flag_column="knee_valgus_ratio",
        metric_column="valgus_ratio",
        threshold=0.75,
        comparison="<",
    ),
    RuleSpec(
        key="rule1_fppa",
        rule=1,
        name="Knee FPPA",
        flag_column="knee_valgus_fppa",
        metric_column="max_fppa",
        threshold=10.0,
        comparison=">",
    ),
    RuleSpec(
        key="rule2_back_slack",
        rule=2,
        name="Back slack heuristic",
        flag_column="back_slack",
        metric_column="back_angle",
        threshold=1.0,
        comparison="flag",
    ),
    RuleSpec(
        key="heel_lift",
        rule=5,
        name="Heel lift heuristic",
        flag_column="heels_off",
        metric_column="heel_angle_delta",
        threshold=3.0,
        comparison=">",
    ),
    RuleSpec(
        key="rule6_rapid_movement",
        rule=6,
        name="Rapid movement angular velocity",
        flag_column="rapid_movement",
        metric_column="max_angular_velocity_dps",
        threshold=600.0,
        comparison=">",
    ),
]


@dataclass
class RuleSummary:
    key: str
    rule: int
    name: str
    threshold: float
    comparison: str
    evidence: str
    frames: int = 0
    triggered: int = 0
    trigger_rate: float = 0.0
    min_value: float | None = None
    max_value: float | None = None
    mean_value: float | None = None
    labeled_frames: int = 0
    true_positive: int = 0
    false_positive: int = 0
    false_negative: int = 0
    precision: float | None = None
    recall: float | None = None
    f1: float | None = None


def parse_bool(value: str | None) -> bool:
    if value is None:
        return False
    text = str(value).strip().lower()
    return text in {"1", "true", "yes", "y", "active", "bad", "fail"}


def parse_float(value: str | None) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except ValueError:
        return None


def read_rows(paths: Iterable[Path]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for path in paths:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                row["_angle_csv"] = str(path)
                rows.append(row)
    return rows


def load_labels(path: Path | None) -> dict[tuple[str, str], dict[str, str]]:
    if path is None:
        return {}
    labels: dict[tuple[str, str], dict[str, str]] = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            source = row.get("source", "")
            frame = row.get("frame", "")
            labels[(source, frame)] = row
    return labels


def expected_column_names(spec: RuleSpec) -> list[str]:
    return [
        f"rule{spec.rule}_expected",
        f"rule_{spec.rule}_expected",
        f"{spec.key}_expected",
        f"{spec.flag_column}_expected",
        f"expected_{spec.key}",
    ]


def expected_for(row: dict[str, str], spec: RuleSpec) -> bool | None:
    for name in expected_column_names(spec):
        if name in row:
            return parse_bool(row.get(name))
    return None


def summarize_rule(
    rows: list[dict[str, str]],
    labels: dict[tuple[str, str], dict[str, str]],
    spec: RuleSpec,
) -> RuleSummary:
    values: list[float] = []
    triggered = 0
    labeled = 0
    tp = fp = fn = 0

    for row in rows:
        pred = parse_bool(row.get(spec.flag_column))
        if pred:
            triggered += 1

        metric = parse_float(row.get(spec.metric_column))
        if metric is not None:
            values.append(metric)

        label_row = labels.get((row.get("source", ""), row.get("frame", "")))
        if label_row:
            expected = expected_for(label_row, spec)
            if expected is not None:
                labeled += 1
                if pred and expected:
                    tp += 1
                elif pred and not expected:
                    fp += 1
                elif not pred and expected:
                    fn += 1

    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall > 0
        else None
    )

    return RuleSummary(
        key=spec.key,
        rule=spec.rule,
        name=spec.name,
        threshold=spec.threshold,
        comparison=spec.comparison,
        evidence=spec.evidence,
        frames=len(rows),
        triggered=triggered,
        trigger_rate=triggered / len(rows) if rows else 0.0,
        min_value=min(values) if values else None,
        max_value=max(values) if values else None,
        mean_value=sum(values) / len(values) if values else None,
        labeled_frames=labeled,
        true_positive=tp,
        false_positive=fp,
        false_negative=fn,
        precision=precision,
        recall=recall,
        f1=f1,
    )


def fmt(value: float | None, digits: int = 3) -> str:
    if value is None:
        return "-"
    return f"{value:.{digits}f}"


def write_markdown(path: Path, angle_paths: list[Path], summaries: list[RuleSummary]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# GemmaFit Threshold Validation Summary",
        "",
        "This report is generated from prototype angle CSV files. Rows without a label CSV are descriptive only.",
        "",
        "## Inputs",
        "",
    ]
    if angle_paths:
        lines.extend(f"- `{p.as_posix()}`" for p in angle_paths)
    else:
        lines.append("- No angle CSV files found")

    lines.extend([
        "",
        "## Rule Summary",
        "",
        "| Rule | Metric | Threshold | Frames | Triggered | Rate | Min | Mean | Max | Labeled | Precision | Recall | F1 |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ])

    for item in summaries:
        lines.append(
            "| "
            f"{item.rule} | {item.name} | {item.comparison} {item.threshold:g} | "
            f"{item.frames} | {item.triggered} | {fmt(item.trigger_rate)} | "
            f"{fmt(item.min_value)} | {fmt(item.mean_value)} | {fmt(item.max_value)} | "
            f"{item.labeled_frames} | {fmt(item.precision)} | {fmt(item.recall)} | {fmt(item.f1)} |"
        )

    lines.extend([
        "",
        "## Notes",
        "",
        "- `prototype_threshold` means the metric is implemented but still needs dataset calibration.",
        "- Rule 6 is stored in degrees per second, not degrees per frame.",
        "- Rule 1 currently tracks both knee/ankle ratio and FPPA so later calibration can compare them directly.",
        "",
    ])
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize GemmaFit safety threshold behavior.")
    parser.add_argument("--angles", default=DEFAULT_ANGLE_GLOB, help="Glob for angle CSV files.")
    parser.add_argument("--labels", default=None, help="Optional labeled CSV for precision/recall.")
    parser.add_argument("--output-json", default=DEFAULT_OUTPUT_JSON)
    parser.add_argument("--output-md", default=DEFAULT_OUTPUT_MD)
    args = parser.parse_args()

    angle_paths = [Path(p) for p in sorted(glob.glob(args.angles))]
    rows = read_rows(angle_paths)
    labels = load_labels(Path(args.labels)) if args.labels else {}
    summaries = [summarize_rule(rows, labels, spec) for spec in RULE_SPECS]

    output_json = Path(args.output_json)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(
        json.dumps(
            {
                "angle_csvs": [p.as_posix() for p in angle_paths],
                "rows": len(rows),
                "labels": args.labels,
                "rules": [asdict(item) for item in summaries],
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    write_markdown(Path(args.output_md), angle_paths, summaries)

    print(f"Angle CSV files: {len(angle_paths)}")
    print(f"Frames: {len(rows)}")
    for item in summaries:
        print(
            f"Rule {item.rule} {item.key}: "
            f"{item.triggered}/{item.frames} triggered "
            f"({item.trigger_rate:.1%})"
        )
    print(f"Wrote {output_json}")
    print(f"Wrote {args.output_md}")
    return 0 if angle_paths else 1


if __name__ == "__main__":
    raise SystemExit(main())
