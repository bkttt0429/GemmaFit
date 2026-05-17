# live_safety_contract_report_2026-05-16

Status: PASS
Run: local_validation_run_2026-05-17
Host time: 2026-05-17T14:19:38.1530502+08:00
Git HEAD: `746c30b8616748cd417b1863bdc762ed26749f2a`
Device: `List of devices attached; 3A251FDJG004RX         device product:husky model:Pixel_8_Pro device:husky transport_id:1`
Raw evidence: `docs/benchmark/local_validation_run_2026-05-17/raw/`


## Claim

影片中的 live safety 主路徑不呼叫 Gemma 或 multimodal backend。`WARNING` / `MONITOR` 先由 deterministic evidence 決定，Gemma 只在低頻事件解釋或 summary。

## Evidence

- `model_invocation_smoke.success = True`
- `LIVE_FRAME`: decision `SKIP_DETERMINISTIC`, backend `DETERMINISTIC_ONLY`, context `NONE`, reasoning `off`.
- `LIVE_FRAME` 即使打開 multimodal flags: action `SKIP`, build_panel `False`, call_backend `False`, reason `live_frame_never_uses_multimodal`.
- `REP_COMPLETED` warning case 才會進 `CALL_E2B_NOW`，reason `rep_completed_with_explainable_warning`，代表 warning evidence 已經存在後才請模型解釋。
- `SESSION_ENDED` / `CAREGIVER_EXPORT` multimodal sidecar 允許 build panel 與 call backend：`session_ended_multimodal_summary`, `caregiver_export_multimodal_summary`。

## Test Gates

| Test | Result |
|---|---:|
| `ModelInvocationSchedulerTest` | 15 tests, 0 failures, 0 errors |
| `MultimodalResultValidatorTest` | 5 tests, 0 failures, 0 errors |
| `TrustUiStateTest` | 8 tests, 0 failures, 0 errors |

## Video Smoke Cross-Check

| Clip | Live plan | AI invoked | Reason |
|---|---|---:|---|
| `senior_occluded_720p.mp4` | `SKIP_DETERMINISTIC` | `False` | `live_frame_deterministic_path` |
| `senior_chair_stand_cdc_phonewin_t004.mp4` | `SKIP_DETERMINISTIC` | `False` | `pose_confidence_below_model_context_threshold` |
| `senior_chair_stand_cdc_phonewin_t062.mp4` | `SKIP_DETERMINISTIC` | `False` | `live_frame_deterministic_path` |

## Conclusion

這份本機驗證可以支撐影片口播：「我們不是讓 LLM 每 frame 看影片下安全判斷；live safety 先由 deterministic gate 決定，Gemma 只處理低頻解釋與 summary。」仍未宣稱 30 分鐘長跑 trace，只有 scheduler contract、unit tests 與代表 clip smoke。
