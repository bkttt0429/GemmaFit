# GemmaFit — 可信賴的多動作體態反饋

> **Kaggle Gemma 4 Good Hackathon 提交文件。**
> 字數上限：**≤1500 字**（目前草稿約 1600 字）。
> 參賽賽道：Main · Safety & Trust（主要）· Health & Sciences · llama.cpp · Unsloth Special。
>
> FC 基準的 `[FILL]` 已全部替換為 `eval_compare.py` 實際測試數據
> （見 [docs/benchmark/](benchmark/)）。Refusal 基準的佔位符保留 —— 待下一個微調 checkpoint。

---

> Current architecture note: GemmaFit now uses a summary-only local Gemma
> path. Live feedback is deterministic from C++/Kotlin evidence. Before the
> model runs, Android receives a Capability Contract (`can_judge` /
> `cannot_judge`) and Evidence DAG refs from native code. Gemma can select a
> function only for metrics in `can_judge`, and any missing `evidence_refs`
> trigger deterministic fallback. This avoids claiming unproven threshold validation,
> injury prediction, joint force, or muscle activation percentages.

## 1. 問題 — 體態 App 普遍過度推斷

單鏡頭健身 App 習慣性地過度推斷。它們從側面視角影片回報 *膝內翻角度*，而該指標在幾何上根本無法從該視角定義。它們顯示 *肌肉活化百分比*，但沒有任何體態模型有辦法測量。它們在沒有 MRI、EMG 或臨床醫師的情況下預測 *受傷風險*。使用者看到整齊的儀表板，開始相信那些數字 —— 直到數字與自體感受矛盾。

誠實的做法不是讓這些系統閉嘴。基於體態的反饋確實能幫助請不起教練的人。誠實的做法是讓系統 **知道自己不能說什麼**。**GemmaFit 的核心主張是信任：** 系統在有證據支撐時給予反饋，在證據不足時 *明確拒絕* —— 大聲說出來、附上理由、並讓使用者清楚看見。演示這兩面比只演示一面更難，但唯有兩面兼具，才能負責任地部署在 Pixel 級手機上，放進真實的健身房。

這一定位契合 Gemma 4 的邊緣設備特性（E4B Q4_K_M 約 1.5 GB，Tensor G3 上單 token 延遲 < 100 ms），也回應了 Safety & Trust 賽道的核心問題：*我們如何推出有用但不假裝萬能的本地 AI？* GemmaFit 的答案是 **「正確判斷 + 正確拒絕」** 雙面演示。

*(約 210 字)*

---

## 2. 系統架構

```text
影片 / 相機
   ↓ MediaPipe Pose Landmarker（33 關鍵點 + 可見度）
   ↓ float[99] → JNI KinematicsBridge
   ↓
C++ 生物力學管線（每幀，< 1 ms）
   ├─ 信心閘門 ─── 低可見度 → LOW_CONFIDENCE
   ├─ 關節角度（12）+ 肢段向量（11）
   ├─ 對稱度評估
   ├─ 質心追蹤（de Leva 1996，支撐多邊形）
   ├─ 安全監測（8 條規則）
   ├─ 動作分類器（物理模式，非動作命名）
   ├─ 肌群推估（基於姿態，非 EMG）
   └─ 動作品質報告（模板感知、視角感知）
         ↓
   結構化 JSON  →  證據鍵快取
         ↓
   FrameHint（確定性，每幀）
   SessionSummaryCoach（Gemma 4，僅在判定變更時觸發，去抖動）
   SessionSummary（訓練後，壓縮時間線）
         ↓
   Kotlin：CoachVoice（TTS + 冷卻） + Compose UI（PoseOverlay）
```

整個決策流程被分層設計，使 *每一項* 判定都有可追溯的來源。Trust Matrix 為每條活躍規則標註七種狀態之一
（`OK / VIEW_LIMITED / LOW_CONFIDENCE / NOT_APPLICABLE / MONITOR / WARNING / CRITICAL`）。Evidence Card 同時記錄判定背後的數值證據，以及 *同樣醒目的* 系統明確拒絕提供的判斷 —— 關節力、臨床受傷風險、EMG 式活化百分比、醫療診斷。

MVP 內建四種動作模板：

