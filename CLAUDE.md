# CLAUDE.md — GemmaFit 專案 AI 協作指引

## 專案概述

GemmaFit 是 Kaggle Gemma 4 Impact Challenge 參賽作品，定位為**可解釋的多運動姿態回饋教練**。
目標是在 Pixel 8 Pro 上離線運行，使用 MediaPipe Pose 擷取人體姿態，以 C++/Python 運動學模組計算關節軌跡與動作品質指標，再由 Gemma 4 將結構化資料轉成可理解、知道自身限制的教練回饋。

核心流程：`Pose → Motion Trace → Exercise Template → Structured Metrics → Gemma Feedback`

系統支援 Squat、Push-up、Lunge、Deadlift 四種運動，每種運動只啟用少數高可信指標。當視角、信心分數或動作情境不足時，明確輸出 `NOT_APPLICABLE`、`LOW_CONFIDENCE` 或 `VIEW_LIMITED`，不硬套所有安全規則。

- **時程**：19 天衝刺（Deadline: May 18, 2026）
- **目標賽道**：Main Track, Health & Sciences, Safety & Trust, llama.cpp, Unsloth
- **權威文件**：`implementation_plan.md` 為所有決策的單一真相來源

## 目錄結構

```
GemmaFit/
├── app/                     # Android Kotlin 主程式
│   ├── camera/              # CameraX 模組
│   ├── mediapipe/           # MediaPipeTask 模組
│   ├── jni/                 # JNI 橋接介面 (Kotlin → C++)
│   │   ├── KinematicsBridge.kt
│   │   └── LLMBridge.kt
│   ├── functions/           # FunctionRegistry (FC 工具 schema)
│   ├── session/             # WorkoutSession（組數/次數/結構化報告）
│   ├── voice/               # CoachVoice（TTS + 冷卻機制）
│   └── ui/                  # UIOverlay（骨架 + 軌跡 + 品質旗標）
├── native/                  # C++ NDK 原生層
│   ├── CMakeLists.txt
│   ├── kinematics/
│   │   ├── joint_angles.cpp         # 12 主要關節角度計算
│   │   ├── body_segments.cpp        # 肢段方向向量
│   │   ├── symmetry.cpp             # 雙側對稱度
│   │   ├── com_tracker.cpp          # 質心投影 vs 支撐底面積
│   │   ├── safety_monitor.cpp       # 8 條運動學品質規則
│   │   ├── movement_classifier.cpp  # 物理模式識別
│   │   ├── muscle_focus.cpp         # 基於姿態的肌群參與推估
│   │   └── confidence_gate.cpp      # 信心分數攔截
│   ├── bridge/
│   │   ├── kinematics_bridge.cpp    # JNI: float[99] → StructuredMotionReport JSON
│   │   └── llm_bridge.cpp           # JNI: JSON → llama.cpp → FC 調用
│   └── llama.cpp/                  # llama.cpp 子模組 (git submodule)
├── prototype/               # Python 原型（驗證與儀表板）
│   ├── exercises/           # 運動模板定義
│   ├── dashboard/           # Prototype Dashboard 可視化
│   └── data/                # 原始資料 + 處理後資料
├── finetune/                # Unsloth Colab 微調腳本 + 數據集
│   ├── train_gemma4_fc.ipynb
│   └── data/
│       ├── generate_synthetic.py    # 600 筆 FC 訓練數據生成器
│       └── fc_training_data.json    # train=510 / val=90
├── docs/                    # 論文參考、影片腳本、競賽素材
├── test_assets/             # Demo 影片資產清單
│   └── asset_manifest.json
└── implementation_plan.md   # 總體規劃文件（單一真相來源）
```

## 資料來源與存放規則

| 資料類型 | 存放路徑 | 版控 | 說明 |
|---------|---------|------|------|
| **合成微調數據集** | `finetune/data/` | ✅ Git | FC 訓練用 JSON，由 `generate_synthetic.py` 生成 |
| **Unsloth 微調輸出** | `finetune/output/` | ❌ .gitignore | LoRA adapter、合併後模型、訓練 log |
| **GGUF 量化模型** | `models/` | ❌ .gitignore | Gemma 4 E4B Q4_K_M.gguf，手動放置 |
| **Python 原型腳本** | `prototype/` | ✅ Git | 運動學驗證、Dashboard、運動模板 |
| **C++ 單元測試** | `native/tests/` | ✅ Git | 桌面端可執行測試，以 `test_*.cpp` 命名 |
| **C++ 測試固定資料** | `native/tests/fixtures/` | ✅ Git | 預錄骨架 JSON / CSV，可重現測試 |
| **學術論文 / 參考文獻** | `docs/papers/` | ✅ Git | PDF 或 BibTeX 引用 |
| **影片腳本 / 分鏡** | `docs/scripts/` | ✅ Git | Demo 影片腳本、對白稿 |
| **Benchmark 結果** | `docs/benchmark/` | ✅ Git | FC 準確率、推論延遲圖表 |
| **競賽素材** | `docs/assets/` | ✅ Git | App 截圖、Media Gallery 用圖 |
| **API 金鑰 / 憑證** | `.env` | ❌ .gitignore | 嚴禁提交 |
| **NDK 建置產物** | `build/`, `native/build/` | ❌ .gitignore | `.so`、`.apk`、`.aab` |

