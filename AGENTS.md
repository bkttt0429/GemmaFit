# AGENTS.md — GemmaFit 專案 AI 協作指引

## 專案概述
GemmaFit 是 Kaggle Gemma 4 Impact Challenge 參賽作品。目標是在 Pixel 8 Pro 上運行離線 AI 健身教練，
使用 MediaPipe Pose + Gemma 4 (llama.cpp, Function Calling) 打造**通用生物力學安全引擎**。
基於 8 條物理規則即時偵測任何徒手訓練姿勢中的安全風險，並給予語音指導。目標人群為一般健身初學者。

- **時程**：19 天衝刺 (Deadline: May 18, 2026)
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
│   ├── functions/           # FunctionRegistry (8 個通用 FC 工具)
│   ├── session/             # WorkoutSession (組數/次數/Form Score)
│   ├── voice/               # CoachVoice (TTS + 冷卻機制)
│   └── ui/                  # UIOverlay (骨架 + 安全熱區)
├── native/                  # C++ NDK 原生層
│   ├── CMakeLists.txt
│   ├── kinematics/
│   │   ├── joint_angles.cpp         # 12 主要關節角度計算
│   │   ├── body_segments.cpp        # 肢段方向向量
│   │   ├── symmetry.cpp             # 雙側對稱度
│   │   ├── com_tracker.cpp          # 質心投影 vs 支撐底面積
│   │   ├── safety_monitor.cpp       # 8 條通用安全規則
│   │   ├── movement_classifier.cpp  # 物理模式識別 (非動作命名)
│   │   ├── muscle_focus.cpp         # 基於姿態的肌群參與推估
│   │   └── confidence_gate.cpp      # 信心分數攔截
│   ├── bridge/
│   │   ├── kinematics_bridge.cpp    # JNI: float[99] → ErrorReport[] + MovementPattern + MuscleFocusEstimate
│   │   └── llm_bridge.cpp           # JNI: JSON → llama.cpp → FC 調用
│   └── llama.cpp/                  # llama.cpp 子模組 (git submodule)
├── prototype/               # Python 原型（運動學驗證，開發階段用）
├── finetune/                # Unsloth Colab 微調腳本 + 數據集
│   ├── train_gemma4_fc.ipynb
│   └── data/
│       ├── generate_synthetic.py
│       └── fc_training_data.json
├── docs/                    # 論文參考、影片腳本、競賽素材
└── implementation_plan.md   # 總體規劃文件
```

## 資料來源與存放規則

| 資料類型 | 存放路徑 | 版控 | 說明 |
|---------|---------|------|------|
| **合成微調數據集** | `finetune/data/` | ✅ Git | FC 訓練用 JSON，由 `generate_synthetic.py` 生成 |
| **Unsloth 微調輸出** | `finetune/output/` | ❌ .gitignore | LoRA adapter、合併後模型、訓練 log |
| **GGUF 量化模型** | `models/` | ❌ .gitignore | Gemma 4 E4B Q4_K_M.gguf，手動放置 |
| **Python 原型腳本** | `prototype/` | ✅ Git | 運動學驗證用，每個 `.cpp` 對應一個原型 |
| **C++ 單元測試** | `native/tests/` | ✅ Git | 桌面端可執行測試，以 `test_*.cpp` 命名 |
| **C++ 測試固定資料** | `native/tests/fixtures/` | ✅ Git | 預錄骨架 JSON / CSV，用於可重現測試 |
| **學術論文 / 參考文獻** | `docs/papers/` | ✅ Git | PDF 或 BibTeX 引用 |
| **影片腳本 / 分鏡** | `docs/scripts/` | ✅ Git | Demo 影片腳本、對白稿 |
| **Benchmark 結果** | `docs/benchmark/` | ✅ Git | 微調前後 FC 準確率、推論延遲圖表 |
| **競賽素材 (截圖/封面)** | `docs/assets/` | ✅ Git | App 截圖、Media Gallery 用圖 |
| **API 金鑰 / 憑證** | `.env` | ❌ .gitignore | Kaggle API token 等，嚴禁提交 |
| **Android 字串/資源** | `app/src/main/res/` | ✅ Git | UI 文字、圖示、顏色主題 |
| **NDK 建置產物** | `build/`, `native/build/` | ❌ .gitignore | `.so`、`.apk`、`.aab` |

### 模型檔案取得流程
1. 在 Colab 執行 `finetune/train_gemma4_fc.ipynb` → 匯出 GGUF
2. 下載 `.gguf` 至 `models/gemma4-e4b-q4.gguf`
3. 桌面端驗證：`./llama-cli -m models/gemma4-e4b-q4.gguf -f prompt.txt`
4. Android 端部署：`adb push models/gemma4-e4b-q4.gguf /sdcard/Android/data/.../files/`

### .gitignore 關鍵規則
```
.env
models/*.gguf
finetune/output/
build/
native/build/
*.apk
*.aab
```

## 架構總覽

```
CameraX → MediaPipeTask → JNI Bridge → C++ Native Layer (.so)
                                          ├── Biomechanics Engine
                                          │   ├── joint_angles.cpp
                                          │   ├── body_segments.cpp
                                          │   ├── symmetry.cpp
                                          │   ├── com_tracker.cpp
                                          │   ├── safety_monitor.cpp (8 rules)
                                          │   ├── movement_classifier.cpp
                                          │   ├── muscle_focus.cpp
                                          │   └── confidence_gate.cpp
                                          └── llama.cpp Engine
                                              └── Gemma 4 GGUF 4-bit + FC 8 tools

C++ → JNI Bridge → Kotlin → FunctionRegistry → TTS / UIOverlay
```

## 核心設計原則

1. **通用物理規則，不硬編碼動作名稱** — 系統不判斷「這是深蹲」，只輸出物理描述 (`bilateral_knee_dominant_sagittal`) + 安全異常
2. **安全優先** — Confidence Gate 閘門必須在任何 LLM 推論前執行
3. **效能關鍵路徑在 C++** — 每幀運動學計算目標 < 1ms，不經 Kotlin GC
4. **Function Calling > 自由文字生成** — LLM 產出必須限縮在 8 個預定義 FC 工具，確保輸出安全可控
5. **肌群推估非診斷** — MuscleFocusEstimator 是基於姿態的推論，不宣稱精準測量肌肉活化

## 8 條通用安全規則

| # | 規則 | 觸發條件 |
|---|------|---------|
| 1 | 膝關節側向偏移 | D_knee / D_ankle < 0.8 |
| 2 | 脊柱彎曲異常 | 肩-髖-膝 夾角偏離 > 15° |
| 3 | 關節過度伸展 | 任一關節角 ≈ 0° 或 180° ± 5° |
| 4 | 雙側不對稱 | 左右同名關節角度差 > 10° |
| 5 | 質心偏移 | COM 投影超出支撐多邊形 |
| 6 | 急速動作 | 關節角速度 > 60°/幀 |
| 7 | 活動度不足 | ROM < 預期安全 ROM 的 50% |
| 8 | 頸椎過伸 | 耳-肩-髖 偏離 > 15° |

## 8 個通用 FC 工具

| 函數名稱 | 對應規則 |
|---------|---------|
| `correct_knee_alignment(side, ratio, severity)` | #1 |
| `correct_spinal_alignment(deviation, region)` | #2, #8 |
| `correct_joint_angle(joint, current, safe_range)` | #3 |
| `correct_asymmetry(joint, left, right)` | #4 |
| `warn_com_offset(direction, distance)` | #5 |
| `warn_rapid_movement(joint, velocity)` | #6 |
| `increase_range_of_motion(joint, current, target)` | #7 |
| `positive_reinforcement(pattern, streak)` | 無異常 30 幀+ |

## 肌群推估規則表

動作模式由主導關節、動作平面、支撐型態判定：

| 動作模式 | 主要肌群 | 次要肌群 | 判斷依據 |
|---------|---------|---------|---------|
| 膝主導蹲起 | 股四頭肌、臀大肌 | 腿後、小腿、核心 | 膝髖同步屈伸 |
| 髖主導折髖 | 臀大肌、腿後肌群 | 豎脊肌、核心 | 髖角變化 > 膝角 |
| 水平推 | 胸大肌、肱三頭、前三角 | 前鋸肌、核心 | 肘伸展 + 肩水平內收 |
| 垂直推 | 三角肌、肱三頭 | 上斜方、核心 | 肩屈曲/外展 + 肘伸展 |
| 水平拉 | 菱形肌、斜方中束、背闊肌 | 肱二頭、後三角 | 肩伸展 + 肘屈曲 |
| 垂直拉 | 背闊肌、肱二頭 | 下斜方、核心 | 肩內收 + 肘屈曲 |
| 單腳穩定 | 臀中肌、踝穩定肌 | 核心、內收肌 | 單腳支撐 + 骨盆控制 |
| 核心抗伸展 | 腹直肌、腹橫肌、腹斜肌 | 臀肌、肩穩定 | 撐地 + 軀幹直線 |
| 核心抗旋轉 | 腹斜肌、深層核心 | 臀中肌、背肌 | 身體抗水平旋轉 |

### 肌群推估用語規範

| ✅ 可以說 | ❌ 不可以說 |
|----------|-----------|
| 根據姿勢推估，這個動作主要會用到股四頭肌與臀大肌 | 你的臀大肌啟動不足 30% |
| 主要負荷可能落在大腿前側與臀部 | 你的臀中肌失能 |
| 核心需要收緊來穩定軀幹 | 你的腹橫肌沒有啟動 |
| 推估訓練肌群 / 可能負荷區域 | 精準分析肌肉做功 |

## 程式碼慣例

### Kotlin
- 遵循 Google Kotlin Style Guide
- 模組間用 sealed class / data class 溝通，避免隱式依賴
- UI Thread 上禁止任何運動學或 LLM 運算

### C++ (native/)
- C++17, CMake ≥ 3.18
- 編譯目標 ARM64 (Pixel 8 Pro Tensor G3)
- 函數命名：`snake_case`
- 所有運動學公式先在 `prototype/` 用 Python 驗證，再移植到 C++
- 單元測試放在 `native/tests/`，編譯為桌面端可執行檔

### JNI
- Kinematics Bridge：輸入 `float[99]` (33 keypoints × 3 coords)，輸出 JSON string
- LLM Bridge：輸入 JSON string，輸出 JSON string (FC 調用結果)
- 所有 JNI 函數加上 `@Keep` annotation

### 其他
- MediaPipe 關鍵點索引使用 `PoseLandmark` 命名常數，嚴禁魔術數字 (0-32)
- TTS 語音冷卻 ≥ 3 秒間隔
- GGUF 模型、build/ 目錄、*.apk 不入 Git

## AI 協作規則

### DO
- 先讀 `implementation_plan.md` 確認決策再動手
- Python 原型通過驗證後，才開始 C++ 移植
- 每個 `kinematics/*.cpp` 保持獨立可單元測試
- 所有運動指導輸出必須引用 NASM 指南確保安全
- 單幀 C++ 運算目標延遲 < 1ms (含 JNI 開銷)

### DON'T
- **不要對動作做命名分類**（不判斷「這是深蹲」），只輸出物理描述 + 安全異常
- **不要宣稱精準測量肌肉活化** — MuscleFocusEstimator 是姿態推估，非 EMG
- **不要產生醫療診斷語句**（只做「矯正指導」，不做「診斷」；不宣稱某肌肉無力或失能）
- 不要引入非必要的外部依賴
- 不要跳過 ConfidenceGate 直接餵數據給 LLM
- 不要在 Kotlin 層做運動學運算（全部下沉到 C++）

## Git 規範
- `main` 保護分支，開發在 `dev`
- Commit 格式：`[scope] 簡述`
  - `[biomechanics] 完成 safety_monitor 8 條規則`
  - `[biomechanics] 完成 muscle_focus 肌群推估`
  - `[jni] 串接 kinematics_bridge JNI`
  - `[finetune] 生成 600 筆通用 FC 訓練數據`
  - `[android] 完成 WorkoutSession 與 TTS 整合`

## 常用指令
```bash
# Python 原型測試
cd prototype && python test_safety_rules.py

# C++ 單元測試 (桌面端)
cd native && mkdir -p build && cd build && cmake .. && make && ./test_kinematics

# Android NDK 建置
./gradlew assembleDebug

# llama.cpp 桌面端模型推論測試
cd native/llama.cpp && make && ./llama-cli -m ../models/gemma4-e4b-q4.gguf -f prompt.txt

# Unsloth 微調（在 Colab 執行）
# 見 finetune/train_gemma4_fc.ipynb
```

## 競賽檢查清單
- [ ] Kaggle Writeup (≤1500 words) — 通用生物力學引擎架構 + Gemma 4 FC 使用 + 挑戰與解決方案
- [ ] YouTube Demo 影片 (≤3 min) — 問題引入 → 自由模式多動作實測 → 安全偵測展示 → 願景
- [ ] GitHub Public Repo — C++/Kotlin 程式碼 + 微調腳本 + benchmark
- [ ] Live Demo — APK 可直接安裝到 Pixel 8 Pro 離線運行
- [ ] Media Gallery — 封面圖 (含 App UI + 安全熱區疊加效果)
- [ ] Unsloth Fine-tuning Benchmark — 微調前後 FC 準確率對比