| 動作 | 活躍指標 | 拒判指標 |
| --- | --- | --- |
| **深蹲** | 深度、膝/髖角度、軀幹前傾、節奏、COM 監測 | 側視角膝內翻；精確關節力 |
| **伏地挺身** | 肘角度、身體線條、髖下沉、深度、節奏 | 膝內翻、COM/BoS（非地面承重） |
| **弓步蹲** | 前膝角度、步長、軀幹直立度 | 單幀雙側不對稱（意圖性的單側動作） |
| **硬舉** | 髖鉸鏈、軀幹角度、槓/體路徑代理、節奏 | 腰椎盤壓力、測力板等效負荷 |

姿態提取使用 MediaPipe Pose Landmarker (Lite)。關節角度與 600°/s 急速動作檢測（規則 6）使用 Savitzky-Golay 平滑濾波以消除單幀離群值。相同的結構化動作報告同時餵給 Streamlit 原型儀表板和 Android App 的 Compose UI —— 兩個介面的結果永遠一致。

### 手機端 AI 管線：每幀分析即反饋分層

GemmaFit 的手機端影片路徑圍繞一個約束設計：本地 AI 在背景工作時，播放必須保持流暢。因此 App **不對每一幀 30 fps 畫面都調用 Gemma**，而是採取 **「每幀分析顯示反饋，但 Gemma 推論以事件/關鍵為驅動」** 的策略。

影片播放器維持正常的 30/60 fps 播放。背景分析工作線程按時間戳採樣幀，通常每 100–125 ms 一幀（約 8–10 fps），在姿態推論前保持長寬比並將長邊壓縮至 640 px。儲存影片分析使用 MediaPipe Pose Landmarker 的 VIDEO 模式並附帶幀時間戳；即時相機使用 LIVE_STREAM 模式。疊加數據以 `timestampMs → landmarks / metrics / flags / feedback` 格式儲存，並對齊回 ExoPlayer 播放時間。當播放器處於兩個分析幀之間時，骨架使用信心感知插值；低信心關鍵點會淡出或隱藏，而非猜測。

```text
影片播放
  → 時間戳採樣器
  → Pose + 原生指標
  → FrameHint
  → 證據鍵快取
  → SessionSummaryCoach / SessionSummary
```

反饋分為三層。`FrameHint` 是確定性的，在每個分析幀上根據規則和原生指標立即可用，UI 永遠不需等待 Gemma。`SessionSummaryCoach` 僅在去抖動後的證據鍵發生變化時由 Gemma 生成；否則 App 復用快取的教練文本。證據鍵組合了動作類型、階段、次數、主要指標、嚴重度區間、信心區間、視角區間、頂級品質標誌、判決以及活躍的 `NOT_APPLICABLE` 集合。去抖動、遲滯和冷卻機制防止 landmark 抖動反覆觸發 LLM。`SessionSummary` 在片段結束後使用壓縮的次數/事件時間線運行，而非原始逐幀報告。

這種結構既更快也更安全。本地模型接收簡短、固定模式的證據提示，返回固定 JSON 如 `text`、`safety`、`confidence_policy` 和 `refusal`。它不允許估算關節力、診斷受傷或覆蓋適用性閘門。若某規則為 `NOT_APPLICABLE`，Gemma 必須解釋邊界，而非將其轉化為判斷。模型僅載入一次，分析前預熱，並受背壓保護：相同鍵的事件合併，佇列保持較小，低優先級事件被丟棄，同時 FrameHint 持續更新。

*(約 330 字)*

---

## 3. 創新 — 將「正確拒絕」作為核心賣點

最具差異化的設計選擇是 *讓拒答變得可見*。大多數健身 App 將自身局限藏在啟動畫面底部的一行小免責聲明中。GemmaFit 將拒答提升到前台：

- Workout 畫面有一個專屬的 **「此視角無法判斷」** 卡片，逐條列出系統拒絕套用的每一條規則，每條附一行原因的簡短說明。
- 每張 Evidence Card 包含一個明確的 `unsupported_judgments` 陣列展示給使用者 —— 不只 `joint_force` 和 `clinical_injury_risk`，還包含 *原因*（單鏡頭代理，非直接測量力）。
- Gemma 4 系統提示在生成時強制執行拒答詞彙：如 *「從此視角我無法判斷 X」* 和 *「此規則不適用於 <動作>」* 的短語通過微調被強化。
- 基準測試套件（[docs/benchmark/refusal/](benchmark/refusal/)）對 29 個手工策劃的拒答情境進行評分，涵蓋 8 個類別（錯誤視角、錯誤模板、低信心、動態 COM、未知動作、超出範圍查詢、跨模板誤用、多對象）。每個情境測試三個維度：模型是否 **拒答**、是否 **提及** 正確的邊界、是否 **避開** 禁用詞彙如 `Newtons`、`EMG`、`bpm`、`your fppa reading`？

