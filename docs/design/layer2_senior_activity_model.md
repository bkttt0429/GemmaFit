# Senior Layer 2 Activity Model

> **v2 update (2026-05-15)**：本文 §15 新增 **Motion Evidence Compiler**
> 設計，把 Layer 2 從單一 temporal interpreter 重新定位成「7-submodule
> evidence compiler」。v2 enhancements 在 §15 集中說明並標註對應的 v1
> sections。v1 內容（§1-§14）保留作為 migration baseline，**新工作直接
> 看 §15**。Architecture-level 整合請見 [`official_e2b_motionzip_runtime_architecture.md`](./official_e2b_motionzip_runtime_architecture.md)。

本文定義 GemmaFit 的長者專用 Layer 2 動作判斷層。它是
streaming-first temporal interpreter，不是通用健身動作分類器，也不是
醫療模型。

Layer 2 的任務是把 MediaPipe Pose 與 deterministic biomechanics tools
產出的 derived features，轉成有限範圍的長者活動假設、活動階段、事件與
可判斷性。它不產生安全結論，不覆寫 Confidence Gate，不判斷 fall risk、
sarcopenia、rehabilitation progress、clinical status、force、GRF、EMG、
heart rate 或 injury risk。

## 1. Contract

Layer 2 的輸出是 evidence，不是 coaching verdict。

```json
{
  "activity_hypothesis": {
    "label": "chair_sit_to_stand",
    "confidence": 0.86,
    "source": ["senior_mode", "derived_motion_features"]
  },
  "phase": "rising",
  "event": "none",
  "judgeability": "judgeable",
  "abstain_reason": null,
  "rep_count": 2,
  "hold_duration_ms": 0,
  "evidence_refs": [
    "layer2.activity.chair_sit_to_stand",
    "layer2.phase.rising"
  ]
}
```

Required fields:

| Field | Meaning |
| --- | --- |
| `activity_hypothesis` | 有界活動假設，不是絕對動作命名。 |
| `phase` | 當前 temporal phase。 |
| `event` | 是否產生可下游處理的事件。 |
| `judgeability` | `judgeable`、`monitor_only`、或 `abstain`。 |
| `abstain_reason` | 低信心、追蹤中斷、多人干擾或缺少必要特徵時必填。 |
| `evidence_refs` | 可被 E2B 引用的 deterministic evidence ids。 |

Layer 2 不更改 E2B function schema。它只提供 compact context：
`activity_context`、`motion_context`、`event_trigger`、`evidence_refs`。
E2B 可以引用和解釋，但不能改寫 Layer 2 label、confidence gate 或
`can_judge` / `cannot_judge` 結果。

## 2. Senior-only Taxonomy

P0 只支援長者訓練活動，不把一般運動 label 放進主線。

| Label | Description | P0 role |
| --- | --- | --- |
| `chair_sit_to_stand` | 椅子起立與坐下循環。 | Hero activity。 |
| `supported_squat` | 扶椅、扶牆或支撐式蹲起。 | 下肢控制展示。 |
| `balance_hold` | 靜態站立、雙腳或單腳穩定保持。 | 穩定度 proxy 展示。 |
| `setup_transition` | 準備、移動、拿椅子、調整站位。 | 防止誤判。 |
| `unknown` | 不支援或不確定活動。 | Safety fallback。 |

`lunge`、`basketball_jump_shot`、一般健身多動作分類屬於 non-senior
roadmap。截稿版應降級為 `unknown`、`setup_transition` 或
`monitor_only`，避免錯誤套用 senior safety templates。

### 2.1 Migration from current Android taxonomy

`app/src/main/kotlin/com/gemmafit/video/Layer2TemporalInterpreter.kt` 仍包含
`LUNGE`、`BASKETBALL_JUMP_SHOT` 與 sport-composite 的 sub-action / rule policy。
P0 deadline 期間採取 **map-and-quarantine**，不直接移除程式碼，避免破壞既有
`E2B` event packet 與 caregiver report 流程。

| Legacy Android label | Senior P0 mapping | Action |
| --- | --- | --- |
| `chair_sit_to_stand` | `chair_sit_to_stand` | 保留，對齊 senior contract。 |
| `lunge`（`LUNGE`） | `unknown` 或 `setup_transition` | `normalizeActivity` 降級回傳；不再進 `updateLunge`，移到 `non_senior_demo` build flavor。 |
| `basketball_jump_shot` | `unknown` | 同上；`updateBasketballJumpShot` 與 sub-action 寫入只在 `non_senior_demo` flavor 啟用。 |
| 缺 `supported_squat` | 新增 `SUPPORTED_SQUAT` enum | 對齊 Python prototype；觸發 `controlledStrength` rule policy 但 frontal valgus `not_applicable`。 |
| 缺 `balance_hold` | 新增 `BALANCE_HOLD` enum 與 FSM | 由 `sway_norm` 驅動，hysteresis 同 §4.4。 |
| 缺 `setup_transition` | 新增 `SETUP_TRANSITION` enum | 防誤判過渡幀，事件預設 `none`、phase `setup`。 |

Senior main flavor 的 `Layer2Activity` 必須是
`{CHAIR_SIT_TO_STAND, SUPPORTED_SQUAT, BALANCE_HOLD, SETUP_TRANSITION, UNKNOWN}`。
任何由 `activity_hint` 帶進的非 senior label 在 `normalizeActivity` 內會被映射到
`UNKNOWN`，並在 debug report 標記 `non_senior_label_demoted`，方便 QA 追蹤。

Phase set:

| Phase | Used by |
| --- | --- |
| `setup` | 所有活動 |
| `standing_stabilized` | sit-to-stand、supported squat、balance |
| `descending` | sit-to-stand、supported squat |
| `seated_low` | chair sit-to-stand |
| `squat_bottom` | supported squat |
| `rising` | sit-to-stand、supported squat |
| `balance_holding` | balance hold |
| `balance_unstable` | balance hold |
| `monitor_only` | evidence insufficient but person still tracked |
| `abstain` | tracking or judgment gate blocks output |

Event set:

```text
none
rep_started
rep_completed
balance_hold_started
balance_hold_completed
monitor_only
abstain
```

Only events trigger recorder and model scheduling. Normal frames update UI state
but do not call E2B.

## 3. Streaming Mobile Pipeline

Target realtime flow:

```text
CameraX
-> MediaPipe Pose LIVE_STREAM
-> derived pose features
-> Confidence / Subject gate
-> Senior Layer 2 FSM or tiny model
-> MotionFeatureWindow on event
-> ModelInvocationScheduler
-> compact E2B event packet
-> validator / deterministic fallback
-> UI / TTS / care log / debug report
```

Frequency budget:

| Component | Target cadence | Notes |
| --- | ---: | --- |
| Camera preview / overlay | 30 fps | Visual path only. |
| MediaPipe Pose | realtime device-dependent | Primary tracking path. |
| Derived feature update | every valid pose frame | Angles, ROM, sway, confidence. |
| Layer 2 FSM | every valid pose frame or 10-15 Hz | Must be sub-ms class. |
| Recorder | event-triggered plus sampled debug | Never store raw frame by default. |
| E2B / Gemma | event or session trigger only | Never every frame. |
| TTS | cooldown >= 3 s | Avoid repeated cues. |

Layer 2 inputs are derived features only.

### 3.1 Derived feature dictionary

