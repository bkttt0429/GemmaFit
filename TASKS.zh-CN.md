# GemmaFit 任务清单
当前日期：2026-05-05
截止日期：2026-05-18
当前主线：Main Track + Safety & Trust

GemmaFit 的 MVP 定位是离线、证据优先、非诊断的 movement-quality coaching。当前主流程：

```text
Pose -> Motion Trace -> Exercise Template -> Structured Metrics
-> Capability Contract / Evidence Ledger -> Safe Local Model Routing
```

一句话叙事：

```text
GemmaFit provides trustworthy motion feedback that knows its limits.
```

## 当前状态 - 2026-05-05

Phase 4 Android + Trust integration 已经完成，并且方向升级为 Capability-Bounded Evidence Router：

```text
Native motion_quality
-> capability_contract + evidence_dag
-> Android parse + session aggregation
-> Summary-only Gemma prompt
-> evidence-ref / capability validation fallback
```

### 已完成

- [x] Native `MotionQualityReport` 输出 `capability_contract` 与 `evidence_dag`。
- [x] Native contract 支持 `can_judge`、`cannot_judge`、`required_evidence`、`confidence_ceiling`、`evidence_refs`。
- [x] Side-view squat 可判 depth / tempo / trunk evidence，并 block frontal knee valgus。
- [x] Low-confidence frames 会 block hard form judgment。
- [x] Android 可解析 `capability_contract` 与 `evidence_dag`，旧 JSON 兼容。
- [x] `SessionCoachRenderer` 只传 compact capability/evidence context 给 summary-only Gemma path。
- [x] 模型引用不存在的 `evidence_refs` 时 deterministic fallback。
- [x] 模型试图判断 `cannot_judge` metric 时 refusal/fallback。
- [x] 文档已更新 Capability Contract、Evidence DAG、summary-only Gemma、evidence-first UX。
- [x] 深度文献回顾与 Product Claims matrix 已整理。
- [x] Fine-tune v3 generator/eval 已转向 evidence-router 训练目标。

### 已验证

- [x] `cmake --build native\build_local --target test_motion_quality --config Release`
- [x] `.\native\build_local\Release\test_motion_quality.exe` -> 23/23 pass
- [x] `ctest --test-dir native\build_local -C Release --output-on-failure` -> 7/7 pass
- [x] `.\gradlew.bat :app:testDebugUnitTest --console=plain`
- [x] `.\gradlew.bat :app:assembleDebug --console=plain`

### 仍待处理

- [ ] Run v3 Colab training job，并复制 `training_done_v3.json`、`trainer_state_v3.json`、conversion metadata。
- [ ] LiteRT-LM real model smoke on Pixel 8 Pro。
- [ ] Summary UI 更完整显示 capability/evidence refs。
- [ ] Pixel 8 Pro `/sdcard/DCIM/GemmaFitTest/` correct/wrong clips acceptance。
- [ ] 清理 unrelated dirty build artifacts。

## 立即下一轮 Todo - Senior Hero Demo 闭环 (2026-05-07)

这是下一轮执行清单。除非设备或模型转换卡住，否则按这个顺序做。目标是先跑通可展示的 Senior Hero flow，不做大范围重构。

- [ ] `NEXT-P0-01` Senior state wiring：新增 ViewModel/state reducer，统一管理 senior activity、dual-task prompt、gesture/voice attempt、care-log context、care log、backend、fallback、evidence refs。
  - 验收：Senior UI 能从同一个 state 显示 prompt card、response status、care-log summary；demo 不依赖纯 mock UI。
- [ ] `NEXT-P0-02` Live gesture pipeline：把 camera pose landmarks 接到 `SeniorGestureDetector`，输出 left-hand A、right-hand B、clap confirm、two-hand skip/cancel 的 `DualTaskAttempt`。
  - 验收：Pixel live run 能记录 gesture attempts，包含 `metric.dual_task.gesture.*` evidence refs，并能在低信心时 fallback。
- [ ] `NEXT-P0-03` Care-log context builder：把 senior session summary、capability contract、evidence ledger 转成 `CareLogContext`。
  - 验收：sit-to-stand 和 balance-hold 能产生 deterministic `CareLogContext`，不包含 raw landmarks、raw video、medical labels。
