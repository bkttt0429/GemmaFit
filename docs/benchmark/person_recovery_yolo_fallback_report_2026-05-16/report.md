# person_recovery_yolo_fallback_report_2026-05-16

Status: PASS_WITH_GAP
Run: local_validation_run_2026-05-17
Host time: 2026-05-17T14:19:38.1530502+08:00
Git HEAD: `746c30b8616748cd417b1863bdc762ed26749f2a`
Device: `List of devices attached; 3A251FDJG004RX         device product:husky model:Pixel_8_Pro device:husky transport_id:1`
Raw evidence: `docs/benchmark/local_validation_run_2026-05-17/raw/`


## Claim

low confidence、多人物、遮擋時系統要 abstain 或 monitor，不用 stale skeleton 或錯人 skeleton 下 deterministic verdict。

## Device Evidence

| Clip | Pose hit | Avg visibility | Gate blocked | Low confidence | View limited | AI invoked | Boundary behavior |
|---|---:|---:|---:|---:|---:|---:|---|
| `no_person_blank_3s.mp4` | 0.0% | 0.0% | 0 | 0 | 0 | `True` | `refuse_unsupported_question` |
| `senior_occluded_720p.mp4` | 96.8% | 70.3% | 0 | 18 | 39 | `False` | `lower_body_reference_not_visible_for_body_line` |
| `senior_chair_stand_cdc_phonewin_t004.mp4` | 100.0% | 45.8% | 16 | 0 | 0 | `False` | `Low overall landmark visibility (0.454791 < 0.6). Adjust camera angle, distance, or lighting.` |
| `senior_balance_4stage_cdc_phonewin_t060.mp4` | 100.0% | 45.9% | 16 | 0 | 0 | `False` | `Low overall landmark visibility (0.453994 < 0.6). Adjust camera angle, distance, or lighting.` |

`no_person_blank_3s.mp4` 的 pose hit rate 是 0%，`ai_assistant_realtime` 走 `refuse_unsupported_question` 且 raw response 標明 `skipped_litert_generation=true`。遮擋片段有 18 個 low-confidence frames、39 個 view-limited frames，未觸發 immediate AI cue。

## Scheduler Evidence

`predicted_tracking` case: decision `SKIP_DETERMINISTIC`, allowed_judgment `False`, reason `tracking_predicted_monitor_only`。

## Unit Evidence

| Test | Result | Timestamp |
|---|---:|---|
| `SubjectRelocalizationPolicyTest` | 3 tests, 0 failures, 0 errors | `2026-05-14T18:34:06` |
| `SubjectIdentityMatcherTest` | 7 tests, 0 failures, 0 errors | `2026-05-14T18:34:06` |
| `PersonTrackingPolicyTest` | 3 tests, 0 failures, 0 errors | `2026-05-14T18:34:06` |
| `PersonProposalFusionTest` | 4 tests, 0 failures, 0 errors | `2026-05-14T18:34:06` |

## Gap

本次裝置端重跑沒有 fresh multi-person clip。多人物/錯人避免目前由 subject identity / relocalization unit XML 支撐，影片報告若要更硬，下一步要加一段實際 multi-person device replay。
