# GemmaFit — 知道自身限制的離線動作教練

> Kaggle Gemma 4 Good Hackathon 中文工作稿。英文版 `docs/writeup_draft.md`
> 為主要提交稿；本文鏡像對齊故事、架構與技術創新點。
> **目標賽道**：Main · Health & Sciences · Safety & Trust · Google AI Edge / LiteRT · llama.cpp。

## GemmaFit 是什麼

GemmaFit 是一個 Android 動作教練 app，完全在裝置上跑（Google AI Edge LiteRT
+ Gemma 4 E2B），零雲端依賴。目標族群是在家或社區照護環境練習扶椅起立、
扶椅下蹲、站立平衡的長者，以及想要邊界清楚、不誇張的回饋的健身初學者。

即時提示建立在 deterministic pose evidence 上；session 總結由 Gemma 4 從一
個精簡的 evidence packet 寫出；每一次「拒絕回答」都明確讓使用者看見。

核心主張是 **在不確定下保持誠實**：當鏡頭角度不足以支持判斷、人物部分出框、
動作介於兩個 template 之間無法分辨、或使用者問了超出證據邊界的問題（跌倒
風險、診斷、關節力、肌肉活化），app 會 **明確地拒絕** 而不是幻想答案。

## 問題

單鏡頭健身 app 經常過度宣稱 — 從側面影片推論膝外翻、沒有 EMG 卻給「肌肉
活化 %」、把手機影片包裝成臨床風格的風險分數。對長者照護來說兩種失敗都很
危險：錯誤警告中止有益活動，錯誤肯定掩蓋真實不確定性。既有 on-device LLM
demo 把模型當成 safety engine，放大這個風險。**GemmaFit 反過來：模型只寫
摘要；安全由 deterministic stack 把守。**

## 技術創新

GemmaFit 的貢獻是 6 個有 benchmark 背書的「bounded local LLM」生產級 pattern：

**1. MotionZip — task-preserving evidence compression。** 密集 pose 串流壓
成一個精簡 JSON packet，保留 event boundary、角度極值、速度峰值、信心下
界、低信心區間、不支持宣稱邊界；raw frame、完整 landmark stream、ReID
embedding、臨床標籤全部丟棄。模型對 dense 輸入重現 **8 / 8 task-critical
facts**，peak velocity 差異僅 **1.89 %**。產品走壓縮路徑；dense 比對作為
CI gate。
*驗證*：[`motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/`](benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json)

**2. ActivityContextTracker — pose-only 時序消歧。** 5 狀態機（`UNKNOWN /
CALIBRATING / LOCKED / SUSPECT_SWITCH / AMBIGUOUS`）用 pose-only 信號（最
低點髖部停留、下蹲時軀幹前傾、手相對髖部位置、phase profile 形狀）在時間
窗內為視覺相似的模板評分（扶椅起立 vs 扶椅蹲）。連續一致 rep 才 lock；
分數太接近時 emit `AMBIGUOUS`。**以接近零推論成本取代 vision sidecar「看
椅子」的需求**，同時對不確定性保持誠實。
*驗證*：[`senior_layer2_video_replay_report_2026-05-16/`](benchmark/senior_layer2_video_replay_report_2026-05-16/report.md), [`layer2_senior_activity_ab_2026-05-16/`](benchmark/layer2_senior_activity_ab_2026-05-16/)

**3. 多層 ModelInvocationScheduler — gate-first LLM dispatch。** 5 層過濾
確保 **≥99 %** 的 pose frame 永遠不會碰到 LLM（rewrite class → 裝置/追蹤
/可判斷性 → trigger policy → in-flight dedup → realtime fast-path 直接回
deterministic JSON 而不呼叫模型）。**一個典型 5 分鐘 session 只觸發 1 次
真實 LLM 呼叫 — 結束時的 summary。** 模型是受限的寫手，不是安全引擎。
*驗證*：[`live_safety_contract_report_2026-05-16/`](benchmark/live_safety_contract_report_2026-05-16/report.md)

**4. 可見的 capability contract + refusal-as-feature。** 每張 Evidence Card
明確顯示 `can_judge` / `cannot_judge` chip。當被問跌倒風險、診斷、關節
力、EMG、肌肉活化 %，系統 fire `refuse_unsupported_question` 並給非臨床的
安全替代答案。共用的 `RefusalValidator` 同時防守英文與 zh-TW 禁語清單。
**拒絕是產品功能，不是 fallback。**
*驗證*：[`architecture_validation_gap_report_2026-05-16/`](benchmark/architecture_validation_gap_report_2026-05-16/report.md), [`memory_export_boundary_report_2026-05-16/`](benchmark/memory_export_boundary_report_2026-05-16/)

**5. Coach Narrative Packet — deterministic narrative substrate。**
`RepRecord` + `RepTraceSummary` + `PersonalTraceEnvelope` 編譯成
`rep_summaries` + `session_trend` + `baseline_comparison` 結構化 packet，
用 enum + 數值精簡編碼。模型拿到豐富的 per-rep 素材但 **不會撐大 prompt
tokens**，輸出從「填表式摘要」變成「具體、引用 rep 編號的教練觀察」。