- [ ] `NEXT-P0-04` Care-log render + memory write path：有本地模型时走 router，否则走 `SeniorCareLogRenderer`；只有 evidence ids 通过 policy 时才写 `CARE_ACTIVITY_LOG`。
  - 验收：debug JSON 显示 `care_log.backend`、`function_name`、`evidence_refs`、`fallback`、unsupported judgments。
- [ ] `NEXT-P0-05` Native senior evidence ids：输出 `metric.senior.reps`、`metric.senior.tempo`、`metric.senior.trunk_control`、`metric.senior.stability_events`，以及 dual-task gesture evidence nodes。
  - 验收：native tests 覆盖 clean sit-to-stand、low confidence、stability proxy event、blocked fall-risk/sarcopenia capability nodes。
- [ ] `NEXT-P0-06` Official FunctionGemma baseline smoke：先用官方 FunctionGemma `.litertlm` 跑 LiteRT 真路径 sanity check，再信任 GemmaFit v4 fine-tune artifact。
  - 验收：Android debug report 显示真实 `litert-lm:*` backend，或清楚说明 fallback reason；demo 不依赖未验证模型。
- [ ] `NEXT-P0-07` FunctionGemma v4 training/export：执行 `train_functiongemma_v4_senior_router.ipynb`，只有 smoke 通过后才复制 `training_done_v4`、eval report、`.litertlm`。
  - 验收：`tool_call_eval_v4_senior.json` 通过 gates，LiteRT artifact 可加载，unsupported claims 仍进入 `refuse_unsupported_question`。
- [ ] `NEXT-P0-08` Pixel Senior acceptance pack：准备或录制 clean sit-to-stand、stability proxy、balance hold、no person、live dual-task gesture 短片。
  - 验收：`docs/benchmark/` 的 debug reports 包含 `care_log`、`dual_task`、`capability_contract`、`evidence_dag`、backend、fallback、evidence refs。
- [ ] `NEXT-P0-09` Caregiver export polish：用五段式 care log 做出照护者可读的 export entrypoint。
  - 验收：导出内容包含完成活动、可见动作品质、未判断项目、下次重点、caregiver note；不出现 fall-risk、sarcopenia、rehab-progress、force、EMG、diagnosis claim。
- [ ] `NEXT-P0-10` Demo script update：前 90 秒改成 Senior Hero，General Fitness 作为 evidence/biomechanics foundation。
  - 验收：脚本展示离线模式、dual-task prompt、care log、fall-risk question refusal、evidence/debug trace。

## 立即下一轮 Todo - P1

- [ ] `NEXT-P1-01` Optional ASR wrapper：用 Android `SpeechRecognizer` 放在 feature flag 后面，bounded parser 仍是权威。
- [ ] `NEXT-P1-02` Senior-specific UI copy localization：补 zh-TW 与 en-US 文案。
- [ ] `NEXT-P1-03` 7D/30D senior activity trend memory summary：只能用 app-provided aggregates。
- [ ] `NEXT-P1-04` 增加 Senior synthetic/no-person/occlusion benchmark clips 与 expected-output fixtures。
- [ ] `NEXT-P1-05` demo-critical files 稳定后，再清理 unrelated dirty artifacts 或明确加入 ignore。

## 下一轮验证命令

每个 implementation slice 后都跑：

```powershell
python finetune/data/generate_v4_senior_router.py --validate
python finetune/eval_v4_senior_router.py --dataset finetune/data/gemmafit_v4_senior_evidence_router.json --strict
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
git diff --check
```

## 训练数据来源计划 - Senior v4

FunctionGemma v4 的 SFT 主数据应该是「结构化 evidence -> function call」，不是 raw video。公开视频/骨架数据集主要用于扩充场景覆盖、生成 fixtures、做验收测试；不能拿来训练或宣称医疗结论。