| Field | Unit / Range | Source | Required by | Notes |
| --- | --- | --- | --- | --- |
| `timestamp_ms` | int64 ms, monotonic | CameraX frame ts | all | Single clock; jitter > 100 ms must trigger `monitor_only`. |
| `activity_hint` | enum string | UI senior mode + heuristic | all | Maps via §2.1 normalizer; never trusted as ground truth. |
| `activity_confidence` | [0, 1] | heuristic classifier | all | Below 0.6 → activity hypothesis confidence capped. |
| `pose_confidence` | [0, 1] | MediaPipe Pose mean visibility (used joints) | all | < 0.55 → `abstain` `low_pose_confidence`. |
| `person_tracking_state` | enum | `PersonTrackingState` | all | Allowed: `OBSERVED`, `SINGLE_PERSON`, `AUTO_LOCKED`. |
| `judgment_allowed` | bool | `CapabilityContract` | all | False → `abstain` `judgment_not_allowed`. |
| `knee_angle_deg` | deg, [0, 180] | hip-knee-ankle 3-point angle | sit-to-stand, supported squat | Use higher-confidence side; both invisible → `monitor_only`. |
| `hip_y_norm` | [0, 1] image y | mid-hip landmark | sit-to-stand fallback | Fallback when `knee_angle_deg` 缺失；y 軸向下增加。 |
| `trunk_angle_deg` | deg, signed | shoulder-hip-vertical | all | + 為前傾；balance hold > 12° → `BALANCE_UNSTABLE` 候選。 |
| `knee_angular_velocity_deg_s` | deg/s, signed | finite diff over SG window | sit-to-stand, supported squat | Used for tempo banding only; not for safety triggers. |
| `hip_vertical_velocity` | norm/s, signed | hip_y_norm finite diff | sit-to-stand fallback | Sign convention: 正值 = 上升。 |
| `sway_norm` | norm length, [0, ∞) | COM proxy 1.5 s window stddev | balance hold | < 0.045 stable, > 0.10 unstable，中間 hysteresis。 |
| `base_of_support_width` | norm length | ankle 距離（normalized by hip width） | balance hold | < 0.6 → `narrow_base` flag。 |
| `support_contact_proxy` | bool | hand 高於 hip 且接近椅背/牆框 | supported squat | True 時 frontal knee valgus 永遠 `not_applicable`。 |
| `visibility_floor` | [0, 1] | min visibility（all required joints） | all | < 0.5 → `LOW_CONFIDENCE` per-metric。 |
| `multi_person_signal` | bool | subject gate 結果 | all | True → `monitor_only`，事件不計數。 |

不可送進 Layer 2 memory record 或 E2B packet：raw video frames、image crops、
raw landmark arrays（33×3）、color histograms、ReID embeddings、clinical
labels、biometric identity features、camera intrinsics 與裝置 ID。

## 4. P0 FSM Baseline

P0 is the deadline path. It is deterministic, inspectable, replayable, and
serves as both runtime fallback and weak-label generator.

### 4.0 Threshold table

集中表，所有 FSM 與 prototype 必須使用同一份常數來源（Kotlin `companion
object` 與 Python `Layer2Config` 必須一致）。

| Const | P0 default | Used by | Rationale |
| --- | ---: | --- | --- |
| `MIN_POSE_CONFIDENCE` | 0.55 | all | KINECAL 預訓練後雜訊邊界；< 0.55 假陽率不可控。 |
| `STANDING_KNEE_ANGLE_DEG` | 158 | sit-to-stand, squat | 站直容差 ±5°；保留 hysteresis 不誤判輕微膝彎。 |
| `LOW_KNEE_ANGLE_DEG` | 118 | sit-to-stand | 椅高 45 cm + 老年人 ROM 經驗值。 |
| `LOW_KNEE_ANGLE_DEG_SQUAT` | 118 | supported squat | 同 sit-to-stand；不要求深蹲。 |
| `MIN_REP_ROM_DEG` | 28 | sit-to-stand, squat | < 28° 視為半次或扶椅微調。 |
| `STABLE_TOP_FRAMES` | 2 | sit-to-stand, squat | 10 Hz cadence ≈ 200 ms 站穩。 |
| `ANGLE_DELTA_EPSILON_DEG` | 1.0 | rep phase | 防 jitter 翻轉 descending/rising。 |
| `STABLE_BALANCE_SWAY` | 0.045 | balance | 1.5 s 視窗內 COM proxy 標準差。 |
| `UNSTABLE_BALANCE_SWAY` | 0.10 | balance | 上界；中間 0.045–0.10 為 hysteresis 帶。 |
| `BALANCE_HOLD_TARGET_MS` | 5000 | balance | demo 預設；UI 可顯示更短進度條，事件門檻不變。 |
| `STANDING_HIP_Y` | 0.46 | sit-to-stand fallback | 當 knee 缺失時退回 hip_y_norm。 |
| `LOW_HIP_Y` | 0.58 | sit-to-stand fallback | 同上；pixel 8 pro 預設構圖。 |
| `JITTER_MAX_TIMESTAMP_GAP_MS` | 200 | all | 大於此值 → `monitor_only` 並重置 phase 視窗。 |
| `MULTI_PERSON_DEBOUNCE_FRAMES` | 5 | all | 連續 5 frame 多人才升級為 abstain。 |

任一常數變動需要：在此表更新、在 Kotlin/Python 兩邊同步，並在 `prototype/test_layer2_fsm.py`
新增測試覆蓋 boundary。`runtime config override` 只允許出現在 debug build。

### 4.1 Gate-first behavior

Every update starts with gates:

| Condition | Output |
| --- | --- |
| person lost | `abstain`, reason `person_not_observed` |
| multi-person ambiguity / wrong subject risk | `abstain` or `monitor_only` |
| `judgment_allowed=false` | `abstain`, reason `judgment_not_allowed` |
| pose confidence below threshold | `abstain`, reason `low_pose_confidence` |
| activity-specific feature missing | `monitor_only` |

The FSM must not guess through low-confidence frames. If tracking is blocked, it
should freeze or reset phase windows before counting any future event.

### 4.2 Chair sit-to-stand

State path:

```text
setup / standing_stabilized
-> descending        event: rep_started
-> seated_low
-> rising
-> standing_stabilized for N frames
-> rep_completed
```

Completion requires:

```text
seen_low == true
seen_rising == true
stable_top_frames >= 2
range_of_motion_deg >= 28
pose_confidence >= threshold
```

Half reps, small knee bends, missing knee angle, or unstable tracking must not
emit `rep_completed`.

### 4.3 Supported squat

State path:

```text
setup / standing_stabilized
-> descending        event: rep_started
-> squat_bottom
-> rising
-> standing_stabilized
-> rep_completed
```

Additional behavior:

- `support_contact_proxy=true` keeps activity confidence higher.
- If support obstructs frontal view, frontal knee valgus remains
  `not_applicable`.
- The activity can be judged for tempo, depth proxy, trunk lean, and ROM proxy
  only when evidence is sufficient.

### 4.4 Balance hold

State path:

```text
setup / standing_stabilized
-> balance_holding       event: balance_hold_started
-> balance_unstable      if sway_norm exceeds unstable threshold
-> balance_holding
-> balance_hold_completed if stable duration reaches target
```

P0 default target can be 5 seconds for demo. The UI may show shorter progress
markers, but Layer 2 should keep one canonical event threshold.

Balance output is a movement stability proxy only. It must not claim vestibular
assessment, clinical balance score, fall-risk score, or diagnosis.

### 4.5 Recorder

On `rep_completed` or `balance_hold_completed`, the app extracts the recent
2-5 seconds from a derived-feature ring buffer and records a compact JSONL row.

Recorder row family:

```json
{
  "schema_version": "layer2_sequence_v1",
  "sample_id": "session42_rep03",
  "activity": "chair_sit_to_stand",
  "feature_sequence": [],
  "labels": {
    "label_source": "fsm_weak_label",
    "activity_hypothesis": "chair_sit_to_stand",
    "phase_sequence": [],
    "events": [],
    "final_rep_count": 3,
    "final_hold_duration_ms": 0
  },
  "quality_gates": {
    "contains_raw_video": false,
    "contains_raw_landmarks": false,
    "requires_manual_review": true
  }
}
```

The recorder is for future Layer 2 training, not for E2B FC retraining. It must
reject raw-video, raw-frame, raw-landmark, crop, histogram, and embedding keys.

#### 4.5.1 File layout, rotation, retention

```text
{app_files}/layer2_recorder/
  YYYYMMDD/
    layer2_events_{session_id}.jsonl       # event-triggered rows
    layer2_monitor_{session_id}.jsonl      # debug sampled rows (debug build only)
    layer2_manifest_{session_id}.json      # per-session metadata
```

| Concern | Rule |
| --- | --- |
| Path | `Context.filesDir / layer2_recorder / YYYYMMDD`，App-private 沙箱，外部 app 無法讀取。 |
| Trigger row cadence | 只在 `rep_completed`、`balance_hold_completed`、`monitor_only` 邊界寫入；正常 `none` frame 不寫。 |
| Monitor sampled row cadence | 1 Hz，僅 debug build；release build 不啟用此檔案。 |
| Rotation | 單檔上限 5 MB 或 500 rows，先到者建新檔；同 session_id 加 `_part2`、`_part3`。 |
| Retention | 預設保留 7 日，超過自動刪除；export 後立即可選 wipe。 |
| Encryption | App-private 沙箱即可；不寫入 SD card。 |
| Manifest | 紀錄 schema_version、device model class（不含 IMEI）、senior mode 開關、retention policy、consent_ack_id。 |
| Export | 僅在使用者顯式同意時封裝 zip；export 之前再跑一次 forbidden-key 驗證。 |
| Wipe | Settings → 「清除動作紀錄」一鍵刪除整個 `layer2_recorder/` 目錄。 |