## 架構總覽

### Prototype（Python，當前 Phase 3 目標）

```
Video/Camera
  → MediaPipe PoseLandmarker
  → Confidence Gate（visibility < 0.6 → VIEW_LIMITED）
  → Motion Trace Builder（關節角度軌跡 + tempo）
  → Heuristic Exercise Classifier
      ↓  exercise + confidence
  → Exercise Template Selector（squat / push_up / lunge / deadlift）
  → Template-specific Metric Extractor
  → Applicability / Quality Gates
      → NOT_APPLICABLE / LOW_CONFIDENCE / VIEW_LIMITED / MONITOR / WARNING / CRITICAL
  → Structured Motion Report JSON
      ├── Dashboard Visualization（骨架 + 角度圖 + 品質旗標）
      └── mock_gemma_feedback → per-frame coaching message
```

### Android（Phase 4，長期目標）

```
CameraX → MediaPipeTask → KinematicsBridge JNI → C++ Native Layer
  → StructuredMotionReport JSON → LLMBridge JNI → llama.cpp Gemma 4
  → Function Calling → CoachVoice TTS / UIOverlay
```

## 核心設計原則

1. **Exercise template over universal rules** — 每種運動只啟用少數高可信指標；8 條運動學規則是 building blocks，不是全域觸發器
2. **Applicability first** — 在發出 WARNING/CRITICAL 前，gate 必須確認規則對當前運動情境成立；否則輸出 `NOT_APPLICABLE`
3. **Safety language** — 所有輸出使用 "movement quality feedback"，不用 "medical diagnosis"；肌群推估用 "pose-based estimate"，不宣稱 EMG 精度
4. **Confidence gate before LLM** — 任何 Gemma/mock 推論前必須先過 Confidence Gate
5. **Structured JSON as interface** — Python Dashboard 與未來 Android JNI 共用同一個 StructuredMotionReport 格式
6. **效能路徑在 C++** — 每幀運動學計算目標 < 1ms，不在 Kotlin GC 上做計算

## 運動模板概覽

| 運動 | 啟用指標 | 重要 Gate 說明 |
|------|---------|--------------|
| `squat` | depth, knee_angle, hip_angle, trunk_lean, tempo | FPPA 只在正面視角啟用 |
| `push_up` | elbow_angle, body_line, hip_sag, depth, tempo | knee_valgus_fppa → NOT_APPLICABLE |
| `lunge` | front_knee_angle, step_length_proxy, trunk_uprightness | 單側動作不從單幀判 asymmetry CRITICAL |
| `deadlift` | hip_hinge, trunk_angle, bar_path_proxy, tempo | P1 bonus；不宣稱 force estimation |

## Applicability Gate 狀態碼

```
OK             — 指標正常，無需干預
MONITOR        — 接近閾值或情境模糊，持續觀察
WARNING        — 超出可接受範圍，給出矯正建議
CRITICAL       — 安全疑慮，需立即回饋
NOT_APPLICABLE — 此指標不適用於當前運動或視角
LOW_CONFIDENCE — landmark 信心分數不足以計算此指標
VIEW_LIMITED   — 視角受限，無法判斷
```

## 8 條運動學品質規則（Building Blocks）

這些規則是運動學計算的基礎，由 Exercise Template 決定是否啟用與如何解讀。

| # | 規則 | 觸發條件 | 適用模板 |
|---|------|---------|---------|
| 1 | 膝關節側向偏移 | D_knee / D_ankle < 0.8 | squat（正面視角）、lunge |
| 2 | 脊柱彎曲異常 | 肩-髖-膝 夾角偏離 > 15° | squat、deadlift、lunge |
| 3 | 關節過度伸展 | 任一關節角 ≈ 0° 或 180° ± 5° | 全部（需確認可見度） |
| 4 | 雙側不對稱 | 左右同名關節角度差 > 10° | bilateral 模板單幀確認 |
| 5 | 質心偏移 | COM 投影超出支撐多邊形 | 靜止/慢速動作 MONITOR |
| 6 | 急速動作 | 關節角速度 > 600 deg/s | 全部（需 SG 平滑 + 連續幀確認） |
| 7 | 活動度不足 | ROM < 模板定義目標 ROM × 50% | 模板有定義 target ROM 時 |
| 8 | 頸椎過伸 | 耳-肩-髖 偏離 > 15° | 全部（需 landmark 可見度） |

## mock_gemma_feedback 格式

Dashboard 使用 deterministic mock，輸出與未來真實 Gemma prompt 共用同一 JSON 接口：

```json
{
  "source": "mock_gemma_feedback",
  "frame": 184,
  "message": "Elbow depth is sufficient, but hips are starting to sag. Brace your core.",
  "safety_note": "Pose-based coaching only, not medical diagnosis."
}
```

## StructuredMotionReport 格式

