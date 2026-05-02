# GemmaFit Threshold Validation Summary

This report is generated from prototype angle CSV files. Rows without a label CSV are descriptive only.

## Inputs

- `data/processed/angles/opencv_vtest_walking_smoke_angles.csv`
- `data/processed/angles/opencv_vtest_walking_smoke_dense_angles.csv`
- `data/processed/angles/pushup_cdc_01_pushup_cdc_angles.csv`
- `data/processed/angles/squat_wikimedia_01_squat_wikimedia_angles.csv`

## Rule Summary

| Rule | Metric | Threshold | Frames | Triggered | Rate | Min | Mean | Max | Labeled | Precision | Recall | F1 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Knee lateral deviation ratio | < 0.75 | 234 | 79 | 0.338 | 0.059 | 1.040 | 5.753 | 0 | - | - | - |
| 1 | Knee FPPA | > 10 | 234 | 132 | 0.564 | 1.746 | 39.297 | 178.658 | 0 | - | - | - |
| 2 | Back slack heuristic | flag 1 | 234 | 73 | 0.312 | 68.796 | 165.394 | 179.912 | 0 | - | - | - |
| 6 | Rapid movement angular velocity | > 600 | 234 | 0 | 0.000 | 0.000 | 41.329 | 565.447 | 0 | - | - | - |

## Notes

- `prototype_threshold` means the metric is implemented but still needs dataset calibration.
- Rule 6 is stored in degrees per second, not degrees per frame.
- Rule 1 currently tracks both knee/ankle ratio and FPPA so later calibration can compare them directly.