每個 session 結束需呼叫 `Layer2DatasetRecorder.flush()` 確認 fsync；崩潰
recovery 時 truncated row 由 reader 跳過並計入 manifest 的 `corrupt_row_count`。

## 5. P1 Tiny Temporal Model

P1 replaces or augments FSM phase estimation with a lightweight model while
keeping the same contract.

Recommended model:

```text
Input: [T=20-40, F=24-40] derived features
Cadence: 10 Hz
Window: 2-4 s
Backbone: tiny TCN or GRU
Hidden size: 32-64
Heads:
  activity_head
  phase_head
  event_head
  judgeability_head
Runtime: INT8 TFLite / LiteRT
```

Deployment rule:

```text
if gate blocked:
    output abstain
elif model confidence high and output is contract-valid:
    use model output
elif FSM output is available:
    use FSM fallback
else:
    monitor_only
```

The tiny model is allowed to smooth activity and phase estimates. It is not
allowed to override person tracking gates, confidence gates, unsupported
judgments, or medical boundaries.

### 5.1 Acceptance bars

P1 model 替換 FSM 的條件（必須 **全部** 達成；任何一項退步即不上線）：

| Metric | FSM baseline | P1 ship bar | Eval set |
| --- | --- | --- | --- |
| `rep_completed` precision (sit-to-stand) | ≥ 0.92 | ≥ FSM | hard-case 手工標註 + KINECAL 5×STS 子集 |
| `rep_completed` recall (sit-to-stand) | ≥ 0.85 | ≥ FSM + 0.05 | 同上 |
| Half-rep false-positive rate | ≤ 0.05 | ≤ FSM | hard-case「半次」子集 |
| `balance_hold_completed` precision | ≥ 0.95 | ≥ FSM | hard-case + KINECAL tandem stance |
| Abstain rate（normal flow） | ≤ 0.10 | ≤ FSM + 0.02 | replay session（清晰光線） |
| Abstain rate（low light / occlusion） | n/a | ≥ 0.60，且 0 false rep_completed | hard-case |
| Inference latency P95 | < 1 ms | < 5 ms（INT8 LiteRT） | Pixel 8 Pro 端側量測 |
| Model 檔案大小 | n/a | ≤ 1 MB | 部署成品 |

eval 流程：

```text
1. 從 layer2_events_*.jsonl 與 KINECAL 子集匯出固定 holdout split（不可重訓）。
2. FSM 與 P1 model 在同一 split 上跑 inference，輸出對齊到 schema_version。
3. 比對 rep_completed / balance_hold_completed timestamp 容差 ±300 ms。
4. 任何 abstain 升級或 hard-judgment 解鎖視為 regression，需人工審查。
5. 通過後在 docs/benchmark/ 下產出 layer2_p1_eval_{date}.md。
```

eval set 不可包含 raw video；只能含 derived feature sequence + 手工 phase
label。任何宣稱 clinical / fall-risk / sarcopenia 性能的數字一律不出現在
benchmark 文件內。

## 6. P2 Research Options

These are research inputs, not deadline blockers.

| Model family | Fit | Mobile risk | Recommended use |
| --- | --- | --- | --- |
| ST-GCN / STGCN++ / AGCN | Skeleton action recognition | Medium, graph ops and conversion risk | Offline benchmark or future backbone. |
| PoseC3D | Robust skeleton heatmap action recognition | Medium-high, heavier representation | Research comparison. |
| RepNet | Class-agnostic repetition counting | Medium-high, video-style pipeline | Rep-count benchmark, not safety loop. |
| MoViNet | Mobile RGB video action recognition | High for GemmaFit because it duplicates vision path | Avoid in realtime main loop. |
| Tiny TCN / GRU | Derived feature sequence model | Low | Preferred trainable Layer 2 path. |

The research direction is useful for writeup context, but P0 should not depend
on external pretrained skeleton action models.

## 7. Public Dataset Strategy

Public datasets should support pretraining, offline benchmarking, and taxonomy
validation. They should not define GemmaFit's product claims.

| Dataset | Useful labels / data | GemmaFit use | License / access |
| --- | --- | --- | --- |
| AHA-3D | Senior fitness exercises, 3D skeleton, frame-level labels | Activity and segmentation benchmark. | Academic; check authors' agreement before redistribution。 |
| KINECAL | 18-92 age range, Kinect V2, 5x sit-to-stand, standing, tandem, unilateral stance, TUG, 3m walk | Best fit for chair and balance pretraining or benchmark. | PhysioNet credentialed access；不得轉載原始檔。 |
| K3Da | Chair rise, balance, walking, TUG, noisy Kinect skeleton, elderly subjects | Robustness benchmark and noisy-frame handling. | Research-only；引用論文。 |
| ETRI-Activity3D / LivingLab | Elderly home ADL, RGB-D, 25-joint skeleton, 55 classes | `unknown`, `setup_transition`, non-exercise ADL boundary. | 申請 EULA；不得用於識別個人。 |
| KIMORE | Rehab movement, healthy and motor dysfunction groups, clinical scores | Movement-quality research only; do not expose clinical scores. | Academic；clinical scores 嚴禁進入 product output。 |
| UI-PRMD | Rehab movements, Kinect and Vicon, deep squat | Squat/ROM baseline, not senior-specific. | Open；署名引用即可。 |
| UTKinect-Action3D | Stand up, sit down, walk, skeleton | Small sanity baseline. | Research-only；署名引用。 |
| NTU RGB+D / PKU-MMD | Large action recognition skeleton datasets | Generic pretrain only, domain mismatch expected. | NTU RGB+D 需簽 EULA；商用受限。 |

Pretrain / fine-tune 規則：

- 任一 dataset 進入訓練前，需在 `docs/benchmark/dataset_usage.md` 紀錄 license、引用、資料切片與是否參與 P1 eval。
- 不允許把 dataset 內 clinical score、診斷標籤映射到 GemmaFit output。
- 不允許 redistribute 原始 raw frames / skeleton sequences；GemmaFit repo 只放 derived feature subset。
- 競賽 writeup 內任何 benchmark 數字要明示對應 dataset 名稱與 split，避免混淆 sources。

Training mix:

```text
public datasets:
  learn broad senior/rehab movement structure

GemmaFit FSM recorder:
  learn app-specific feature distribution and event contract

manual hard-case labels:
  validate edge cases and prevent unsafe false positives
```

Hard-case manual set should include: half reps, interrupted sit-to-stand,
support obstruction, person leaves frame, multiple persons, low light,
balance sway, camera angle changes, and non-exercise ADL.

## 8. Acceptance Criteria

P0 documentation and implementation are accepted when:

- Android and Python Layer 2 contracts use the same activity, phase, event, and
  abstain vocabulary for senior activities（§2 senior set；§2.1 migration 完成）。
- 同一份 threshold 表（§4.0）在 Kotlin `companion object` 與 Python
  `Layer2Config` 出現相同數值，差異由 unit test 失敗。
- Normal live frames do not trigger E2B（replay session E2B call count = 0）。
- `rep_completed` 與 `balance_hold_completed` 在 hard-case fixture 上 precision ≥ 0.92（sit-to-stand）/ 0.95（balance）。
- Missing evidence produces `monitor_only` 或 `abstain`，never hard coaching；hard-case「半次」fixture 上 false `rep_completed` rate ≤ 0.05。
- Recorder writes derived feature rows only；forbidden-key 驗證在 CI 跑過。
- Debug output exposes activity hypothesis、phase、event、confidence、
  judgeability、abstain reason、evidence refs（見 §11）。
- 任何宣稱 fall-risk、sarcopenia、clinical balance score 的字串不在 product output（grep CI 守門）。

Required tests:

| Scenario | Expected |
| --- | --- |
| clean chair sit-to-stand | `rep_started` then `rep_completed` |
| half squat / insufficient ROM | no `rep_completed` |
| low pose confidence | `abstain` |
| person lost | `abstain` and no hard coaching |
| supported squat | `squat_bottom`, `rising`, `rep_completed` |
| stable balance | `balance_hold_started`, then `balance_hold_completed` |
| unstable balance | `monitor_only`, no hard warning |
| normal `none` frames | no E2B call |
| event frame | scheduler may call E2B if policy allows |

Python prototype smoke:

```bash
python prototype/test_layer2_fsm.py
```

## 9. Implementation Order

P0 implementation order:

1. Treat this document as the Layer 2 contract.
2. Align Android `Layer2TemporalInterpreter` with senior-only taxonomy.
3. Add `balance_hold` FSM to Android.
4. Align Python and Android output vocabulary.
5. Add or reuse derived-feature ring buffer.
6. Emit `MotionFeatureWindow` only on Layer 2 events.
7. Connect event windows to recorder and `ModelInvocationScheduler`.
8. Add the tests listed above.
9. Use P0 outputs in demo and writeup.

P1 starts only after P0 is stable:

1. Export recorder rows.
2. Build a small manually reviewed validation set.
3. Train tiny temporal model offline.
4. Quantize to INT8.
5. Compare against FSM on false-positive event rate and abstain behavior（§5.1）。
6. Ship only if it improves stability without breaking safety gates。

### 9.1 Android migration plan

對齊 Android `Layer2TemporalInterpreter` 至 senior contract 的具體步驟。每一步
都應有對應 PR 與單元測試，避免一次性大改造成 regression。

| Step | Change | Test gate |
| --- | --- | --- |
| 1 | 新增 `Layer2Activity.SUPPORTED_SQUAT`、`BALANCE_HOLD`、`SETUP_TRANSITION` enum；補 wireName。 | enum→wireName round-trip test。 |
| 2 | `normalizeActivity` 將 `lunge`、`forward_lunge`、`basketball_*`、`jump_shot` 全部映射到 `UNKNOWN` 並回傳 `non_senior_label_demoted` debug flag。 | hint 字典 fixture 測試。 |
| 3 | 新增 `updateSupportedSquat`，沿用 `updateChairSitToStand` 的 rep state machine，但 `support_contact_proxy=true` 時 frontal valgus 永遠 `not_applicable`。 | 對齊 Python `_update_rep_activity` 同一 fixture 的 rep_completed timestamp。 |
| 4 | 新增 `updateBalanceHold` FSM（hysteresis、target ms、events）。 | 與 Python `test_balance_hold_started_and_completed` 同 fixture 結果一致。 |
| 5 | 新增 `updateSetupTransition`：phase 固定 `setup`、event 固定 `none`、不更新 rep counter。 | 過渡幀不會觸發 E2B（scheduler integration test）。 |
| 6 | 將 `updateLunge`、`updateBasketballJumpShot`、sport-composite sub-action 移到 `non_senior_demo` build flavor 或 `internal` package；main flavor 不引用。 | senior flavor build 不含 `basketball_jump_shot` symbol（aapt2 output grep）。 |
| 7 | `Layer2RulePolicy.unilateralMonitor` / `sportCompositeMonitorOnly` 移到同 flavor。 | senior flavor 編譯通過且不出現這些 policy。 |
| 8 | DebugReport 暴露 §11 fields。 | snapshot test 涵蓋所有 senior activity。 |
| 9 | `Layer2TemporalInterpreterTest` 對齊 Python `test_layer2_fsm.py` 的 fixture（同樣的 angle / sway 序列產出同樣 phase + event）。 | parity test 名稱前綴 `parity_*`。 |

完成 1–9 後，doc §2 senior-only taxonomy 才能視為 Android 全面執行；在這之前
release notes 必須標示 `senior-mode-experimental`。

## 10. Privacy & Senior Cohort Considerations

長者是高度敏感的使用者群體；以下規則是硬限制，不是建議。

| Rule | 說明 |
| --- | --- |
| On-device only | Pose、Layer 2 derived features、recorder rows 一律不離開裝置；caregiver export 為使用者主動觸發的「分享」動作，不是自動同步。 |
| Forbidden fields | 不收集年齡、體重、診斷、用藥、跌倒史、住址、姓名、生日、IMEI、廣告 ID。Onboarding 只記錄「練習目標時長」「是否使用扶椅」這類功能性偏好。 |
| Consent surface | 首次啟用 senior mode 必須出現 plain-language consent dialog（Android 中文 / 英文皆可），明示「動作紀錄僅用於改善動作判斷，不做醫療判讀」；接受後產生 `consent_ack_id` 寫入 manifest（§4.5.1）。 |
| Refusal language | 任何醫療、跌倒風險、肌少症、復健進展、藥物建議的問題，scheduler 走 deterministic refusal 或 E2B refusal tool；Layer 2 不可暗示這些結論。 |
| Caregiver share | 若使用者開啟 caregiver 報表，匯出包僅含彙整指標（總次數、ROM 平均、abstain ratio），不含 raw feature sequence。 |
| Right to wipe | Settings 提供一鍵刪除（§4.5.1 wipe）；按下後同 session 下游 cache、context compiler buffer 也必須清空。 |
| Vulnerable cohort copywriting | 所有 UI 用「動作品質回饋」與「請慢慢做」語氣；禁止「警告」「危險」「異常」這類醫療化字眼出現在 senior flavor。 |
| Multi-person scene | 偵測到第二人連續 ≥ 5 frame 即進 `monitor_only`，不對任一人下判斷（避免錯把照護者當成練習者）。 |

privacy 違反等同 P0 blocker；CI 應有 grep 守門：拒絕在 senior flavor 出現
`fall_risk`、`sarcopenia`、`diagnosis`、`force_plate`、`emg`、`age`、`bmi`、
`medication` 字串。

### 10.1 Dementia-Friendly Self-Guided Boundary

P0 可新增 `DementiaFriendlySelfGuided` senior mode，但 Layer 2 的角色不變：
它只輸出 activity、phase、event、judgeability、abstain reason 與 evidence refs。
它不判斷失智、混亂、走失、認知退化、照護等級或跌倒風險。

新增互動層 `SeniorInteractionPolicy` 應放在 Layer 2 下游：

```text
Layer2Output + PersonTrackingState + user-response timing
-> SeniorInteractionPolicy
-> deterministic UI/TTS pause/repeat/end decision
```

可觀察 support states：

| State | Source | Layer 2 relation |
| --- | --- | --- |
| `READY` | person tracked + evidence judgeable | Layer 2 可繼續輸出 events。 |
| `SETUP_NEEDED` | low confidence / view limited / blocked judgment | 對應 `monitor_only` 或 `abstain`；不產生 hard coaching。 |
| `USER_LEFT_ACTIVITY_AREA` | `PersonTrackingState.NO_PERSON` 或 `LOST` | Layer 2 `abstain`，rep/hold 不計數。 |
| `NO_RESPONSE_AFTER_CUE` | UI/gesture timing | 不是 motion label；不可回寫成 cognitive evidence。 |
| `MULTI_PERSON_AMBIGUOUS` | subject gate | Layer 2 `abstain`，不做 ReID。 |
| `SESSION_PAUSED_FOR_SUPPORT` | interaction policy | deterministic pause state，不送模型做判斷。 |

在此模式下，Dual-task 預設關閉。若後續開啟，只能使用 gesture-first、低衝擊、
無認知分數的 bounded prompts；結果只能記錄 answer matched / movement completed，
不可輸出 dementia screening、cognitive score、wandering risk 或 clinical interpretation。

Recorder / memory 只能保存 compact support counts：

- low-confidence pause count
- user-left-activity-area pause count
- no-response pause count
- multi-person ambiguity count
- effective cue key count

不得保存或推論 dementia severity、cognitive decline、wandering risk、diagnosis、
medication、address、raw video、raw landmarks、ReID embeddings。

## 11. Observability & Debug Surfaces

debug build 在 `Settings → Developer → Layer2 Debug` 暴露下列即時欄位，
release build 一律不顯示。