| ID | 来源 | GemmaFit 用途 | 提取内容 | 状态 / 动作 | 边界 |
| --- | --- | --- | --- | --- | --- |
| `DATA-P0-01` | GemmaFit synthetic senior evidence router data (`finetune/data/generate_v4_senior_router.py`) | FunctionGemma v4 主要 SFT 来源。 | `care_log_context`、`dual_task_context`、`activity_context`、`motion_context`、`capability_contract`、`evidence_ledger`、目标 tool call。 | 已可用；当前 9200 rows strict eval 通过。等 `NEXT-P0-05` 后补 native fixtures。 | 只训练 routing/refusal，不训练 biomechanics 或 clinical labels。 |
| `DATA-P0-02` | Pixel / 本地 Senior Hero demo clips | Gold acceptance 与 hard-negative fixtures。 | MediaPipe landmarks -> native report -> compact evidence ledger；fine-tune row 不含 raw video。 | 录制 sit-to-stand、balance hold、step touch、no-person、low-confidence、live dual-task gestures。 | 本地/private video 默认 ignored；只提交 derived JSON。 |
| `DATA-P0-03` | ETRI-Activity3D elderly daily-activity dataset: https://ai4robot.github.io/etri-activity3d-en/ | 高龄 ADL 与 activity-context 覆盖。 | elderly RGB-D/skeleton action classes，尤其站起、坐下、伸手、居家 ADL。 | 先下载 samples；完整数据使用前检查 access terms。 | 只做 activity recognition/fixture diversity；不做 fall-risk、sarcopenia、rehab claims。 |
| `DATA-P0-04` | Toyota Smarthome / Toyota Smarthome Untrimmed | 真实居家 senior ADL context 与 noisy home scenarios。 | activity labels、coarse/fine ADL segments、可用时提 pose evidence。 | 检查 access/license；未获批前只用论文与 sample metadata。 | Research-use；不要重分发 raw clips；不是临床数据集。 |
| `DATA-P0-05` | NTU RGB+D / NTU RGB+D 120: https://rose1.ntu.edu.sg/dataset/actionRecognition/ | 广泛 skeleton action、gesture 覆盖，以及 motion-intensity proxy 覆盖。 | stand up、sit down、clapping、hand waving、squat down、stagger/fall，以及 joint velocity / angular velocity / tempo-band proxy。 | 需要时注册/request；优先用 skeleton modality。 | academic/non-commercial terms；fall/stagger 只用于 refusal/boundary tests。derived velocity 是 proxy，不是 force 或 injury label。 |
| `DATA-P0-06` | Public fall datasets：UP-Fall、UR Fall、SisFall/KFall | Refusal / boundary corpus。 | unsupported claim 名称、edge-case motion contexts、sensor limitation。 | 先用文献/数据集描述；只有需要 no-claim benchmark 时下载。 | GemmaFit 不输出 fall-risk score 或 fall prediction。 |
| `DATA-P0-07` | App-generated care-log templates | 家属/照护者五段式日志 Data-to-Text examples。 | synthetic JSON -> en-US / zh-TW care log。 | 从产品模板生成，再加 reviewer-authored gold examples。 | wellness activity summary，不是 medical note 或 rehab progress report。 |
| `DATA-P0-08` | Dual-task prompt templates | 低冲击 cognitive/motor prompt selection rows。 | A/B、yes/no、1-4 bounded options；gesture/voice fallback cases。 | 从 memory recall、category sorting、attention switching、arithmetic、orientation 模板生成。 | 不训练 dementia risk、cognitive impairment、cognitive screening。 |
| `DATA-P0-14` | AddBiomechanics Dataset: https://addbiomechanics.org/download_data.html | 作为 motion-intensity 边界与 claim limit 的真实 biomechanics 参考。 | optical mocap、ground reaction force、joint torque、center-of-mass kinematics，以及许可范围内的 kinematics/kinetics。 | 先用于 research notes 与 calibration design；terms 允许时才提取 aggregate proxy sanity checks。 | 这不是 single-camera dataset；不能训练 GemmaFit 宣称手机单镜头可测 force、torque、GRF、ligament load 或 clinical risk。 |
| `DATA-P0-15` | OpenCap validation literature / available exports: https://pmc.ncbi.nlm.nih.gov/articles/PMC10586693/ | 作为 markerless biomechanics 能力边界参考。 | kinematic/kinetic validation ranges、task coverage、camera requirements，以及可用时的 public example exports。 | 先支撑 Product Claims 与 calibration path，再考虑生成 row。 | OpenCap 不等于 GemmaFit single-camera BlazePose；不能转移 force/muscle activation claims。 |
| `DATA-P0-16` | AMASS + BABEL: https://amass.is.tue.mpg.de/ 和 https://babel.is.tue.mpg.de/ | 大规模 mocap + language labels，用于 activity-aware motion-intensity distributions。 | 3D motion sequences、action labels、可用时的 phase/window labels、velocity/angular-velocity/ROM/smoothness proxies。 | 写好 source/license note 后下载小 subset。 | 只用于 context 与 expected-movement distributions；不作为 medical、force 或 injury supervision。 |
| `DATA-P0-17` | AIST++ Dance Motion Dataset: https://google.github.io/aistplusplus_dataset/ | 高旋转 / 高 ROM motion examples，用于降低 dance-like tasks 的 false positives。 | 3D dance motion、rotation speed、asymmetry、airborne/turning phase proxies、activity labels。 | `motion_context` schema 固定后作为 stress data。 | Dance intensity 是 task context，不是自动 safety warning；不做 joint-force 或 injury claim。 |
| `DATA-P0-18` | GemmaFit motion-intensity synthetic rows | 把 public motion datasets 转成 FunctionGemma router schema。 | `motion_context`、`momentum_proxy`、`tempo_band`、`phase`、`rule_interpretation`、`capability_contract`、evidence refs。 | native `motion_intensity` evidence ids 建好后再加。 | 模型学习 interpretation states，不学习 true momentum measurement；deterministic gates 仍是权威。 |

