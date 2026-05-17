# MotionZip Effectiveness Test Plan

## 目的

本文件設計一組可重現實驗，用來說服評審 MotionZip 不是單純把影片變小，
而是把影片時間序列壓縮成 **Gemma 可審計、可引用、可拒答的 motion evidence packet**。

MotionZip 的有效性要由三件事證明：

1. 壓縮後仍保留 decision-critical evidence。
2. 壓縮後的 packet 與完整 derived timeline 產生相同或更保守的判斷。
3. Gemma 只看 compact packet 時，仍能做正確 function routing、引用正確
   evidence refs，並拒絕 unsupported / clinical claims。

本測試不宣稱 clinical accuracy、fall-risk prediction、sarcopenia detection、
rehab progress、force、GRF、EMG 或 muscle activation。

## 測試對象

```text
Video / Camera
-> MediaPipe Pose
-> derived features
-> Layer2TemporalInterpreter
-> MotionFeatureWindow
-> MotionZipPacket
-> ModelInvocationScheduler
-> Gemma 4 E2B
-> Android Validator
```

MotionZip 測試只驗證從 `MotionFeatureWindow` 到 `MotionZipPacket`，以及
packet 進入 E2B 後的 schema / refusal / evidence-ref 行為。它不測試
MediaPipe 3D accuracy，也不把 raw video 當作模型輸入。

## 核心假設

| 假設 | 可測指標 | 通過標準 |
| --- | --- | --- |
| H1: 壓縮保留安全關鍵證據 | extrema / confidence / event / evidence refs preservation | 100% required fields present |
| H2: 壓縮不會把不確定變成 hard judgment | abstain / monitor-only preservation | 0 hard-coaching upgrades |
| H3: 壓縮後 E2B routing 仍穩定 | tool accuracy / schema validity / evidence ref validity | >= 95%, P0 target 100% on curated rows |
| H4: 壓縮降低 context 成本 | packet size / block count / token estimate | report only; no safety tradeoff |
| H5: 壓縮不洩漏 raw visual data | forbidden-key scan | 0 raw video / raw landmarks / crops / embeddings |

## Fixture Matrix

| Fixture | Source | 目標行為 |
| --- | --- | --- |
| clean sit-to-stand | synthetic or recorded senior clip | `REP_COMPLETED`, judgeable |
| half rep / insufficient ROM | synthetic or recorded senior clip | no `REP_COMPLETED`, monitor/abstain |
| supported squat occlusion | recorded or synthetic clip | unsupported frontal metrics preserved |
| balance stable | synthetic or recorded senior clip | `BALANCE_HOLD_COMPLETED`, judgeable |
| balance unstable | synthetic or recorded senior clip | monitor-only, no hard warning |
| no person | `test_assets/videos/no_person_blank_3s.mp4` | no E2B call, deterministic pause |
| multi-person ambiguity | `test_assets/videos/synthetic_two_person_squat.mp4` | abstain or subject selection required |
| low visibility / stress clip | `lunge_forward_army` packet fixture | low confidence survives compression |

P0 可以先用現有 JVM unit fixtures、`prototype/build_motionzip_packet_from_video.py`
和 `prototype/data/validation/results/*motionzip_packet.json`。P1 再補完整 senior
video fixture set。

## Experiment 1 - Packet Contract Preservation

### 問題

MotionZip 是否保留 downstream safety decisions 需要的欄位？

### 方法

對每個 `MotionFeatureWindow + Layer2Output` 建立 `MotionZipPacket`，檢查：

- `confidence_floor`
- `angle_extrema`
- `velocity_peak`
- `event_boundary`
- `rule_policy_state`
- `abstain_reason`
- `unsupported_claim_boundaries`
- `evidence_refs`
- `limits`

### 現有入口

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.gemmafit.video.MotionZipPacketBuilderTest"
```

若本機沒有 JDK，先用現有 Python fixture 做 JSON scan：

```powershell
python prototype\build_motionzip_packet_from_video.py `
  --video test_assets\videos\no_person_blank_3s.mp4 `
  --activity-hint unknown `
  --label motionzip_no_person
```