| Field | Source | Purpose |
| --- | --- | --- |
| `activity_hypothesis.{label, confidence}` | Layer2Output | 確認 hint 與 normalize 結果一致。 |
| `phase` + `event` + `rep_count` + `hold_duration_ms` | Layer2Output | 直觀觀察 FSM 行為。 |
| `judgeability` + `abstain_reason` | Layer2Output | 看 gate 為何阻擋。 |
| `non_senior_label_demoted` | normalizer flag | 抓非 senior hint 流入。 |
| `evidence_refs` | Layer2Output | 對齊 E2B packet 引用。 |
| `pose_confidence` 直方圖（最近 5 s） | derived feature pipeline | 看 tracking 是否邊界值徘徊。 |
| `multi_person_signal` 連續計數 | subject gate | 觀察 debounce。 |
| `recorder.event_rows_in_session` / `recorder.dropped_forbidden_rows` | Layer2DatasetRecorder | 確認 forbidden-key 攔截有效。 |
| `scheduler.last_decision` + `scheduler.last_blocked_by` | ModelInvocationScheduler | 看為何沒呼叫 E2B（§model_invocation_scheduler.md）。 |
| `threshold_overrides` | runtime debug-only config | 標示 `4.0` 之外的偏離值。 |

`DebugReportProvider` 應把以上欄位納入 `gemmafit_debug_report.json` 的
`layer2` 區塊；release build 該區塊空白。Pixel smoke run 結束時印出最近一輪
session 的事件統計（rep_completed count、abstain rate、scheduler skip count），
不印逐幀內容。

## 12. Failure Modes & Known Limitations

| 情境 | 觀察徵兆 | 期望 Layer 2 行為 | 已知 limit |
| --- | --- | --- | --- |
| 椅背遮擋臀/膝 | `pose_confidence` 80%+ 但 knee landmark visibility 低 | `monitor_only` `missing_knee_angle`；fallback 到 `hip_y_norm`。 | hip_y 在 sit-to-stand 仍可粗判，但 ROM 計算偏差，不出 hard-judgment。 |
| 扶椅手遮 frontal 視角 | `support_contact_proxy=true` 且雙膝 IoU 低 | `supported_squat` 啟動，`knee_valgus_fppa` 永遠 `not_applicable`。 | 無法評估 frontal alignment；只給 tempo、ROM proxy。 |
| 照護者進出鏡頭 | `multi_person_signal=true` ≥ 5 frame | `abstain` `multi_person_ambiguity`；事件不計數。 | 無法 ReID 區分主體；P1 不嘗試 ReID（隱私限制）。 |
| 低光 / 逆光 | `visibility_floor` < 0.5 持續 > 1 s | `monitor_only` 並提示「請補光」 UI。 | 不嘗試影像增強；交還使用者調整環境。 |
| 椅子高度極低（< 35 cm） | knee_angle 永遠 < 100° | sit-to-stand 仍可運作但 `LOW_KNEE_ANGLE_DEG` boundary 觸發頻率上升 | 不對椅子高度做幾何標定；ROM 解讀偏保守。 |
| Camera 抖動 / 換手 | `timestamp_gap > JITTER_MAX_TIMESTAMP_GAP_MS` | `monitor_only` 並重置 phase 視窗。 | 不做光流穩定，需使用者重置構圖。 |
| 半次 sit-to-stand（坐回未站直） | `seen_low=true`、`stable_top_frames < 2` | 不發 `rep_completed`；連續多次半次 → UI 提示「再起身一點」（不是 warning）。 | 連續 3 次半次後再不計入計數，避免 UX 卡關。 |
| 平衡測試突然走動 | `sway_norm` 跳動超過 unstable 閾值 | `BALANCE_UNSTABLE` → `monitor_only`；計時歸零。 | 不嘗試判斷「跌倒風險」，僅紀錄不穩定事件數量。 |
| 使用者中途退出 senior mode | activity_hint 變成 `unknown` | 立即 reset rep / hold 視窗，避免跨模式累計。 | recorder 該 session 標記 `mode_switch_mid_session`。 |
| 高速幀率 60 fps（裝置不限） | Layer2 cadence 高於 15 Hz | 內部 downsample 至 10–15 Hz；FSM 不依賴實際 fps。 | 不對所有裝置保證 60 fps preview。 |

任何新觀察到的 failure mode 應補到此表並對應 hard-case fixture，否則不能宣稱
P0 「stable」。

## 13. Glossary of Derived Features

對齊 §3.1 dictionary 的精確定義。修改任一定義需同步 Kotlin 計算碼、Python
prototype 與 §4.0 threshold rationale。

| 名詞 | 精確定義 |
| --- | --- |
| `knee_angle_deg` | hip-knee-ankle 三點夾角，內側為正向；同側 visibility 低於 0.5 即視為缺失，再退回對側；雙側皆缺則 `monitor_only`。 |
| `hip_y_norm` | mid-hip landmark 影像 y 座標 normalized 到 [0, 1]，y 軸向下為正；座標基準為 preview 影像非 sensor。 |
| `trunk_angle_deg` | mid-shoulder → mid-hip 向量與「重力垂直線」夾角，前傾為 +、後仰為 -；重力方向使用 device IMU 校正後的影像 y 軸。 |
| `knee_angular_velocity_deg_s` | `knee_angle_deg` 經 5-點 Savitzky-Golay 平滑後的中心差分，clip 到 [-1500, 1500] 防 outlier。 |
| `hip_vertical_velocity` | `hip_y_norm` 同樣 SG 平滑後的中心差分，正值代表向上移動（image y 減小）。 |
| `sway_norm` | 過去 1.5 s 視窗內 mid-hip 與雙踝中點水平位移的標準差，除以 hip-width；視窗不足 1.0 s → `monitor_only`。 |
| `base_of_support_width` | 雙踝水平距離除以 hip-width；< 0.6 為窄基底，> 1.6 為寬基底。 |
| `support_contact_proxy` | 任一手部 landmark 高於 hip 且 x 座標位於畫面 ROI 邊框內（前側椅背 / 牆框 ROI 由 onboarding 標定，可 fallback 到「手在 hip 上方且穩定 > 0.5 s」）。 |
| `visibility_floor` | 對該活動 required joint set 取 visibility 最小值；required set 由 §3.1 必填欄位定義。 |
| `pose_confidence` | required joint set visibility 平均；MediaPipe 提供 0–1。 |
| `multi_person_signal` | subject gate 偵測到第二個 person bbox 連續 ≥ `MULTI_PERSON_DEBOUNCE_FRAMES`；單幀干擾不觸發。 |
| `activity_hint` | UI senior mode 選擇 + heuristic classifier 輸出；只是「建議」，Layer 2 永遠透過 §2.1 normalizer 二次過濾。 |

## 14. References

- AHA-3D dataset: https://vislab.isr.tecnico.ulisboa.pt/datasets_and_resources/
- KINECAL dataset: https://physionet.org/content/kinecal/1.0.0/
- K3Da dataset: https://filestore.leightley.com/k3da/k3da_access_data.html
- ETRI-Activity3D-LivingLab: https://ai4robot.github.io/etri-activity3d-livinglab-en/
- KIMORE dataset: https://vrai.dii.univpm.it/content/kimore-dataset
- UI-PRMD dataset paper: https://pmc.ncbi.nlm.nih.gov/articles/PMC5773117/
- UTKinect-Action3D: https://cvrc.ece.utexas.edu/KinectDatasets/HOJ3D.html
- NTU RGB+D: https://arxiv.org/abs/1604.02808
- PKU-MMD: https://arxiv.org/abs/1703.07475

---

## 15. Layer 2 v2: Motion Evidence Compiler

### 15.0 Why v2 — architectural reframing

v1 把 Layer 2 描述成「time-axis interpreter」。v2 把它精確化成
「**可驗證 motion evidence compiler**」 — 它的工作不是「猜這是什麼動作」，
而是回答：

```text
在哪個時間窗
基於哪些 deterministic feature
滿足哪些 transition precondition
因此可以產生哪個 bounded event
以及哪些 claim type 不允許成立
```

這個 reframing 解決 v1 三個實作風險：
- 單一 `judgeability` 欄位混淆了 activity / phase / rep_count / quality / medical
  五種不同的可判斷性
- 單一 `phase` 欄位無法表達「正在 transition 但尚未 commit」的中間態，導致
  抖動（rising → standing → rising → standing）
- `evidence_refs` 只是 label，下游無法 audit「為什麼這個 ref 支持這個結論」

v2 的所有 enhancement（§15.1-§15.18）都圍繞這三個目標。