立即数据任务：

- [ ] `DATA-P0-09` 新增 `finetune/data_sources/senior_v4_sources.md`，记录每个外部数据集的 URL、access status、license/terms、用途、禁止 claim、citation。
- [ ] `DATA-P0-10` 新增 `test_assets/benchmarks/senior_v4/manifest.json`，把 Pixel clips 与 public/sample clips 对应到 expected evidence ids。
- [ ] `DATA-P0-11` 在最终训练前，从真实 app reports 加 20 条 native-report-derived rows 到 v4 generator。
- [ ] `DATA-P0-12` 增加 50 条 adversarial caregiver prompts，覆盖 fall risk、sarcopenia、rehab progress、diagnosis、clinical improvement、joint force、EMG、muscle activation。
- [ ] `DATA-P0-13` 英文 schema gates 稳定后，增加 50 条 zh-TW care-log rows 与 50 条 zh-TW dual-task rows。
- [ ] `DATA-P0-19` 在 `finetune/data_sources/senior_v4_sources.md` 增加 `motion_intensity_sources` 小节，覆盖 AddBiomechanics、OpenCap、AMASS/BABEL、AIST++、NTU、license/access status 与 excluded claims。
- [ ] `DATA-P0-20` 在 v4 generator 增加 motion-intensity row families：controlled senior fast motion -> monitor pace、sport explosive movement -> expected movement、airborne COM offset -> not applicable/expected、landing stabilization instability -> monitor/warning、force/ACL/EMG questions -> refusal。
- [ ] `DATA-P0-21` 在 evaluator 增加 motion-intensity 检查：single-camera pose 不得输出 true momentum、GRF、joint force、ACL load、ligament strain、EMG、muscle activation claim。

## 下一轮 - Evidence Contract 强化 (P0)

论文主要支撑 claim boundary、calibration path、selective abstention、provenance design；不代表目前 prototype thresholds 已临床验证。

- [x] `EVID-P0-01` Native evidence id 稳定化。
- [x] `EVID-P0-02` Evidence node 补齐 `id`, `type`, `metric`, `value`, `source`, `confidence`, `evidence_level`, `reason`。
- [x] `EVID-P0-03` Evidence edge endpoint 验证。
- [x] `EVID-P0-04` Capability Contract view gating。
- [x] `EVID-P0-05` Low confidence blocks hard judgment。
- [x] `EVID-P0-06` Android compact ledger 不传 raw landmarks / raw video。
- [x] `EVID-P0-07` Gemma validation fallback。
- [x] `EVID-P0-08` Summary UI/debug 显示 backend/function/evidence refs/fallback。
- [x] `EVID-P0-09` Native tests 覆盖 side-view/frontal/low-confidence/edge endpoint。
- [x] `EVID-P0-10` Android tests 覆盖 missing DAG/full DAG/invalid evidence refs。
- [ ] `EVID-P0-11` Video acceptance 汇出 debug JSON。
- [x] `EVID-P0-12` Research extraction backlog。

## 下一轮 - Senior Hero v4 (P0)

Senior Hero 是新的主 demo：离线高龄居家运动辅助、照护日志、双重任务训练。General Fitness 保留为 shared biomechanics foundation。所有输出必须维持 non-diagnostic movement-quality / wellness support。

