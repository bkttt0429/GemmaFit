# GemmaFit - 可信任的離線動作回饋

> Kaggle Gemma 4 Good Hackathon 中文工作稿。英文版
> `docs/writeup_draft.md` 是主要提交稿；本文件用來檢查故事、架構與用語是否一致。

## 摘要

GemmaFit 是一個離線 Android 動作回饋 app，目標族群是長者與健身初學者。它結合單鏡頭姿態證據、 deterministic safety gates、MotionZip 時序證據壓縮，以及本機 Gemma 4 E2B LiteRT，產生受限制且可稽核的訓練摘要。

核心主張不是「AI 看懂所有影片」，而是：app 先用工具取得可信的動作證據，再把壓縮後的關鍵事實交給 Gemma 產生摘要；當證據不足時，系統必須清楚拒判。

Hero demo 是 Senior Strength Mode：支援 chair sit-to-stand、supported squat、balance hold，並提供大按鈕、短 TTS、保守暫停、照護者摘要。這是失智友善的互動設計，不是失智篩檢，也不推論認知退化、走失風險、跌倒風險或臨床診斷。

## 問題

很多單鏡頭健身系統容易過度宣稱。例如從側面影片判斷膝外翻、沒有 EMG 卻宣稱肌肉活化百分比，或用手機影片包裝成臨床風險分數。對長者照護場景來說，這類錯誤很危險：錯誤警告可能讓有益的活動被停止，錯誤肯定則會掩蓋不確定性。

GemmaFit 把「拒判」設計成產品能力。當使用者離開畫面、目標人物不明、視角不足以判斷某項指標，或使用者要求診斷/跌倒風險/復健處方時，系統會直接顯示不能判斷的原因，而不是假裝知道。

## 架構

```text
CameraX / Video
-> MediaPipe Pose
-> presence, subject identity, confidence, judgeability gates
-> derived motion features
-> Senior Layer 2 FSM + ActivityContextTracker
-> MotionFeatureWindow + MotionZip packet
-> SeniorInteractionPolicy
-> ModelInvocationScheduler
-> official Gemma-4-E2B-it LiteRT streaming session
-> Android parser, validator, deterministic fill, fallback
-> UI, TTS, care log, caregiver export, debug receipt
```

Live path 不呼叫 E2B。每一幀的姿態、信心、目標人物、ROM proxy、速度、穩定度與可判斷性都由 deterministic 工具處理。Senior Layer 2 只輸出 `activity_hypothesis`、`phase`、`event`、`judgeability`、`evidence_refs`；它不做醫療判斷、不判斷跌倒風險、不估計 sarcopenia、不輸出復健處方、不估計 force/GRF/EMG。

`ActivityContextTracker` 負責避免錯誤肯定。當 chair sit-to-stand 和 supported squat 等相似動作證據接近時，它輸出 `AMBIGUOUS`，而不是硬選一個錯誤活動。

MotionZip 是 app-side temporal evidence compression。它保留 event boundary、角度 extrema、velocity peak、confidence floor、低信心區段、subject tracking state、unsupported-claim boundary 與 evidence refs；它丟棄冗餘逐幀資料、raw video、完整 landmarks、ReID embedding 與臨床 label。MotionZip 不是 lossless video compression，而是 task-preserving evidence compression。

`ModelInvocationScheduler` 只決定是否要呼叫 E2B、跳過、延後或 fallback；它不決定 function name、args 或 safety verdict。E2B 只用於 approved event explanation、session summary、caregiver export、bounded refusal wording。`USER_LEFT_ACTIVITY_AREA`、`NO_RESPONSE_AFTER_CUE`、`MULTI_PERSON_AMBIGUOUS` 與一般 live frame 都走 deterministic UI/TTS，不呼叫 E2B。

Gemma 4 E2B 是本機 evidence writer，不是 live safety engine。Android 端仍負責 JSON cleanup、schema validation、evidence-ref whitelist、forbidden-claim rejection、deterministic fill 與 deterministic fallback。100-run constrained smoke 沒有觀察到 native LiteRT tool-call object，所以正式安全邊界不能依賴 native tool-call enforcement。

## Gemma 4 使用方式

P0 主線使用官方 `Gemma-4-E2B-it` LiteRT artifact。它比 GemmaFit v5 小，且已通過目前的 Pixel JSON gate 與 MotionZip equivalence benchmark。GemmaFit v5 fine-tune 保留為 P1 quality layer；`llama.cpp` vision sidecar 因 Pixel 記憶體壓力過高，保留為 P3 benchmark-only。

## 目前證據

| Gate | Result |
| --- | ---: |
| Official E2B artifact size | 2,538,766,336 bytes |
| 100-run official JSON gate | 100/100 endpoint、generation、JSON parse success |
| Official E2B generation latency | avg 24.9s, p50 24.8s, p95 26.5s |
| Warm streaming first token | 0.96s 到 3.14s |
| MotionZip dense-vs-compressed key checks | 8/8 pass |
| MotionZip peak velocity difference | 1.89% |
| MotionZip event-frame tolerance | within 6 frames |
| Live camera image path audit | accepted-frame p95 約 10.2ms |

MotionZip equivalence 的重點是：dense frame-derived evidence 和 compressed MotionZip prompt 都讓模型保留同一組關鍵理解，包括 activity、state set、event count、event timing、velocity band、peak velocity、confidence floor、low-confidence reason。這證明目前架構不需要把每一幀都餵給模型，也能保留允許任務需要的關鍵資訊。

## Safety & Trust

UI 會顯示 `Pose rules`、`Local Gemma`、`Template fallback`、`Abstained` 等來源 badge。Evidence Card 顯示已觀察、已判斷、未判斷與原因。Debug report 會列出 backend、model file、scheduler decision、fallback reason、evidence refs、stream phase、first-token time、thermal status 與 per-stage timing。

Memory 是 app-owned structured memory。允許記錄 session summary、calibration baseline、bounded preference、care log、dual-task attempt、evidence-ledger refs。禁止記錄 raw video、完整 raw landmarks、free-form model memory、biometric identity、medical labels、fall-risk score、sarcopenia score、force、GRF、EMG。

## 限制

GemmaFit 不是醫療器材，也不宣稱臨床有效性。它不估計 joint force、GRF、EMG、真實肌肉活化、受傷風險、跌倒風險、失智狀態、sarcopenia、復健進展或臨床改善。它也不宣稱 MotionZip 保留影片中的所有視覺資訊。當證據不足，正確行為是 abstain、pause、要求重新 setup，或產生非臨床活動摘要。

## 結語

GemmaFit 的貢獻是一個可落地的 Safety & Trust 架構：deterministic evidence first、compact auditable model context、local Gemma only when useful，以及 visible refusal。它讓長者與初學者得到離線動作協助，同時讓每個輸出都留在證據邊界內。