具體而言，這將專案的安全敘事從一個 checkbox 變成一個可量化的競爭指標。*正確拒答一個側視角 FPPA 查詢* 是一個附帶數字的特性，而不僅僅是口號。

微調數據集使用三股數據流按 30:60:10 權重混合：510 個領域專用合成 FC 範例（來自 `finetune/data/generate_synthetic.py`，涵蓋全部 8 條規則、9 種動作模式）、40 K Glaive Function Calling v2 範例（從 HuggingFace 串流讀取—— 不觸及硬碟）、以及 1.2 K Anthropic HH-RLHF 安全對齊配對（串流）。Glaive 組件教導 schema 的穩健性；領域種子教導 GemmaFit 專屬的 8 工具詞彙；HH-RLHF 配對強化安全對齊的拒答語言。Unsloth QLoRA 管線在 Colab Pro A100 上運行，每個 checkpoint 約需 1.5 小時；最終 LoRA adapter 約 50 MB；合併後的 Gemma 4 E4B 匯出為 Q5_K_M（桌面基準測試用）和 Q4_K_M（Android、Pixel 8 Pro 部署用）。

*(約 300 字)*

---

## 4. 結果

**Function Call Schema 遵守度** — 90 驗證範例，透過 `eval_compare.py` 調用 llama-cpp-python 對本地 GGUF 檔案進行測試。

| 指標 | 基礎 E4B Q4_K_M | 微調後 Q5_K_M | Δ |
| --- | --- | --- | --- |
| JSON 解析率 | 95.6% | 93.3% | −2.3% |
| 函數名稱匹配 | 0.0% | 2.2% | +2.2% |
| 參數重疊（Jaccard） | 0.026 | 0.081 | +0.055 |
| 平均延遲 / 範例 | 16.78 s | 10.77 s | −6.01 s |

基礎模型對每個範例都幻覺出 `analyze_motion`（0% 正確函數）。微調引入了 8 工具詞彙：函數匹配從零開始上升，參數 Jaccard 增長三倍。JSON 解析率略微下降 —— 這是 QLoRA 保真度的已知取捨，可通過更高比特量化改善。完整逐範例分解及對比 HTML 報告見 `docs/benchmark/ab_compare/`。

**Refusal 基準測試** — 29 個手工策劃情境，涵蓋 8 個類別（錯誤視角、錯誤模板、低信心、動態 COM、未知動作、超出範圍、跨模板誤用、多對象）。基準測試基礎設施（`refusal_eval.py`）和情境定義已完備；模型評估待下一個微調 checkpoint。

| 維度 | 基礎 | 微調後 | Δ |
| --- | --- | --- | --- |
| 通過率（三項全過） | [FILL: %] | [FILL: %] | [FILL: ±%] |
| 拒答維度 | [FILL: %] | [FILL: %] | [FILL: ±%] |
| 提及維度 | [FILL: %] | [FILL: %] | [FILL: ±%] |
| 安全維度（無禁用詞彙） | [FILL: %] | [FILL: %] | [FILL: ±%] |

資料來源：`prototype/refusal_eval.py`。詳細逐情境報告將產出至 `docs/benchmark/refusal/report.html`。

**動作品質驗證**（Phase 1 基準測試）。

- Python 原型單元測試 **169/169 全通過**（關節角度 36/36、8 安全規則 67/67、COM 追蹤 16/16、動作分類 35/35、肌群推估 15/15）。
- C++ 原生單元測試 **50/50 全通過**（COM 追蹤 16/16、安全監測 8/8、運動學管線 12/12、動作品質 14/14）。
- Zenodo 深蹲圖片基準（3,806 張圖片）：彎背探測 P = 1.000, R = 0.072（以 15° 偏差為保守閾值，達成零誤報）。Bad-heel 代理 F1 = 0.787。

**本地推論**（llama.cpp on Tensor G3）。

- Gemma 4 E4B Q4_K_M GGUF：Pixel 8 Pro 運行記憶體約 1.5 GB。
- 微調後 Q5_K_M 推論：90 範例平均 **10.77 s**。
- App 內設計：每幀生物力學在 C++ 原生層運行（< 1 ms/幀）。真實 Gemma 被證據鍵去抖動閘門攔截 —— 僅在判定變更事件時調用，通常每次數或每個新安全標誌出現時。FrameHint（確定性）在不等待 LLM 的情況下每幀更新。SessionSummary 在訓練結束後運行。

