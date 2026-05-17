from __future__ import annotations

import tempfile
from pathlib import Path

from layer2_dataset_recorder import (
    Layer2DatasetRecorder,
    Layer2RecordedSequence,
    build_recorded_sequence,
)
from layer2_fsm import (
    Layer2Activity,
    Layer2Config,
    Layer2Event,
    Layer2FSM,
    Layer2Features,
    Layer2Phase,
    run_layer2_sequence,
)


def _knee_sequence(
    activity: str,
    angles: list[float],
    *,
    start_ms: int = 0,
    step_ms: int = 100,
) -> list[Layer2Features]:
    return [
        Layer2Features(
            timestamp_ms=start_ms + idx * step_ms,
            activity_hint=activity,
            knee_angle_deg=angle,
            pose_confidence=0.9,
            support_contact=(activity == Layer2Activity.SUPPORTED_SQUAT.value),
        )
        for idx, angle in enumerate(angles)
    ]


def test_chair_sit_to_stand_rep_completed() -> None:
    features = _knee_sequence(
        Layer2Activity.CHAIR_SIT_TO_STAND.value,
        [170, 148, 118, 96, 110, 132, 154, 166, 169],
    )

    outputs = run_layer2_sequence(
        features,
        Layer2Activity.CHAIR_SIT_TO_STAND.value,
        config=Layer2Config(stable_top_frames=2),
    )

    assert Layer2Phase.SIT_LOW.value in {o.phase for o in outputs}
    assert Layer2Phase.RISING.value in {o.phase for o in outputs}
    assert Layer2Event.REP_STARTED.value in {o.event for o in outputs}
    assert outputs[-1].event == Layer2Event.REP_COMPLETED.value
    assert outputs[-1].rep_count == 1
    assert "layer2.event.rep_completed" in outputs[-1].evidence_refs


def test_supported_squat_rep_completed() -> None:
    features = _knee_sequence(
        Layer2Activity.SUPPORTED_SQUAT.value,
        [170, 150, 124, 104, 112, 136, 160, 166],
    )

    fsm = Layer2FSM(
        activity=Layer2Activity.SUPPORTED_SQUAT.value,
        config=Layer2Config(stable_top_frames=1),
    )
    outputs = [fsm.update(frame) for frame in features]

    assert Layer2Phase.SQUAT_BOTTOM.value in {o.phase for o in outputs}
    assert Layer2Event.REP_STARTED.value in {o.event for o in outputs}
    assert Layer2Event.REP_COMPLETED.value in {o.event for o in outputs}
    assert outputs[-1].rep_count == 1
    assert outputs[-1].activity_hypothesis == Layer2Activity.SUPPORTED_SQUAT.value


def test_balance_hold_started_and_completed() -> None:
    config = Layer2Config(balance_hold_target_ms=750)
    features = [
        Layer2Features(
            timestamp_ms=timestamp,
            activity_hint=Layer2Activity.BALANCE_HOLD.value,
            sway_norm=0.02,
            pose_confidence=0.92,
        )
        for timestamp in [0, 250, 500, 750, 1000]
    ]

    outputs = run_layer2_sequence(features, Layer2Activity.BALANCE_HOLD.value, config)

    assert outputs[0].event == Layer2Event.BALANCE_HOLD_STARTED.value
    assert outputs[3].event == Layer2Event.BALANCE_HOLD_COMPLETED.value
    assert outputs[-1].phase == Layer2Phase.BALANCE_HOLDING.value
    assert outputs[-1].hold_duration_ms == 1000


def test_tracking_gate_abstains() -> None:
    output = Layer2FSM().update(
        Layer2Features(
            timestamp_ms=0,
            person_state="lost",
            knee_angle_deg=170,
            pose_confidence=0.9,
        )
    )

    assert output.phase == Layer2Phase.ABSTAIN.value
    assert output.event == Layer2Event.ABSTAIN.value
    assert output.abstain_reason == "person_not_observed"
    assert output.rep_count == 0


def test_dataset_recorder_writes_fsm_weak_labels() -> None:
    features = _knee_sequence(
        Layer2Activity.CHAIR_SIT_TO_STAND.value,
        [170, 148, 116, 98, 112, 136, 160, 168],
    )
    outputs = run_layer2_sequence(
        features,
        Layer2Activity.CHAIR_SIT_TO_STAND.value,
        Layer2Config(stable_top_frames=1),
    )
    sequence = build_recorded_sequence(
        sample_id="chair_sts_smoke_001",
        activity=Layer2Activity.CHAIR_SIT_TO_STAND.value,
        features=features,
        outputs=outputs,
        source={"asset": "synthetic_knee_angles", "contains_raw_video": False},
        split_hint="train",
    )

    with tempfile.TemporaryDirectory() as temp_dir:
        recorder = Layer2DatasetRecorder(Path(temp_dir) / "layer2.jsonl")
        row = recorder.append(sequence)
        rows = recorder.read_rows()

    assert row["schema_version"] == "layer2_sequence_v1"
    assert len(rows) == 1
    assert rows[0]["labels"]["final_rep_count"] == 1
    assert rows[0]["labels"]["events"][-1]["event"] == Layer2Event.REP_COMPLETED.value
    assert rows[0]["quality_gates"]["contains_raw_landmarks"] is False


def test_recorder_rejects_raw_data_keys() -> None:
    features = [
        Layer2Features(
            timestamp_ms=0,
            knee_angle_deg=170,
            extra={"raw_landmarks": [[0.0, 0.0, 0.0]]},
        )
    ]
    outputs = run_layer2_sequence(features, Layer2Activity.CHAIR_SIT_TO_STAND.value)
    sequence = Layer2RecordedSequence(
        sample_id="bad_raw_key",
        activity=Layer2Activity.CHAIR_SIT_TO_STAND.value,
        features=features,
        outputs=outputs,
    )

    try:
        sequence.to_row()
    except ValueError as exc:
        assert "raw_landmarks" in str(exc)
    else:
        raise AssertionError("expected recorder to reject raw_landmarks")


def main() -> None:
    tests = [
        test_chair_sit_to_stand_rep_completed,
        test_supported_squat_rep_completed,
        test_balance_hold_started_and_completed,
        test_tracking_gate_abstains,
        test_dataset_recorder_writes_fsm_weak_labels,
        test_recorder_rejects_raw_data_keys,
    ]
    for test in tests:
        test()
    print(f"OK {len(tests)} Layer 2 FSM/recorder tests passed")


if __name__ == "__main__":
    main()
