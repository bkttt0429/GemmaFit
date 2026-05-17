"""
JSONL recorder for Layer 2 sequence-labeling data.

The recorder stores derived feature windows plus FSM weak labels. It never
stores raw video, image crops, histograms, embeddings, or raw landmarks.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

from layer2_fsm import Layer2Features, Layer2Output


SCHEMA_VERSION = "layer2_sequence_v1"
FORBIDDEN_ROW_KEYS = {
    "raw_video",
    "raw_frames",
    "rgb_frames",
    "image_crops",
    "histograms",
    "embeddings",
    "raw_landmarks",
}


@dataclass
class Layer2RecordedSequence:
    sample_id: str
    activity: str
    features: List[Layer2Features]
    outputs: List[Layer2Output]
    source: Dict[str, Any] = field(default_factory=dict)
    split_hint: str = "train"
    label_source: str = "fsm_weak_label"

    def to_row(self) -> Dict[str, Any]:
        if len(self.features) != len(self.outputs):
            raise ValueError("features and outputs must have the same length")

        phases = [o.phase for o in self.outputs]
        events = [
            {
                "timestamp_ms": o.timestamp_ms,
                "event": o.event,
                "phase": o.phase,
                "evidence_refs": o.evidence_refs,
            }
            for o in self.outputs
            if o.event != "NONE"
        ]
        abstain_reasons = sorted(
            {o.abstain_reason for o in self.outputs if o.abstain_reason}
        )
        final_output = self.outputs[-1] if self.outputs else None

        row = {
            "schema_version": SCHEMA_VERSION,
            "sample_id": self.sample_id,
            "activity": self.activity,
            "split_hint": self.split_hint,
            "source": dict(self.source),
            "feature_sequence": [f.to_dict() for f in self.features],
            "labels": {
                "label_source": self.label_source,
                "activity_hypothesis": (
                    final_output.activity_hypothesis if final_output else self.activity
                ),
                "phase_sequence": phases,
                "events": events,
                "abstain_reasons": abstain_reasons,
                "final_rep_count": final_output.rep_count if final_output else 0,
                "final_hold_duration_ms": (
                    final_output.hold_duration_ms if final_output else 0
                ),
            },
            "quality_gates": {
                "contains_raw_video": False,
                "contains_raw_landmarks": False,
                "requires_manual_review": self.label_source != "manual_label",
            },
        }
        self._validate_no_forbidden_keys(row)
        return row

    @staticmethod
    def _validate_no_forbidden_keys(value: Any) -> None:
        if isinstance(value, dict):
            overlap = FORBIDDEN_ROW_KEYS.intersection(value.keys())
            if overlap:
                raise ValueError(f"forbidden raw-data keys present: {sorted(overlap)}")
            for child in value.values():
                Layer2RecordedSequence._validate_no_forbidden_keys(child)
        elif isinstance(value, list):
            for child in value:
                Layer2RecordedSequence._validate_no_forbidden_keys(child)


class Layer2DatasetRecorder:
    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)

    def append(self, sequence: Layer2RecordedSequence) -> Dict[str, Any]:
        row = sequence.to_row()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            fh.write("\n")
        return row

    def append_many(self, sequences: Iterable[Layer2RecordedSequence]) -> int:
        count = 0
        for sequence in sequences:
            self.append(sequence)
            count += 1
        return count

    def read_rows(self) -> List[Dict[str, Any]]:
        if not self.path.exists():
            return []
        rows: List[Dict[str, Any]] = []
        with self.path.open("r", encoding="utf-8") as fh:
            for line in fh:
                if line.strip():
                    rows.append(json.loads(line))
        return rows


def build_recorded_sequence(
    sample_id: str,
    activity: str,
    features: List[Layer2Features],
    outputs: List[Layer2Output],
    source: Optional[Dict[str, Any]] = None,
    split_hint: str = "train",
    label_source: str = "fsm_weak_label",
) -> Layer2RecordedSequence:
    return Layer2RecordedSequence(
        sample_id=sample_id,
        activity=activity,
        features=features,
        outputs=outputs,
        source=source or {},
        split_hint=split_hint,
        label_source=label_source,
    )