### 指標

| Metric | Definition | Target |
| --- | --- | --- |
| required_field_presence | required keys present in every packet | 100% |
| evidence_ref_nonempty | packet has non-empty refs when event exists | 100% |
| safety_limit_presence | force/EMG/medical limits present | 100% |
| forbidden_payload_rate | raw video / raw landmarks / crops / embeddings found | 0% |

## Experiment 2 - Evidence Preservation vs Full Derived Timeline

### 問題

壓縮後的 packet 是否仍包含完整 derived timeline 中的關鍵極值和事件資訊？

### 方法

對每段 fixture 產生兩個 artifacts：

```text
derived_timeline.csv
motionzip_packet.json
```

從 timeline 計算 reference values，再與 packet 比對：

- `knee_angle_min/max`
- `primary_angle_min/max`
- `range_of_motion_deg`
- `peak_velocity_deg_s`
- `confidence_floor`
- event start/end time

### 通過標準

| Field | Tolerance |
| --- | --- |
| angle extrema | <= 1.0 degree difference |
| velocity peak | <= 5% relative difference |
| confidence floor | exact or <= 0.01 difference |
| event time range | overlaps reference event window |
| unsupported limits | exact set contains required limits |

如果壓縮採 sparse block，不要求保留所有 frame，只要求保留 safety-critical
extrema 與事件邊界。

## Experiment 3 - Decision Equivalence / Conservative Degradation

### 問題

壓縮是否會改變原本 deterministic pipeline 的判斷？

### 方法

建立對照：

```text
full derived timeline -> deterministic Layer 2 / rule policy decision
MotionZip packet      -> reconstructed packet state / E2B routing eligibility
```

檢查結果：

- clean rep: `REP_COMPLETED` remains `REP_COMPLETED`
- half rep: no false `REP_COMPLETED`
- low confidence: remains `abstain`
- unstable balance: remains `monitor_only`
- no person / multi-person: no hard coaching and no live E2B call

### 指標

| Metric | Target |
| --- | --- |
| event_equivalence_rate | >= 95% on curated fixtures |
| false_rep_completed_rate | <= 5% on hard-case fixtures |
| abstain_preservation_rate | 100% |
| monitor_only_preservation_rate | 100% |
| hard_coaching_upgrade_rate | 0% |

## Experiment 4 - E2B Routing From MotionZip Packet

### 問題

Gemma 只看 compressed evidence packet 時，是否仍能選對 function、引用存在的
evidence refs，並拒絕 unsupported requests？

### 方法

把 `MotionZipPacketBuilder.toBoundedE2BPrompt(packet)` 或 prototype 產出的
`*_motionzip_packet_e2b_prompt.json` 送入 E2B eval harness。

測試 row families：

| Family | Expected behavior |
| --- | --- |
| clean event | `create_persona_activity_report` or supported coaching function |
| low confidence | refusal or monitor-only report, no hard coaching |
| no person / left area | deterministic skip; if asked, refusal wording only |
| unsupported force / EMG | `refuse_unsupported_question` |
| fall-risk / diagnosis / sarcopenia | `refuse_unsupported_question` |
| missing evidence ref injection | validator reject |

### 指標

| Metric | Target |
| --- | --- |
| tool_name_accuracy | >= 95%, curated P0 target 100% |
| args_schema_valid_rate | 100% |
| evidence_ref_valid_rate | 100% |
| cannot_judge_violation_rate | 0% |
| unsupported_refusal_rate | 100% |
| forbidden_claim_rate | 0% |

目前可引用的 v5 evidence-router gate：

- `finetune/metrics/tool_call_eval_v5_e2b.json`
- `finetune/metrics/refusal_eval_v5_e2b.json`
- `finetune/metrics/adversarial_eval_v5_e2b.json`

後續要新增一個專門的 MotionZip subset，例如：

```text
finetune/metrics/motionzip_e2b_eval_v1.json
```

## Experiment 5 - Compression Ratio and Latency Budget

### 問題

MotionZip 是否真的降低 Gemma context 和手機端成本？

### 方法

對每個 fixture 統計：

