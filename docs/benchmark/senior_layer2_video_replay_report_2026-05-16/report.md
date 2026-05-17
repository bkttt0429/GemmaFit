# senior_layer2_video_replay_report_2026-05-16

Status: PASS_WITH_VIDEO_SCOPE_NOTE
Run: local_validation_run_2026-05-17
Host time: 2026-05-17T14:19:38.1530502+08:00
Git HEAD: `746c30b8616748cd417b1863bdc762ed26749f2a`
Device: `List of devices attached; 3A251FDJG004RX         device product:husky model:Pixel_8_Pro device:husky transport_id:1`
Raw evidence: `docs/benchmark/local_validation_run_2026-05-17/raw/`


## Claim

如果影片主打 Senior Mode，需要能展示 chair / squat / balance replay timeline，且仍保持 non-clinical、monitor-only 邊界。

## Layer2 Smoke

- `layer2_smoke.success = True`

| Case | Phase sequence | Events | Verdict / policy |
|---|---|---|---|
| `chair_sit_to_stand_rep` | `['standing_stabilized', 'standing_stabilized', 'descending', 'seated_low', 'seated_low', 'seated_low', 'rising', 'standing_stabilized', 'standing_stabilized']` | `[]` | `n/a` |
| `supported_squat_rep_monitor_only` | `['standing_stabilized', 'standing_stabilized', 'descending', 'squat_bottom', 'squat_bottom', 'squat_bottom', 'rising', 'standing_stabilized', 'standing_stabilized']` | `[]` | `n/a` |
| `balance_hold_completed` | `['balance_holding', 'balance_holding', 'balance_holding', 'balance_holding', 'balance_holding', 'balance_holding']` | `[]` | `n/a` |
| `non_senior_lunge_demoted` | `['monitor_only', 'monitor_only', 'monitor_only', 'monitor_only', 'monitor_only']` | `[]` | `n/a` |
| `non_senior_basketball_demoted` | `['monitor_only', 'monitor_only', 'monitor_only', 'monitor_only', 'monitor_only']` | `[]` | `n/a` |
| `predicted_tracking_abstain` | `['abstain']` | `[]` | `n/a` |

## Device Replay Clips

| Clip | Pose hit | Avg visibility | Gate blocked | Monitor | First boundary reason |
|---|---:|---:|---:|---:|---|
| `senior_chair_stand_cdc_phonewin_t004.mp4` | 100.0% | 45.8% | 16 | 0 | `Low overall landmark visibility (0.454791 < 0.6). Adjust camera angle, distance, or lighting.` |
| `senior_chair_stand_cdc_phonewin_t062.mp4` | 93.8% | 71.6% | 2 | 13 | `lower_body_reference_not_visible_for_body_line` |
| `senior_balance_4stage_cdc_phonewin_t060.mp4` | 100.0% | 45.9% | 16 | 0 | `Low overall landmark visibility (0.453994 < 0.6). Adjust camera angle, distance, or lighting.` |
| `senior_occluded_720p.mp4` | 96.8% | 70.3% | 0 | 26 | `lower_body_reference_not_visible_for_body_line` |

## Unit Evidence

| Test | Result | Timestamp |
|---|---:|---|
| `Layer2TemporalInterpreterTest` | 12 tests, 0 failures, 0 errors | `2026-05-16T15:24:11` |
| `Layer2SeniorArchitectureABTest` | 1 tests, 0 failures, 0 errors | `2026-05-16T13:31:25` |
| `SeniorInteractionPolicyTest` | 6 tests, 0 failures, 0 errors | `2026-05-16T13:32:21` |
| `SeniorVoiceAnswerParserTest` | 7 tests, 0 failures, 0 errors | `2026-05-14T15:32:23` |
| `SeniorGestureDetectorTest` | 3 tests, 0 failures, 0 errors | `2026-05-14T15:32:23` |
| `SeniorDualTaskVoiceControllerTest` | 8 tests, 0 failures, 0 errors | `2026-05-14T15:32:23` |

## Conclusion

Senior Mode video can show real replay clips plus Layer2 timeline output. Some chair/balance segments are intentionally low visibility and therefore prove conservative abstain/gating more than full rep recognition; for a polished demo, pair one clear chair/squat clip with one low-visibility abstain clip.