- [x] `SENIOR-P0-01` 添加 Senior contracts：`CareLogContext`、`CareActivityLog`、`DualTaskSessionPlan`、`DualTaskAttempt`、`activity_context`、`motion_context`。
- [x] `SENIOR-P0-02` 扩展 FunctionRegistry：新增 `create_care_activity_log`、`select_dual_task_prompt`、`record_dual_task_result`。
- [x] `SENIOR-P0-03` 扩展 memory policy：`CARE_ACTIVITY_LOG` 与 `DUAL_TASK_RESULT` 写入必须带 evidence ids。
- [x] `SENIOR-P0-04` 添加 deterministic care-log fallback renderer，禁止 fall-risk、sarcopenia、rehab、force、EMG、clinical claim。
- [x] `SENIOR-P0-05` 添加双重任务 gesture detector：左手=A、右手=B、拍手确认、双手举高跳过/取消。
- [x] `SENIOR-P0-06` 添加 bounded voice answer parser：ASR 信心不足或答案越界时 fallback 到 gesture。
- [x] `SENIOR-P0-07` 添加 debug endpoints：`content://com.gemmafit.debug/care_log` 与 `content://com.gemmafit.debug/dual_task`。
- [ ] `SENIOR-P0-08` 将 Senior screens 接到 ViewModel live state：dual-task prompt card、gesture/voice status、care-log summary。
- [ ] `SENIOR-P0-09` Native Senior evidence ids：`metric.senior.reps`、`metric.senior.tempo`、`metric.senior.trunk_control`、`metric.senior.stability_events`、`metric.dual_task.gesture.left_arm_raise`、`metric.dual_task.gesture.right_arm_raise`。
- [x] `SENIOR-P0-10` 添加 v4 synthetic generator 与 evaluator，用于 FunctionGemma senior evidence router。
- [x] `SENIOR-P0-11` 添加 Colab notebook skeleton，可恢复执行 FunctionGemma v4 senior-router fine-tune。
- [ ] `SENIOR-P0-12` v4 dataset/eval 已通过；下一步 smoke `gemmafit-v4-senior-router.litertlm` 后再启用 backend。
- [ ] `SENIOR-P0-13` Pixel acceptance：clean sit-to-stand care log、stability proxy events 不说 fall risk、gesture A/B、ASR low-confidence fallback、caregiver 问 fall risk 时拒答。

## 下一轮 - Senior Hero v4.1 主观回馈与跨角色报告 (P0)

v4.1 不让手机镜头或模型硬估真实 momentum、heart rate、force 或 clinical risk，而是加入 bounded self-report evidence 和跨角色活动报告。

- [x] `SENIOR41-P0-01` 定义 subjective check-in contracts：`SubjectiveCheckIn`、`SubjectiveLevel`、self-report evidence refs 与 JSON serialization。
- [x] `SENIOR41-P0-02` 添加 persona report contract：`PersonaActivityReport`，支持 `senior`、`caregiver`、`professional_share`。
- [x] `SENIOR41-P0-03` 扩展 FunctionRegistry：新增 `ask_subjective_checkin`、`record_subjective_checkin`、`create_persona_activity_report`。
- [x] `SENIOR41-P0-04` 添加 deterministic persona report fallback，把 objective movement evidence 与 self-report 结合，同时拒绝 heart-rate、fall-risk、sarcopenia、rehab、force、EMG、diagnosis claim。
- [x] `SENIOR41-P0-05` 更新 v4 generator/evaluator 为 v4.1 row families：care log、subjective prompt、subjective record、persona report、memory、unsupported、adversarial。
- [x] `SENIOR41-P0-06` 添加 `subjective_checkin` 与 `persona_report` debug sections/endpoints。
- [ ] `SENIOR41-P0-07` 把 post-session 大按钮/TTS check-in card 接进 live Senior state flow。
- [ ] `SENIOR41-P0-08` Pixel acceptance：clean sit-to-stand + mild RPE 生成三种 persona reports；discomfort / strong breathlessness 输出 stop/rest boundary，不做 diagnosis。

## 安全语言检查

- [ ] 使用 “movement quality feedback”，不要说 medical diagnosis。
- [ ] 使用 “pose-based estimate”，不要说 measured muscle activation。
- [ ] 使用 “single-camera proxy”，不要说 precise joint force。
- [ ] 明确显示 `VIEW_LIMITED`、`LOW_CONFIDENCE`、`NOT_APPLICABLE`。
- [ ] Senior care log 只能描述活动完成与可见 movement observations，不能判断 fall risk / sarcopenia / rehab progress。
- [ ] Dual-task 只能记录 bounded task result，不能做 cognitive diagnosis 或 dementia risk。
