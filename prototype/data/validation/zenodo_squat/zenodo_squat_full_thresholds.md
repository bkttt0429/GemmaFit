# GemmaFit Threshold Validation Summary

This report is generated from prototype angle CSV files. Rows without a label CSV are descriptive only.

## Inputs

- `data/processed/angles/zenodo_squat_full_angles.csv`

## Rule Summary

| Rule | Metric | Threshold | Frames | Triggered | Rate | Min | Mean | Max | Labeled | Precision | Recall | F1 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Knee lateral deviation ratio | < 0.75 | 3769 | 1575 | 0.418 | 0.048 | 0.878 | 15.520 | 0 | - | - | - |
| 1 | Knee FPPA | > 10 | 3769 | 3761 | 0.998 | 3.895 | 110.071 | 152.619 | 0 | - | - | - |
| 2 | Back slack heuristic | flag 1 | 3769 | 92 | 0.024 | 12.898 | 115.203 | 179.433 | 3769 | 1.000 | 0.072 | 0.134 |
| 5 | Heel lift heuristic | > 3 | 3769 | 1451 | 0.385 | -167.130 | -9.404 | 165.272 | 3769 | 0.711 | 0.880 | 0.787 |
| 6 | Rapid movement angular velocity | > 600 | 3769 | 0 | 0.000 | 0.000 | 0.000 | 0.000 | 3769 | - | - | - |

## Notes

- `prototype_threshold` means the metric is implemented but still needs dataset calibration.
- Rule 6 is stored in degrees per second, not degrees per frame.
- Rule 1 currently tracks both knee/ankle ratio and FPPA so later calibration can compare them directly.