**6. Mali-G715 hardened LiteRT-LM pipeline。** Chat template 對齊官方
Gemma 4（`<turn|>` 收尾標記、session summary `<|think|>` thinking mode）、
JSON parse 失敗自動 GPU→CPU retry、tool-call args ≤ 4 限制。**直接回應
Mali-G715 public field report（LiteRT-LM Issue #2202）** — 沒對齊時
tool-call 在 4+ args 下成功率掉到 0 %。我們達到 **100-run smoke 100 / 100
JSON parse 成功**。
*驗證*：[`litert_prompt_smoke_constrained_100_official_2026-05-16/`](benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json), [`litert_runtime_stability_report_2026-05-16/`](benchmark/litert_runtime_stability_report_2026-05-16/)

## 架構

```text
CameraX / Video
  → MediaPipe Pose + presence / subject / judgeability gates
  → derived motion features
  → Senior Layer 2 FSM + ActivityContextTracker
  → MotionFeatureWindow + MotionZip packet
  → SeniorInteractionPolicy
  → ModelInvocationScheduler (multi-layer gate)
  → 官方 Gemma-4-E2B-it LiteRT streaming session
  → Android parser / validator / deterministic fill / fallback
  → UI · TTS · care log · caregiver export · debug receipt
```

Deterministic live loop 算 pose 證據；MotionZip 壓縮；scheduler 決定何時
（與是否）呼叫 Gemma；Android 端驗證並補完模型輸出。完整設計：
[`official_e2b_motionzip_runtime_architecture.md`](design/official_e2b_motionzip_runtime_architecture.md)。

## 效能 — Pixel 8 Pro, 官方 Gemma-4-E2B-it LiteRT

| 指標 | 結果 |
|---|---:|
| Model 磁碟 | 2.5 GB |
| GPU + CPU footprint | ~2.86 GB |
| 生成延遲 | avg 24.9s · p50 24.8s · p95 26.5s |
| JSON 解析成功率（100 runs）| 100 / 100 |
| Streaming 首字時間 | 生成開始後 0.96s – 3.14s |
| MotionZip vs dense 等效性 | 8 / 8 key facts |
| Peak velocity 保留差異 | 1.89 % |
| Camera image-path p95 | 10.2 ms / frame |

4 個層次優化把使用者體感 end-to-end 從 **~88s 降到 ~25s**：(a) app-launch
背景 prewarm + 熱狀態 gating 把 9.7s engine init 藏起來；(b) async streaming
UI 讓首字 ~3s 出現而不是空等 23s；(c) constrained-decoding spike 在
conversation API 路徑通過 smoke 後強制 JSON schema；(d) Compose pose-overlay
violation pulse 隔離到 `Modifier.graphicsLayer { alpha = pulse }`，主骨架
維持 pose update 頻率（~15 fps）重組，違規期間不再每秒 60 次重繪。
*驗證*：[`litert_prompt_stream_dev_2_warm_official_2026-05-16/`](benchmark/litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json), [`rgba_pipeline_audit/2026-05-16_pixel8pro/`](benchmark/rgba_pipeline_audit/2026-05-16_pixel8pro/summary.md), [`litert_model_perf_2026-05-16/`](benchmark/litert_model_perf_2026-05-16/)

## 為什麼選官方 Gemma 4 E2B（而非自訓微調）

提交版本走官方 `Gemma-4-E2B-it` LiteRT artifact。另外訓練的
`gemmafit-v5-e2b-evidence-router` LoRA 透過 debug override 可切換，但列為
P1 — 官方 baseline 已能從 MotionZip 寫出 care-log 等級輸出，v5 要升級到
產品路徑必須通過 eval gate（schema fidelity、evidence-ref precision、
latency、senior wording）證明優於官方。`llama.cpp` vision sidecar（含
mmproj Q8 vs F16 比對）已 prototype 但保留為 P3 — Pixel 記憶體壓力過大，
而 ActivityContextTracker 已覆蓋當初想用 vision 解的 chair-vs-squat 問題。
*驗證*：[`gemma4_vision_mmproj_q8_vs_f16/`](benchmark/gemma4_vision_mmproj_q8_vs_f16/README.md)

## 雙語 care log

同一份 MotionZip packet 配上 locale-pinned instruction 與 one-shot example，
Gemma 4 即可寫出英文或繁體中文 care-log 內容。UI 字串、TTS 聲音、cue
catalog、模型輸出全部對齊使用者選擇。`RefusalValidator` 強制執行兩語禁語
清單，因為多語 base 在邊界情境會偷漏英文。

## Safety & Trust

