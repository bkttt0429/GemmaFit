# local_validation_run_2026-05-17

本資料夾保存這次本機驗證的 raw outputs。五份對外 report 在 sibling report folders：

| Report | Status | Main evidence |
|---|---|---|
| `live_safety_contract_report_2026-05-16` | PASS | `model_invocation_smoke`, video live-frame plans, scheduler tests |
| `person_recovery_yolo_fallback_report_2026-05-16` | PASS_WITH_GAP | no-person / occlusion / low-visibility replay, subject policy XML |
| `litert_runtime_stability_report_2026-05-16` | PASS_WITH_CAVEAT | model readiness, prewarm, prompt infer, 100-run anchor |
| `memory_export_boundary_report_2026-05-16` | PASS_WITH_RUNTIME_STATE_GAP | memory/export tests, raw boundary scan |
| `senior_layer2_video_replay_report_2026-05-16` | PASS_WITH_VIDEO_SCOPE_NOTE | Layer2 smoke, chair/balance replay clips |

可視化：`validation_matrix.svg`

Raw device endpoints and replay JSON are under `raw/`.