```json
{
  "frame": 184,
  "exercise": "push_up",
  "exercise_confidence": 0.86,
  "phase": "descent",
  "rep": 4,
  "metrics": {
    "elbow_angle": 74.2,
    "body_line_deviation": 13.5,
    "tempo_dps": 420.0
  },
  "quality_flags": [
    { "id": "hip_sag", "status": "WARNING", "value": 13.5, "evidence": "pose_based_template_metric" }
  ],
  "not_applicable": [
    { "id": "knee_valgus_fppa", "reason": "not_a_frontal_lower_body_template" }
  ],
  "notes": ["not_medical_diagnosis", "single_camera_pose_based_feedback"]
}
```

## 程式碼慣例

### Python（prototype/）
- Exercise Template 定義放在 `prototype/exercises/`
- Dashboard 放在 `prototype/dashboard/`
- 每個新模板都要有對應的 applicability gate 說明
- `mock_gemma_feedback` 與真實 Gemma prompt 共用同一 JSON schema

### Kotlin
- 遵循 Google Kotlin Style Guide
- 模組間用 sealed class / data class 溝通，避免隱式依賴
- UI Thread 上禁止任何運動學或 LLM 運算

### C++（native/）
- C++17, CMake ≥ 3.18；編譯目標 ARM64（Pixel 8 Pro Tensor G3）
- 函數命名：`snake_case`
- 所有運動學公式先在 `prototype/` 用 Python 驗證，再移植到 C++
- 單元測試放在 `native/tests/`，編譯為桌面端可執行檔

### JNI
- Kinematics Bridge：輸入 `float[99]`（33 keypoints × 3 coords），輸出 StructuredMotionReport JSON
- LLM Bridge：輸入 JSON string，輸出 JSON string（FC 調用結果）
- 所有 JNI 函數加上 `@Keep` annotation

### 共用規範
- MediaPipe 關鍵點索引使用 `PoseLandmark` 命名常數，嚴禁魔術數字（0-32）
- TTS 語音冷卻 ≥ 3 秒間隔
- GGUF 模型、build/、*.apk 不入 Git

## AI 協作規則

### DO
- 先讀 `implementation_plan.md` 確認決策再動手
- 新指標啟用前先確認 applicability gate（哪種運動、哪種視角才適用）
- Python Dashboard 原型通過驗證後，再考慮 C++ 移植
- 輸出回饋前先過 Confidence Gate
- 每個運動模板的 disabled_rules 清單要明確寫清楚

### DON'T
- **不要在不適用的模板輸出 CRITICAL**（如 push_up 輸出 knee_valgus_fppa CRITICAL）
- **不要宣稱精準測量肌肉活化**（MuscleFocusEstimator 是姿態推估，非 EMG）
- **不要產生醫療診斷語句**（只做「矯正指導」，不做「診斷」）
- 不要用單幀不對稱判定單側動作（lunge 等）為 CRITICAL
- 不要跳過 ConfidenceGate 直接餵數據給 LLM
- 不要在 Kotlin 層做運動學運算（全部下沉到 C++）
- 不要宣稱精確關節受力或 force plate 等級數據

## 安全語言規範

| ✅ 可以說 | ❌ 不可以說 |
|----------|-----------|
| movement quality feedback | medical-grade diagnosis |
| pose-based load demand estimate | precise joint force |
| single-camera proxy | muscle activation percentage |
| hips appear to be sagging based on pose | your gluteus medius is inhibited |
| not medical diagnosis | this is a rehabilitation assessment |

## Git 規範

- `main` 保護分支，開發在 `dev`
- Commit 格式：`[scope] 簡述`
  - `[prototype] add squat exercise template`
  - `[dashboard] show NOT_APPLICABLE gates in overlay`
  - `[biomechanics] update safety_monitor applicability`
  - `[finetune] generate 600 FC training examples`
  - `[android] integrate WorkoutSession with structured report`

## 常用指令

```bash
# Python 原型測試
cd prototype && python test_phase1_showcase.py

# C++ 單元測試（桌面端）
cd native/build && ctest --output-on-failure

# Android NDK 建置
./gradlew assembleDebug

# Gemma 4 桌面端推論測試
cd native/llama.cpp && ./llama-cli -m ../../models/gemma4-e4b-q4.gguf -f prompt.txt

# 生成 FC 訓練數據
cd finetune/data && python generate_synthetic.py --count 600
```

## 競賽檢查清單

- [ ] Kaggle Writeup（≤ 1500 words）— 多運動模板架構 + Applicability Gate + Gemma 4 FC 使用
- [ ] YouTube Demo（≤ 3 min）— Squat / Push-up / Lunge 自動偵測 → 結構化回饋 → Gemma coaching
- [ ] GitHub Public Repo — Python Dashboard + C++ 原生層 + 微調腳本 + benchmark
- [ ] Live Demo APK — 可直接安裝到 Pixel 8 Pro 離線運行
- [ ] Media Gallery — 封面圖（Dashboard 截圖 + 品質旗標視覺化）
- [ ] Unsloth Benchmark — 微調前後 FC 準確率對比