### 15.1 7-submodule decomposition

v1 將 Layer 2 描述成單一 `Layer2TemporalInterpreter`。v2 拆成 7 個責任明確
的子模組，避免變成巨大 if-else 檔。

| 子模組 | 責任 | 對應 v1 |
|---|---|---|
| `InputGate` | person lost / low confidence / multi-person / visibility floor / judgeability gate | §4.1 |
| `FeatureNormalizer` | 座標方向統一、body-scale normalization、單位統一 | §13 |
| `TemporalSmoother` | EMA / median filter / outlier rejection / frame drop 補償 | §3.1 (`hip_vertical_velocity` SG smoothing) |
| `ActivityContextTracker` | chair_sts / supported_squat / balance_hold 的長期 activity lock | §2.1 |
| `PhaseFSM` | 每個 activity 的 phase transition | §4.2-§4.4 |
| `EventEmitter` | rep_started / rep_completed / abstain / monitor_only | §1 (`event`), §4 |
| `EvidenceBuilder` (含 MotionZipWriter) | evidence_refs / numeric support / counter-evidence / MotionZip packet | §1 (`evidence_refs`), §4.5 |

Kotlin 實作建議：每個子模組是 `internal class`，主 entry point `updateLayer2()`
按固定順序呼叫（見 §15.16 update loop pseudocode）。

### 15.2 Granular judgeability（取代 §1 單一字串）

> Demo P0 scope: runtime only exposes the compact string/debug view. Do not
> send the full 5-claim judgeability object to E2B. For the Kaggle demo, only
> track the reduced claims `activity_recognizable` and
> `movement_quality_judgeable` in debug/internal logic; keep other claim types
> out of the prompt to avoid insurance-style summaries.

**v1 schema**：
```json
"judgeability": "judgeable"
```

**v2 schema**：
```json
"judgeability": {
  "activity": "judgeable",
  "phase": "judgeable",
  "rep_count": "judgeable",
  "movement_quality": "not_judgeable",
  "medical_or_safety": "not_allowed"
}
```

每個 claim type 三態：`judgeable` / `monitor_only` / `not_judgeable` /
`not_allowed`。`not_allowed` 是 hard rule（醫療、跌倒、力量永遠 not_allowed），
其他三態由 evidence + gate 動態決定。

**對下游的意義**：E2B 拿到 `movement_quality: "not_judgeable"` 就不能寫
「動作品質良好」，只能寫「系統觀察到一段可能的站起階段」。

Migration：v1 callers 暫時讀 `judgeability.activity` 維持單一字串行為；
逐步遷移到讀對應的 claim type。

### 15.3 Candidate vs committed phase（取代 §1 單一 `phase`）

**v1**：`phase: "rising"`

**v2**：
```json
{
  "phase": {
    "committed": "seated_low",
    "candidate": "rising",
    "candidate_confidence": 0.68,
    "commit_ready": false
  }
}
```

Commit 規則需同時滿足：
- 連續 ≥ N frame 同 candidate（建議 N=3 at 10 Hz ≈ 300ms）
- 或持續 ≥ 180ms
- 或 evidence score ≥ 0.75
- 且 underlying gate（pose_confidence、tracking）持續通過

Commit 之後才更新 `committed`，candidate 清空。下游（EventEmitter、UI、TTS）
只看 `committed`，不看 candidate。

**這解 v1 的 phase 抖動問題** — UI 在 candidate 階段顯示 deterministic
holding 文字，不會被低 confidence frame 拉著跳。

### 15.4 Three-tier abstain（細化 §4.1 gate-first）

**v1**：`abstain` 是單一 outcome。

**v2** 拆三層：

| Tier | 觸發 | FSM 行為 | E2B 行為 |
|---|---|---|---|
| `hard_abstain` | multi-person ≥ debounce、subject lost > 800ms、judgment_not_allowed | 可能 reset 或 abort 進行中的 rep | 完全不呼叫 |
| `soft_hold` | 短暫低 confidence（< 500ms）、局部遮擋、frame drop < jitter limit | **凍結 phase**，不 reset；不 commit 新 candidate | 不呼叫，但保留上下文 |
| `monitor_only` | 追蹤可用但 evidence 不足以下事件判斷 | 持續更新 candidate；不發 event | 不呼叫 |

**重點**：不要因為 1 frame 低 confidence 就把進行中的 rep 砍掉。lost 累積
超過 abort threshold（建議 800-1200ms）才升級成 `hard_abstain` +
`rep_aborted`。

對應 Kotlin schema：
```kotlin
sealed class JudgeabilityGate {
    object Pass : JudgeabilityGate()
    data class SoftHold(val reason: String, val ageMs: Long) : JudgeabilityGate()
    data class MonitorOnly(val reason: String) : JudgeabilityGate()
    data class HardAbstain(val reason: String, val shouldAbortRep: Boolean) : JudgeabilityGate()
}
```

### 15.5 ActivityContextTracker as slow-varying scorer

**v1** 在 §2.1 提到 activity 由 hint 決定。**v2** 引入「慢變量 score」，
避免每幀硬切 label。

```json
{
  "activity": {
    "hypothesis_scores": {
      "chair_sit_to_stand": 0.82,
      "supported_squat": 0.31,
      "balance_hold": 0.05,
      "setup_transition": 0.14
    },
    "locked_label": "chair_sit_to_stand",
    "lock_confidence": 0.82
  }
}
```

**Lock 規則**：score > 0.75 持續 1-2 秒 OR 完成一個 valid rep OR 連續 M
個 phase transition 符合 template。

**Unlock 規則**：locked activity 的 evidence score 連續下降 OR 出現長時間
incompatible pattern OR `hard_abstain`。

**States**（取代 v1 §2 直接 label）：
```text
UNKNOWN          - session 開始
CALIBRATING      - 第 1 rep 完成，scoring 多模板中
LOCKED(template) - N consecutive rep 同模板 + score > threshold
SUSPECT_SWITCH   - LOCKED 後出現 1 rep 矛盾
AMBIGUOUS        - 多模板 score 接近，無法 lock → 走 generic cue
```

**AMBIGUOUS 是 trust card** — 系統明確說「動作模式不明確，使用通用控制
提示」，比錯誤 lock 到 squat 然後給「下到 90°」的建議好。

**Implementation status (2026-05-16)**：Android 已新增
`ActivityContextTracker`，目前是 event-level scorer，輸入
`Layer2Output + MotionFeatureWindow` 的 compact features，不讀 raw video 或 raw
landmarks。輸出 `ActivityContext(state, taskLabel, confidence,
templateScores, evidenceRefs)`，並寫入 MotionZip packet 的
`activity_context`。Unit tests 覆蓋：

- 連續 chair sit-to-stand event 後 lock `chair_sit_to_stand`
- 連續 supported squat event 後 lock `supported_squat`
- chair-vs-squat score 接近時輸出 `AMBIGUOUS` 且 `taskLabel = null`
- MotionZip packet 攜帶 `activity_context`

### 15.6 Event cooldown + deduplication

**v1** 沒對 event 重複觸發做明確處理。**v2** 每個 event 帶 ID + cooldown：

```json
{
  "event": {
    "type": "rep_completed",
    "event_id": "evt_2026_00031",
    "rep_id": "rep_003",
    "emitted_once": true,
    "cooldown_ms": 1200,
    "preconditions_met": [...]
  }
}
```

EventEmitter 維護 `emittedEventIds: Set<String>` + `lastEventTimeByType: Map`。
同一個 `event_id` 不重複 emit；同 type event 之間至少 cooldown_ms。

**對 `rep_completed` 特別重要**：standing_stabilized 持續多幀，沒 cooldown
會連噴。

### 15.7 Per-rep precondition bitset（細化 §4.2 completion）

**v1** §4.2 列了 completion 條件（seen_low / seen_rising / stable_top_frames 等）
作為 boolean check。**v2** 把它變成 explicit bitset，方便 debug + audit：

```json
{
  "rep_attempt_id": "rep_003",
  "preconditions": {
    "saw_seated_low": true,
    "saw_rising": true,
    "returned_to_standing_stabilized": true,
    "rom_sufficient": true,
    "confidence_sufficient": true,
    "tracking_continuous": true,
    "multi_person_clear": true
  },
  "all_met": true
}
```