信任層 **可被使用者看見**。Source badge（`Pose rules` / `Local Gemma` /
`Template fallback` / `Abstained`）出現在每個 cue 旁；Evidence Card 分成
*看到什麼* / *判斷了什麼* / *沒判斷什麼*；Debug receipt 紀錄 backend、
model hash、scheduler decision、fallback reason、evidence refs、stream
phase、first-token time、thermal status、per-stage timing — **每一個輸出
的字都可審計**。Memory 結構化、app-owned；raw video、完整 landmark
stream、自由格式 model memory、生物辨識、醫療標籤、跌倒/肌少症分數、力學、
GRF、EMG 全部硬封鎖，CI grep gate 在 senior mode UI 字串出現封鎖詞時 fail
build。

## 限制

GemmaFit 不是醫療裝置。不估計關節力、GRF、EMG、真實肌肉活化、受傷/跌倒風
險、失智狀態、肌少症、復健進展、臨床改善。MotionZip 是 task-preserving，
不是 visually lossless。證據不足時，正確行為是 abstain、暫停、要求重新設
置鏡頭，或產出非臨床的活動摘要。

## 賽道對應

- **Main** — 完整可跑的 Android app：on-device Gemma 4 E2B 從 app-owned
  evidence 寫 care log，零雲端依賴。
- **Health & Sciences** — 為長者設計的動作教練；明確的非臨床邊界；
  `capability_contract` 明確列出系統可以與不可以判斷的事項。
- **Safety & Trust** — refusal-as-feature；MotionZip 8 / 8 evidence audit
  gate；可見來源 badge；100 / 100 JSON parse gate；隨時可用的 deterministic
  fallback；結構化 app-owned memory 與硬封鎖詞表。
- **Google AI Edge / LiteRT** — 使用官方 `Gemma-4-E2B-it` LiteRT artifact
  與官方文件中的 Engine / Session 生命週期（prewarmed persistent session、
  streaming API、app-launch background warmup）；Mali-G715 field report 對齊。
- **llama.cpp** — `llama.cpp` vision sidecar 已對 MotionZip equivalence 做
  評估（mmproj Q8 vs F16）；因 Pixel 記憶體限制保留為 research benchmark。

## 結語

GemmaFit 的貢獻是一個可信任的 on-device AI pattern：**先 deterministic
證據、再 compact 可審計的 model context、本機 Gemma 只在有用時呼叫、相機
不支持判斷時要看得見地拒絕**。最終是一個長者可以安心使用、不被誤導的離線
教練 — 也是任何想做有邊界、有證據基礎 local LLM 產品團隊可參考的架構。

---

### 參考資料

**單一入口驗證 rollup**（涵蓋下方所有 benchmark + 整合報告）：
[`docs/benchmark/local_validation_run_2026-05-17/`](benchmark/local_validation_run_2026-05-17/README.md)
· [`summary.json`](benchmark/local_validation_run_2026-05-17/summary.json)
· [`validation_matrix.svg`](benchmark/local_validation_run_2026-05-17/validation_matrix.svg)

設計文件：

- [`official_e2b_motionzip_runtime_architecture.md`](design/official_e2b_motionzip_runtime_architecture.md) — runtime 架構
- [`layer2_senior_activity_model.md`](design/layer2_senior_activity_model.md) — Layer 2 + ActivityContextTracker v2
- [`model_invocation_scheduler.md`](design/model_invocation_scheduler.md) — gate-first scheduler
- [`motionzip_v4_temporal_evidence_compression.md`](design/motionzip_v4_temporal_evidence_compression.md) — evidence compression spec

逐項 benchmark：

- [`motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/`](benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json) — 8/8 MotionZip 等效性
- [`litert_prompt_smoke_constrained_100_official_2026-05-16/`](benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json) — 100/100 JSON gate
- [`litert_prompt_stream_dev_2_warm_official_2026-05-16/`](benchmark/litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json) — streaming 首字
- [`live_safety_contract_report_2026-05-16/`](benchmark/live_safety_contract_report_2026-05-16/report.md) — scheduler gate-first 驗證
- [`architecture_validation_gap_report_2026-05-16/`](benchmark/architecture_validation_gap_report_2026-05-16/report.md) — capability contract 覆蓋
- [`senior_layer2_video_replay_report_2026-05-16/`](benchmark/senior_layer2_video_replay_report_2026-05-16/report.md) — Layer 2 + ActivityContextTracker replay
- [`gemma4_vision_mmproj_q8_vs_f16/`](benchmark/gemma4_vision_mmproj_q8_vs_f16/README.md) — vision sidecar 評估
- [`rgba_pipeline_audit/2026-05-16_pixel8pro/`](benchmark/rgba_pipeline_audit/2026-05-16_pixel8pro/summary.md) — camera-path perf
- [`edge_gallery_official_e2b_litert_smoke_2026-05-15.md`](benchmark/edge_gallery_official_e2b_litert_smoke_2026-05-15.md) — 初版 E2B baseline

Demo 素材：[`docs/assets/demo/`](assets/demo/) · 架構影片：[`docs/assets/video/`](assets/video/)。