*(約 280 字)*

---

## 5. 實作證據

程式碼庫包含：

- **[`prototype/exercises/core.py`](../prototype/exercises/core.py)** — 模板引擎、適用性閘門、mock 反饋生成器。
- **[`prototype/dashboard_v3.py`](../prototype/dashboard_v3.py)** — Streamlit 儀表板：骨架疊加、關節軌跡、Evidence Card、不支援的判斷。
- **[`prototype/render_demo_video.py`](../prototype/render_demo_video.py)** — 註解示範 MP4（深蹲、伏地挺身、弓步蹲、硬舉），並排展示骨架 + 教練面板。
- **[`app/`](../app/)** — 35 個 Kotlin 檔案：CameraX + MediaPipe LIVE_STREAM、`VideoAnalysisViewModel`（中央調度器）、`KinematicsBridge` JNI、`TemporalMotionAnalyzer`（線上次數計數器 + Savitzky-Golay 平滑）、`CoachVoice`（TTS + 冷卻 + 優先級佇列）、`PoseOverlay`（Compose Canvas 骨架 + 安全熱區）、WorkoutScreen + SummaryScreen 附表單評分趨勢圖。
- **[`native/`](../native/)** — C++17 生物力學引擎：10 個運動學模組、JNI 橋接、4 個測試可執行檔（CTest 50/50 全通過）。
- **[`finetune/train_gemma4_pipeline.ipynb`](../finetune/train_gemma4_pipeline.ipynb)** — 雲端 QLoRA 管線，Colab A100 執行。串流 40 K Glaive FC + 領域種子 + HH-RLHF（透過 HF datasets，零本地下載），每 200 steps checkpoint 至 Google Drive，斷線後自動恢復，匯出 adapter + Q5/Q4 GGUF。

持久訓練儲存：< 4 GB（LoRA adapter + 兩個 GGUF 量化檔案）；來源數據集從不觸及硬碟。整個管線可在免費 Kaggle Notebook（2 × T4）或 Colab Pro A100 上一次運行完成。

*(約 220 字)*

---

## 6. 限制與邊界

GemmaFit 是一套動作品質反饋系統，非臨床工具。系統明確 **不**：

- 估算關節力、腰椎盤壓力或力矩（單鏡頭姿態沒有真實力測量）。
- 估算肌肉活化百分比（無 EMG 感測器）。
- 預測受傷風險（非診斷設備，未經臨床結果驗證）。
- 給出任何形式的醫療診斷。
- 從側面或背面視角判斷膝內翻 / FPPA（幾何上未定義）。
- 基於單一影片幀發出 `CRITICAL`（需要時間持續性驗證）。
- 在單幀中判斷多個對象（直至單一對象才進行判斷）。

[`finetune/data_collection/knowledge_base/thresholds_curated.json`](../finetune/data_collection/knowledge_base/thresholds_curated.json) 中的每個閾值都帶有明確引用來源（NSCA、Hewett 2005、Powers 2010、Schoenfeld 2010 等）。標記為 `prototype_threshold` 的閾值是保守預設值；標記為 `validated` 的閾值已經過開放資料集校準，並在程式碼註釋中予以說明。

*(約 170 字)*

---

## 7. 結語

GemmaFit 推出的是知道自身邊界的本地 AI 反饋。它可在 Pixel 8 Pro 上完全離線運行，大聲拒答不支援的判斷，並為每一項判決生成證據卡。「正確判斷 + 正確拒絕」的演示是這個專案的核心影響力主張 —— 也是唯一誠實地將健身 AI 放到使用者面前、而不需要臨床醫師在旁監督的方式。

*(約 60 字)*

---

## 附錄 — 字數估算

| 章節 | 約略字數 |
| --- | --- |
| 1. 問題 | 210 |
| 2. 架構 | 330 |
| 3. 創新 | 300 |
| 4. 結果 | 310 |
| 5. 實作證據 | 220 |
| 6. 限制與邊界 | 170 |
| 7. 結語 | 60 |
| **總計** | **≈1600** |

若超標，優先精簡 §4 的表格行或 §2 的手機端管線小節 —— 動作模板表是參考資料，非核心論述。
