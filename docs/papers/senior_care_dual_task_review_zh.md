# GemmaFit Senior Hero：照护日志与双重任务设计说明

Last updated: 2026-05-07

## 定位

Senior Hero Mode 的目标不是把手机变成医疗筛检工具，而是提供离线、
可解释、可拒答的居家活动辅助：

```text
single-camera pose evidence
-> capability contract
-> evidence ledger
-> non-diagnostic care log / dual-task prompt
```

产品可主张的是：

- 完成了哪些低冲击活动。
- 哪些 movement-quality proxy 可见，例如 reps、tempo、ROM proxy、sway
  proxy、stability proxy。
- 哪些判断没有做，例如 fall-risk prediction、sarcopenia detection、
  rehabilitation prescription、muscle mass、clinical improvement。
- 本地端处理减少 raw video / care record 离开装置的需求。

产品不可主张的是：

- 诊断失智、肌少症、跌倒风险、疼痛原因或受伤。
- 判断 rehabilitation progress 或 return-to-play readiness。
- 推估 joint force、ground reaction force、ligament load、EMG 或 muscle
  activation。

## Care Activity Log

Care log 是 data-to-text generation，但输入必须是结构化 evidence，而不是
raw video 或自由叙述。日志固定五段：

1. What was completed
2. Observed movement quality
3. What was not judged
4. Next session focus
5. Caregiver note

建议语气：

```text
Completed 12 chair sit-to-stand reps in 3 minutes.
Two stability proxy events were observed.
This does not assess fall risk, sarcopenia, or rehabilitation progress.
Keep the chair area clear and use the same controlled pace next time.
```

避免语气：

```text
Fall risk improved.
Possible sarcopenia pattern.
Rehabilitation progress is good.
Lower-limb force increased.
```

## Dual-task Training

第一版双重任务是低冲击、闭集回答：

- 左手 = A
- 右手 = B
- 拍手 = 确认
- 双手举高 = 跳过或取消
- 语音只接受 A/B、yes/no、1-4 或短选项

模型可以选择 prompt / focus，但不能判断认知能力、失智风险或医学状态。
ASR 信心不足、噪音高、答案超出闭集时，必须 fallback 到 gesture。

## Fine-tune Implication

v4 数据集应该训练 FunctionGemma 270M 做 bounded routing：

```text
activity_context + motion_context + capability_contract + evidence_ledger
-> create_care_activity_log
-> select_dual_task_prompt
-> record_dual_task_result
-> refuse_unsupported_question
```

训练数据不应该包含 raw landmarks、raw video、私人照护记录或医疗标签。合成数据
必须把 forbidden claims 做成 refusal rows，而不是让模型学习如何改写诊断。

## Research Backlog

- Data-to-text generation for structured care logs.
- Dual-task motor-cognitive training in older adults.
- Augmented feedback frequency and autonomy for older adults.
- On-device privacy and local AI for home wellness support.
- Non-diagnostic wellness boundaries under FDA general wellness and WHO/NIST
  trustworthy AI principles.