只有 `all_met == true` 才 emit `rep_completed`。Bitset 寫進 debug report 與
recorder row，可以直接 grep 出「為什麼這個 rep 沒被 count」。

### 15.8 MotionFeatureWindow as keyframes + extrema

**v1** §4.5 recorder 寫 `feature_sequence`（per-frame 序列）。對 E2B 太肥。
**v2** product runtime 只給 E2B keyframes + extrema：

```json
{
  "motion_zip_version": "0.2",
  "activity": "chair_sit_to_stand",
  "rep_id": "rep_003",
  "event": "rep_completed",
  "phase_sequence": [
    "standing_stabilized", "descending", "seated_low",
    "rising", "standing_stabilized"
  ],
  "duration_ms": 2840,
  "keyframes": {
    "rep_started_ms": 12030,
    "seated_low_ms": 13180,
    "rising_started_ms": 13520,
    "rep_completed_ms": 14870
  },
  "extrema": {
    "min_knee_angle_deg": 82.4,
    "max_knee_angle_deg": 166.1,
    "hip_height_rom_norm": 0.31,
    "max_trunk_angle_deg": 28.6,
    "max_sway_norm": 0.08
  },
  "confidence": {
    "pose_confidence_floor": 0.71,
    "visibility_floor": 0.68,
    "tracking_continuity": "continuous"
  }
}
```

Recorder（§4.5）仍存完整 `feature_sequence` 給 future training；E2B 只看
MotionZip。兩者目的不同。

### 15.9 Evidence refs upgraded to verifiable references

> Demo P0 scope: keep prompt-facing `evidence_refs` as a flat string array.
> Value / threshold / time_range fields belong in Layer 2 internal debug logs
> and replay artifacts only. They should not be included in the E2B session
> summary packet because the model only needs stable ref labels.

**v1**：
```json
"evidence_refs": ["layer2.activity.chair_sit_to_stand", "layer2.phase.rising"]
```

**v2** — 每個 ref 帶 value + threshold + time range：

```json
"evidence": [
  {
    "ref": "layer2.phase.rising",
    "supports": "phase:rising",
    "metric": "hip_height_velocity_norm_s",
    "value": 0.42,
    "threshold": "> 0.18",
    "time_range_ms": [13520, 14100]
  },
  {
    "ref": "metric.pose_confidence_floor",
    "supports": "judgeability.rep_count",
    "metric": "pose_confidence",
    "value": 0.71,
    "threshold": ">= 0.65",
    "time_range_ms": [12030, 14870]
  }
]
```

讓 E2B 寫的 summary 可被 audit — 每個結論都能追溯到 deterministic 數值與
threshold。

### 15.10 Counter-evidence（新概念）

> Demo P0 scope: do not send counter-evidence to E2B. Use deterministic
> Android-side disclaimer templates for cannot-judge wording instead. This
> prevents the model from turning internal limits into repetitive output such
> as "movement quality cannot be assessed" when the demo needs a concise coach
> summary.

v1 沒有此概念。v2 引入，避免 E2B 過度延伸 `rep_completed` 成「動作品質良好」：

```json
"counter_evidence": [
  {
    "ref": "metric.left_knee_visibility_low",
    "metric": "left_knee_visibility",
    "value": 0.38,
    "effect": "movement_quality_not_judgeable"
  }
]
```

**用途**：跟 §15.2 granular judgeability 配對。counter_evidence 解釋為什麼
某個 claim type 是 `not_judgeable`。

E2B 看到 counter_evidence 就知道哪些話不能說，輸出會變成：
> 「完成了一次可能的 sit-to-stand。左膝 visibility 不足，無法評估膝控制。」

### 15.11 Calibration / baseline（新概念）

v1 §4.0 thresholds 是固定值。長者身高、椅子高度、鏡頭角度差異大，固定值
不穩。v2 引入 per-session calibration：

```json
{
  "calibration": {
    "standing_hip_height_norm": 0.73,
    "seated_hip_height_norm": 0.42,
    "body_scale_confidence": 0.81,
    "calibrated_at_ms": 8420
  }
}
```

**取得方式**：
- `standing_hip_height_norm`：first stable standing window 取 hip 平均高度
- `seated_hip_height_norm`：first observed seated_low 取 hip 平均高度
- `body_scale_confidence`：取得樣本足夠 → 高；不足 → fallback 用固定 threshold

ROM 判斷改用 normalized：`hip_height_rom_norm = (standing - seated) /
body_scale`，不再用絕對 hip_y。

### 15.12 Hip height naming convention（取代 §3.1 `hip_y_norm`）

**v1** 用 `hip_y_norm`，y 軸向下為正，容易誤判。**v2** Layer 2 內部統一成
`hip_height_norm`：越大 = 越高，越小 = 越低。

`hip_height_norm = 1.0 - hip_y_norm`（或更精確的 body-scale normalized
version per §15.11）。

對應 v1 `STANDING_HIP_Y = 0.46` → v2 `STANDING_HIP_HEIGHT_NORM = 0.54`。
所有 sit-to-stand FSM transition 改用 hip_height_norm 比較，方向 intuitive。

### 15.13 `chair_sts_candidate` intermediate state

**v1** §2 直接 label。**v2** 加 candidate 中間態，對 P0 demo 更穩：

```text
setup_transition
→ chair_sts_candidate    (rep 1 partial pattern match)
→ chair_sit_to_stand     (locked after rep 2 with score > 0.75)
→ rep_completed
```

candidate 期間 UI 顯示「準備中」，不顯示 rep counter；lock 後才開始顯示。

### 15.14 `supported_squat` monitor-only safety stance

**v1** §4.3 將 supported_squat 視為 judgeable。**v2** 提高警覺：
`support_contact_proxy` 是 heuristic，沒有 object detection 不應該強行說
「使用者扶著椅子」。

**v2 P0 行為**：
```json
{
  "activity_hypothesis": "supported_squat",
  "support_status": "support_unknown",
  "judgeability": {
    "activity": "monitor_only",
    "support_contact": "not_judgeable",
    "rep_count": "monitor_only"
  }
}
```

P0 demo 主路徑用 chair_sts 即可；supported_squat 留 monitor-only，等
ActivityContextTracker score-based lock 機制更熟之後再升級。

### 15.15 `balance_hold` rolling window

**v1** §4.4 用單幀 sway 觸發。**v2** 強制 rolling window，避免單幀抖動：

```json
{
  "activity": "balance_hold",
  "phase": "holding",
  "window_stats": {
    "duration_ms": 5000,
    "sway_norm_p95": 0.06,
    "tracking_lost_ratio": 0.0,
    "pose_confidence_floor": 0.74
  }
}
```

Events 從 v1 `none → started → completed` 細化成：
```text
started → holding → completed
              ↘
                aborted (sway window exceeded or tracking lost)
```

Phase 切換用 rolling window p95，不用單幀峰值。

### 15.16 v2 output schema (consolidated)

```json
{
  "layer2_version": "0.2",
  "timestamp_ms": 14870,
  "tracking": {
    "person_tracking_state": "tracked",
    "pose_confidence": 0.78,
    "visibility_floor": 0.68,
    "multi_person_signal": false
  },
  "activity": {
    "locked_label": "chair_sit_to_stand",
    "hypothesis_scores": {
      "chair_sit_to_stand": 0.84,
      "supported_squat": 0.27,
      "balance_hold": 0.05,
      "setup_transition": 0.11,
      "unknown": 0.02
    },
    "lock_state": "LOCKED",
    "lock_confidence": 0.84
  },
  "phase": {
    "committed": "standing_stabilized",
    "candidate": null,
    "confidence": 0.81
  },
  "event": {
    "type": "rep_completed",
    "event_id": "evt_2026_00031",
    "rep_id": "rep_003",
    "confidence": 0.83,
    "preconditions": {
      "saw_seated_low": true,
      "saw_rising": true,
      "returned_to_standing_stabilized": true,
      "rom_sufficient": true,
      "confidence_sufficient": true,
      "tracking_continuous": true,
      "multi_person_clear": true
    },
    "all_preconditions_met": true,
    "cooldown_ms_until_next": 1200
  },
  "judgeability": {
    "activity": "judgeable",
    "phase": "judgeable",
    "rep_count": "judgeable",
    "movement_quality": "not_judgeable",
    "medical_or_safety": "not_allowed"
  },
  "abstain": null,
  "calibration": {
    "standing_hip_height_norm": 0.73,
    "seated_hip_height_norm": 0.42,
    "body_scale_confidence": 0.81
  },
  "evidence": [
    {
      "ref": "layer2.event.rep_completed",
      "supports": "event:rep_completed",
      "metric": "phase_sequence",
      "value": ["standing_stabilized", "descending", "seated_low",
                "rising", "standing_stabilized"]
    },
    {
      "ref": "metric.hip_height_rom_norm",
      "supports": "rom_sufficient",
      "value": 0.31,
      "threshold": ">= 0.22"
    },
    {
      "ref": "metric.pose_confidence_floor",
      "supports": "confidence_sufficient",
      "value": 0.71,
      "threshold": ">= 0.65"
    }
  ],
  "counter_evidence": [
    {
      "ref": "metric.left_knee_visibility_low",
      "metric": "left_knee_visibility",
      "value": 0.44,
      "effect": "movement_quality_not_judgeable"
    }
  ]
}
```