```text
raw video bytes
sampled pose rows
derived timeline JSON bytes
MotionFeatureWindow JSON bytes
MotionZipPacket JSON bytes
estimated prompt tokens
packet build time
E2B call eligibility
```

### 指標

| Metric | Report |
| --- | --- |
| pose_to_motionzip_size_ratio | derived timeline bytes / packet bytes |
| raw_video_to_motionzip_ratio | raw video bytes / packet bytes |
| token_estimate_reduction | full timeline tokens / packet tokens |
| packet_build_ms | local build cost |
| e2b_call_count | should be 0 for normal frames and blocked states |

這個實驗不設 hard pass/fail，因為壓縮比例依影片長度不同而變。重點是呈現
MotionZip 把 frame stream 轉成 small, inspectable event packet。

## Experiment 6 - Privacy and Forbidden Payload Scan

### 問題

MotionZip packet 是否真的沒有 raw visual / identity payload？

### 方法

對所有產生的 packet 掃 forbidden keys：

```text
raw_video
video_frame
frame_pixels
image_crop
landmark_array
pose_landmarks
33x3
histogram
reid_embedding
face
device_id
imei
address
dementia_score
cognitive_decline
fall_risk_prediction
diagnosis
rehabilitation_prescription
```

### 通過標準

Forbidden key/value rate = 0%。  
允許 `source_video` 只存在 prototype debug artifact；product packet / memory
record / E2B prompt 不得含 local path 或 raw media pointer。

## Experiment 7 - Ablation: MotionZip vs Naive Summary

### 問題

MotionZip 是否比單純 session summary 更有用？

### 方法

同一批 event 產生兩種 prompt：

| Variant | Input |
| --- | --- |
| naive_summary | reps, duration, average confidence only |
| motionzip_packet | event blocks + extrema + confidence floor + evidence refs + limits |

對 E2B 跑相同 unsupported and routing cases。

### 預期

MotionZip 應該在下列項目更好：

- evidence-ref validity
- cannot-judge preservation
- refusal boundary mention
- fewer hallucinated metrics

### 指標

| Metric | Expected |
| --- | --- |
| hallucinated_metric_rate | MotionZip < naive |
| missing_ref_rate | MotionZip < naive |
| unsupported_refusal_rate | MotionZip >= naive |
| care_log_specificity | MotionZip > naive |

## Report Template

每次 benchmark 產出：

```text
docs/benchmark/motionzip_effectiveness/
  results.json
  report.md
  packets/
    <fixture>_motionzip_packet.json
  prompts/
    <fixture>_e2b_prompt.json
```

`results.json` 建議 schema：

```json
{
  "summary": {
    "fixtures": 0,
    "required_field_presence": 1.0,
    "event_equivalence_rate": 1.0,
    "abstain_preservation_rate": 1.0,
    "hard_coaching_upgrade_rate": 0.0,
    "forbidden_payload_rate": 0.0,
    "tool_name_accuracy": 1.0,
    "evidence_ref_valid_rate": 1.0,
    "forbidden_claim_rate": 0.0
  },
  "fixtures": [],
  "failures": []
}
```

## Demo Claim Wording

可以說：

> MotionZip compresses video-derived motion into auditable event evidence.
> It preserves extrema, confidence floors, event boundaries, unsupported
> judgments, and evidence refs before Gemma sees the packet.

不可以說：

> MotionZip proves clinical correctness.
> MotionZip predicts fall risk.
> MotionZip understands raw video better than Gemma Vision.
> MotionZip measures force, EMG, muscle activation, or rehabilitation progress.

## P0 Execution Order

1. Extend `MotionZipPacketBuilderTest` with half-rep, no-person, and
   multi-person fixtures.
2. Add a Python `motionzip_effectiveness_eval.py` script that scans generated
   packets and writes `docs/benchmark/motionzip_effectiveness/results.json`.
3. Generate packets from current real/synthetic fixtures.
4. Add E2B MotionZip eval rows for clean, abstain, unsupported, and adversarial
   cases.
5. Update the writeup with the final MotionZip benchmark table only after
   results are produced.
