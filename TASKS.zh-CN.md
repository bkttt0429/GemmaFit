# GemmaFit 任务状态简体版

当前日期：2026-05-05
截止日期：2026-05-18
当前路线：**Main Track + Safety & Trust**

GemmaFit 现在的定位不是单一动作分析器，也不是万能姿势裁判或医疗诊断工具。当前 MVP 是一个可信赖的多动作运动质量反馈系统：

```text
Pose -> Motion Trace -> Exercise Template -> Structured Metrics
-> Trust Matrix -> Evidence Card -> Safe Gemma Feedback
```

核心主张：

```text
GemmaFit provides trustworthy motion feedback that knows its limits.
```

## 当前做到哪里

**当前里程碑：Phase 4 Android + Trust integration 已完成 contract layer。**

最新完成的是第一版 **Capability-Bounded Evidence Router**：

```text
Native motion_quality
-> capability_contract + evidence_dag
-> Android parse + session aggregation
-> Summary-only Gemma prompt
-> evidence-ref / capability validation fallback
```

也就是说，现在系统已经不只是输出 `quality_flags`，而是会先声明本次 session 能判断什么、不能判断什么，再把这些边界交给本地 Gemma。Gemma 只能在 `can_judge` 里选择工具，并且必须引用存在的 evidence id。

## 最新已完成

- [x] Native `MotionQualityReport` 新增 additive JSON：
  - `capability_contract`
  - `evidence_dag`
- [x] Native contract 会输出：
  - `can_judge`
  - `cannot_judge`
  - `required_evidence`
  - `confidence_ceiling`
  - `evidence_refs`
- [x] Native Evidence DAG 已包含：
  - metric node
  - visibility node
  - quality gate node
  - safety rule node
  - not applicable gate node
  - capability node
- [x] 侧面深蹲现在不会整段封锁：
  - 可判断：depth / tempo / trunk evidence
  - 不可判断：frontal knee valgus
- [x] LOW_CONFIDENCE 会封锁 hard form judgment，不会产出硬性 warning。
- [x] Android 已 parse `capability_contract` 与 `evidence_dag`。
- [x] 旧 JSON 仍兼容，缺少 DAG 不会崩溃。
- [x] `SessionCoachRenderer` 会把 compact capability / evidence context 传给 summary-only Gemma。
- [x] 如果本地模型引用不存在的 `evidence_refs`，直接 fallback deterministic。
- [x] 如果本地模型选择了 `cannot_judge` 对应的 tool，直接 fallback deterministic。
- [x] 文档已更新到：
  - Capability Contract
  - Evidence DAG
  - summary-only Gemma
  - evidence-first UX
- [x] Fine-tune 文档已保留 v2 benchmark，不直接破坏；v3 才引入 Evidence Router dataset。

## 已验证

- [x] `cmake --build native\build_local --target test_motion_quality --config Release`
- [x] `.\native\build_local\Release\test_motion_quality.exe`
  - 结果：23/23 pass
- [x] `ctest --test-dir native\build_local -C Release --output-on-failure`
  - 结果：7/7 pass
- [x] `.\gradlew.bat :app:testDebugUnitTest --console=plain`
- [x] `.\gradlew.bat :app:assembleDebug --console=plain`
- [x] touched files 的 scoped `git diff --check`

## Phase 总览

```text
Phase 0  Environment and assets                 100% complete
Phase 1  Python biomechanics prototypes          100% complete  202/202 PASS
Phase 2  Native C++ core prototypes              100% complete  ctest pass
Phase 3  Multi-exercise Prototype Dashboard      100% complete
Phase 4  Android + Trust integration             in progress / contract layer complete
Phase 5  Demo, writeup, media gallery            pending
```

## Phase 4 当前状态

Phase 4 已完成基础 Trust integration：

- [x] Android NDK cross-compile
- [x] `KinematicsBridge.kt` 接 native `motion_report`
- [x] Android state parse:
  - `exercise`
  - `exercise_confidence`
  - `template_metrics`
  - `quality_flags`
  - `not_applicable`
  - `capability_contract`
  - `evidence_dag`
- [x] Workout UI 显示 Trust Matrix
- [x] Workout UI 显示 Evidence Card
- [x] Unsupported judgments 已包含：
  - `joint_force`
  - `clinical_injury_risk`
  - `medical_diagnosis`
  - `muscle_activation_percentage`
- [x] Safe summary-only Gemma prompt 已接 capability/evidence refs
- [x] Model result validation 已接：
  - invalid evidence refs fallback
  - cannot_judge tool fallback

## 还没做

- [ ] 深度研究报告回来后，正式整理 citation。
- [ ] 建 Product Claims matrix：
  - `claim`
  - `source`
  - `allowed wording`
  - `forbidden wording`
  - `code/doc target`
- [ ] Fine-tune v3 data generator：
  - input 加 `capability_contract`
  - input 加 compact `evidence_dag`
  - output 必须含 `evidence_refs`
  - output 加 `refusal_level`
- [ ] LiteRT-LM 真机 smoke：
  - Pixel 8 Pro
  - `gemmafit-v2-fc.litertlm` 或后续 v3 artifact
- [ ] Summary UI 更深入显示 capability / evidence refs。
- [ ] 用 `/sdcard/DCIM/GemmaFitTest/` correct / wrong 视频做验收。
- [ ] 清理 unrelated dirty build artifacts。
- [ ] 修掉既有 `VideoStates.kt` EOF whitespace warning。

## 下一步建议顺序

1. **Pixel 8 Pro 跑 correct/wrong 视频验收**
   - 目标：确认 Capability Contract 实际影响 Summary，不只是 JSON 存在。

2. **补 Summary UI evidence refs 展示**
   - 目标：让用户和评审看到“本地 AI 参考了哪些 evidence”。

3. **等深度研究报告回来后更新 docs**
   - 目标：把 Product Claims 和论文证据对齐。

4. **做 fine-tune v3 evidence-router dataset**
   - 目标：让 Gemma 真正学会 `can_judge` / `cannot_judge` / `evidence_refs`。

5. **LiteRT-LM 真机 inference**
   - 目标：证明不是 deterministic 文案，而是真的 on-device local model。

## 当前风险

- LiteRT-LM 真模型路径还没完全验收；目前 summary path 有 fallback guard，但还不能声称 tuned LiteRT model 已在手机上稳定运行。
- Evidence DAG 第一版是 frame/session summary 级，不是完整 raw landmark timeline；这是有意保守，避免 raw video / raw pose 进入长期记忆。
- Fine-tune v2 还不是 Capability Contract 训练出来的模型；v3 才会对齐这个新 contract。
- 当前 worktree 还有很多 unrelated build artifacts 和之前任务留下的 dirty state，提交前需要单独整理。