### 15.17 v2 update loop pseudocode

```kotlin
fun updateLayer2(frame: DerivedMotionFeatures): Layer2State {
    val gate = inputGate.evaluate(frame)

    if (gate is HardAbstain) {
        val aborted = phaseFsm.abortIfNeeded(gate.reason)
        return eventEmitter.emitAbstainOrAbort(frame.timestampMs, gate, aborted)
    }

    val normalized = featureNormalizer.normalize(frame)            // §15.12
    val smoothed = temporalSmoother.update(normalized)             // §15.1

    if (gate is SoftHold) {
        return phaseFsm.holdPreviousState(                         // §15.4
            timestampMs = frame.timestampMs,
            reason = gate.reason
        )
    }

    calibration.maybeUpdate(smoothed)                              // §15.11
    val activityCtx = activityTracker.update(smoothed)             // §15.5
    val phaseUpdate = phaseFsm.step(                               // §15.3
        features = smoothed,
        activity = activityCtx.lockedActivity,
        calibration = calibration.snapshot,
    )
    val event = eventEmitter.maybeEmit(                            // §15.6
        phaseUpdate = phaseUpdate,
        activityContext = activityCtx,
        features = smoothed,
        preconditions = phaseFsm.preconditionBitset(),             // §15.7
    )
    val evidence = evidenceBuilder.build(                          // §15.9
        features = smoothed,
        activityContext = activityCtx,
        phaseUpdate = phaseUpdate,
        event = event,
        calibration = calibration.snapshot,
    )
    val counterEvidence = evidenceBuilder.buildCounterEvidence(    // §15.10
        features = smoothed,
        gate = gate,
    )
    val judgeability = evidenceBuilder.deriveJudgeability(         // §15.2
        evidence = evidence,
        counterEvidence = counterEvidence,
        gate = gate,
        activityContext = activityCtx,
    )

    if (event != null) {
        motionZipWriter.recordEvent(                               // §15.8
            features = smoothed,
            phaseUpdate = phaseUpdate,
            event = event,
            evidence = evidence,
            counterEvidence = counterEvidence,
        )
    }

    return Layer2State(
        tracking = gate.trackingSummary,
        activity = activityCtx,
        phase = phaseUpdate.phase,
        event = event,
        judgeability = judgeability,
        calibration = calibration.snapshot,
        evidence = evidence,
        counterEvidence = counterEvidence,
    )
}
```

### 15.18 v2 P0 implementation order

依 ROI + 風險 ordered（單獨 PR，每步要有 test gate）：

| # | Step | 對應 §15 | 工程估時 |
|---|---|---|---|
| 1 | Gate-first three-tier abstain (hard / soft / monitor) | §15.4 | 3-4 hr |
| 2 | `hip_height_norm` + body-scale calibration | §15.11, §15.12 | 4-6 hr |
| 3 | Chair sit-to-stand FSM with hysteresis + candidate/committed | §15.3 | 4-6 hr |
| 4 | Per-rep precondition bitset | §15.7 | 2-3 hr |
| 5 | Event cooldown + dedup with event_id | §15.6 | 2-3 hr |
| 6 | MotionZip keyframes + extrema schema | §15.8 | 3-4 hr |
| 7 | Granular judgeability + counter_evidence | §15.2, §15.10 | 4-6 hr |
| 8 | Evidence refs with value + threshold + time_range | §15.9 | 3-4 hr |
| 9 | ActivityContextTracker with hysteresis score | §15.5 | 6-8 hr |
| 10 | Debug report v2 + golden replay test framework | §15.19 | 4-6 hr |

**先不做**：
- 通用動作分類
- 複雜 RNN/TCN（保留 §5 P1 baseline）
- LLM-based phase detection
- supported_squat hard judgment
- 每 rep 都叫 E2B

### 15.19 v2 evaluation metrics

Layer 2 質量不能只看 demo。Required metrics（先固定，不要隨意改）：

| Metric | Target | Eval set |
|---|---|---|
| `rep_completed` precision (chair_sts) | ≥ 0.92 | hard-case fixture + KINECAL 5×STS subset |
| `rep_completed` recall (chair_sts) | ≥ 0.85 | 同上 |
| `rep_completed` false-positive rate（半次 / 抖動）| ≤ 0.05 | hard-case「半次」subset |
| `balance_hold_completed` precision | ≥ 0.95 | hard-case + KINECAL tandem |
| Abstain correctness（hard_abstain 該觸發時觸發）| ≥ 0.95 | hard-case occlusion / multi-person |
| Judgeability false-positive rate（不該 judgeable 卻 judgeable）| ≤ 0.02 | 全 eval set |
| Phase boundary error | ≤ 300 ms | KINECAL 手工標註 |
| Event latency（rep 完成 → event_id 發出）| < 50 ms | Pixel 8 Pro 端側 |
| Tracking lost recovery time | < 800 ms | hard-case subject re-entry |
| ActivityContextTracker lock stability | 連續 ≥ 3 rep 同 label | replay session |

**最關鍵兩條**：`false rep_completed rate` 與 `judgeability false-positive
rate`。Layer 2 寧可少判，也不能亂判。

### 15.20 Golden replay test framework

每個 case 都測 expected events / expected no-events / expected
abstain_reason / expected MotionZip fields：

```text
case_001_clean_chair_sts_3_reps          (3 reps with stable hip+knee)
case_002_half_squat_not_rep              (insufficient ROM)
case_003_low_confidence_occlusion        (left side occluded)
case_004_subject_leaves_frame            (mid-rep abandon)
case_005_multi_person_enters             (caregiver walks in)
case_006_starts_already_seated           (no standing baseline)
case_007_starts_already_standing         (no descent observed)
case_008_supported_squat_ambiguous       (hand near hip but no chair confirm)
case_009_balance_hold_with_sway          (sway crosses unstable threshold)
case_010_chair_sts_with_brief_jitter     (frame drop during rising)
```

每個 case 是一個 derived-feature-only JSONL fixture（**no raw video, no raw
landmarks**），CI 跑全部，任一 case regression 阻擋 commit。

### 15.21 v2 vs v1 conflict resolution

當 v1 與 v2 描述衝突時，**以 v2 為準**：

| 主題 | v1 reference | v2 supersedes |
|---|---|---|
| Layer 2 single class | §9 implementation plan | §15.1 7-submodule decomposition |
| Single `judgeability` string | §1 contract | §15.2 granular judgeability |
| Single `phase` value | §1 contract, §2 phase set | §15.3 candidate vs committed |
| Two-state abstain (judgeable/abstain) | §4.1 gate-first | §15.4 three-tier abstain |
| Activity by `activity_hint` only | §2.1, §3.1 | §15.5 ActivityContextTracker score lock |
| `hip_y_norm` (y-down convention) | §3.1, §4.0 `STANDING_HIP_Y` | §15.12 `hip_height_norm` (y-up convention, body-scale calibrated) |
| Fixed completion conditions | §4.2 | §15.7 explicit precondition bitset |
| `feature_sequence` to E2B | §4.5 recorder row | §15.8 MotionZip keyframes + extrema (recorder still keeps full sequence for training) |
| Evidence refs as labels | §1 evidence_refs | §15.9 value + threshold + time_range |
| supported_squat fully judgeable | §4.3 | §15.14 monitor-only at P0 |

v1 sections 仍是 migration baseline 與歷史紀錄；新 PR 直接針對 §15
contract 對齊。
