# GemmaFit Threshold Validation Summary

This report is generated from prototype angle CSV files. Rows without a label CSV are descriptive only.

## Inputs

- `data/processed/angles/zenodo_squat_sample_angles.csv`

## Rule Summary

| Rule | Metric | Threshold | Frames | Triggered | Rate | Min | Mean | Max | Labeled | Precision | Recall | F1 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | Knee lateral deviation ratio | < 0.75 | 360 | 188 | 0.522 | 0.161 | 0.813 | 4.694 | 0 | - | - | - |
| 1 | Knee FPPA | > 10 | 360 | 358 | 0.994 | 3.895 | 107.310 | 135.057 | 0 | - | - | - |
| 2 | Back slack heuristic | flag 1 | 360 | 295 | 0.819 | 62.100 | 117.923 | 154.777 | 360 | 0.285 | 0.700 | 0.405 |
| 5 | Heel lift heuristic | > 3 | 360 | 105 | 0.292 | -160.669 | -77.837 | 34.023 | 360 | 0.000 | 0.000 | - |
| 6 | Rapid movement angular velocity | > 600 | 360 | 0 | 0.000 | 0.000 | 0.000 | 0.000 | 360 | - | - | - |

## Notes

- `prototype_threshold` means the metric is implemented but still needs dataset calibration.
- Rule 6 is stored in degrees per second, not degrees per frame.
- Rule 1 currently tracks both knee/ankle ratio and FPPA so later calibration can compare them directly.
