# Pixel LiteRT Model Equivalence Result

This is the actual on-device model run, not oracle-only evaluation.

## Runtime

| Field | Value |
| --- | ---: |
| Device path | `/storage/emulated/0/Android/data/com.gemmafit/files/gemmafit-v5-e2b-evidence-router.litertlm` |
| Model size | 5,071,689,680 bytes |
| Backend | `litert-lm:raw:cpu` |
| Prompt pair | `model_prompt_pair_compact.jsonl` |
| Prompt pair size | 8,768 bytes |
| Elapsed | 142,688 ms |

## Result

| Check | Result |
| --- | --- |
| Overall pass | `true` |
| Pass rate | `8/8` |
| Activity | `lunge_like_unilateral_motion` == `lunge_like_unilateral_motion` |
| States | `abstain, monitor_only` == `abstain, monitor_only` |
| Event count | `2` == `2` |
| Event frames | dense `258, 1440`; MotionZip `258, 1434`; max diff `6` frames within `12` |
| Velocity band | `high` == `high` |
| Velocity peak | dense `857.147`; MotionZip `840.93`; relative error `1.89%` |
| Confidence floor | `0.5145` == `0.5145` |
| Low-confidence reason | `low_keypoint_visibility` == `low_keypoint_visibility` |

## Notes

- The first tool-calling attempt reached LiteRT but failed in the LiteRT template path with `unknown method: map has no method named get`.
- The endpoint was switched to raw JSON generation for this artifact.
- A first raw run passed `6/8`; the failures were prompt-specification issues: dense input used event-window start frames and did not define the velocity-band threshold clearly.
- After adding `event_frame` and the shared velocity-band rule to both prompts, the same on-device model passed `8/8`.
- `DebugReportProvider` now runs in the `:debug` process so adb benchmark inference does not start the main UI or MediaPipe pipeline.
