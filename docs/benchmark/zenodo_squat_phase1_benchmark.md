# Zenodo Squat Phase 1 Benchmark

This benchmark is generated from the completed Zenodo squat image run.
It is public-dataset evidence for static posture heuristics only.

## Dataset Run

- Selected images: 3806
- MediaPipe landmark frames: 3769
- Missed images: 37
- Angles CSV: `data\processed\angles\zenodo_squat_full_angles.csv`
- Labels CSV: `data\validation\zenodo_squat\zenodo_squat_full_labels.csv`

## Class Counts

| Split/Class | Images |
| --- | ---: |
| test/bad_back | 329 |
| test/bad_heel | 321 |
| test/good | 310 |
| train/bad_back | 956 |
| train/bad_heel | 852 |
| train/good | 1001 |

## Applicable Metrics

| Item | Prediction | Expected label | Frames | Pred + | Expected + | TP | FP | FN | TN | Precision | Recall | F1 | Evidence |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Rule 2 Bad Back | back_slack | rule2_expected | 3769 | 92 | 1285 | 92 | 0 | 1193 | 2484 | 1.000 | 0.072 | 0.134 | prototype_threshold |
| Bad Heel proxy | heels_off | heels_off_expected | 3769 | 1451 | 1173 | 1032 | 419 | 141 | 2177 | 0.711 | 0.880 | 0.787 | prototype_threshold |

## Interpretation

- Rule 2 Bad Back: precision is high but recall is low on the full image set, so the current back_slack rule is conservative.
- Bad Heel proxy: heel lift has a stronger signal for Bad Heel and is suitable as the first demo benchmark, while still remaining a prototype heuristic.

## Explicit Exclusions

- Rule 6 rapid movement is excluded because the Zenodo asset is image-only and has no time sequence.
- Rule 1 FPPA/knee-ratio is excluded because the dataset is side-view squat imagery, not a frontal knee-valgus benchmark.
- Rule 5 COM-vs-BoS is not fully validated here; Bad Heel is reported only as a heel-lift proxy.

## Report Language

- `prototype_threshold` means implemented and reportable, not clinically validated.
- This report should be cited as a Phase 1 benchmark, not as final threshold validation.
