# GemmaFit 資料目錄說明

## 資料夾結構

```
data/
├── raw/                        # 從公開來源下載的原始資料
│   ├── squat_zenodo/           # Zenodo Squat Dataset（Good/Bad Back/Bad Heel）
│   ├── squat_kaggle/           # Kaggle Squat Exercise Pose Dataset
│   ├── fitness_pose_classification/  # Kaggle 多動作姿態分類
│   └── exercise_timeseries/    # Exercise Recognition Time Series（33 landmarks）
│
├── processed/                  # 從原始資料萃取/轉換後的資料
│   ├── landmarks/              # MediaPipe 格式 33 landmarks CSV/JSON
│   ├── angles/                 # 關節角度特徵（膝蓋角、軀幹角、髖角等）
│   └── phase_labels/           # 深蹲階段標籤（top/down/bottom/up）
│
├── gemma_finetune/             # Gemma 微調用語料
│   ├── prompts/                # 姿態狀態 JSON（輸入）
│   └── responses/             # 教練回饋文字（輸出）
│
└── validation/                 # 閾值驗證與測試結果
    ├── threshold_tests/        # 不同閾值設定的測試紀錄
    └── results/                # 分類準確率、混淆矩陣等結果
```

---

## 各資料集下載說明

### 1. Zenodo Squat Dataset（⏳ 下載中 824 MB）

- **用途**：影像層驗證 — Bad Back、Bad Heel、Good 三分類
- **內容**：側面深蹲影像，含 Good / Bad Back / Bad Heel 三類
- **下載**：[zenodo.org/records/17558630](https://zenodo.org/records/17558630)
- **存放位置**：`raw/squat_zenodo/Dataset.zip` → 解壓後為影像資料夾
- **狀態**：背景下載中，完成後需 extract_landmarks.py 轉換

### 2. Kaggle Squat Exercise Pose Dataset ✅

- **用途**：閾值驗證 — 6 種錯誤標籤（0=正確, 1=淺蹲, 2=前傾, 3=膝內夾, 4=腳跟抬起, 5=不對稱）
- **內容**：預計算角度特徵 CSV（無原始 landmarks）
- **檔案**：`squat_dataset/squat_features_augmented.csv`
- **欄位**：knee_angle, hip_angle, ankle_angle, spine_angle, torso_lean, knee_lateral, symmetry_score, hip_depth, label
- **NOTE**：合成資料（原始影像角度 + 人工擾動），可直接對應規則閾值

### 3. Gym Exercise MediaPipe 33-Landmarks ✅（替代競賽資料集）

- **用途**：手臂動作參考（bicep, shoulder, tricep），用途偏限
- **內容**：2700 幀 × 33 landmarks（class, x1..z33, v1..v33）
- **存放位置**：`raw/fitness_pose_classification/dataset_all_points.csv`

### 4. Exercise Recognition Time Series Dataset ✅

- **用途**：多動作連續幀驗證 — movement_classifier、相位偵測、Rep Counter
- **內容**：83,922 幀 × 33 landmarks（x,y,z），448 個影片，5 種動作（jumping_jack 107, pull_up 101, push_up 99, situp 78, squat 63）
- **檔案**：`landmarks.csv`（幀×33landmarks）, `angles.csv`（預算角度）, `labels.csv`（vid_id→class）
- **NOTE**：最直接可用資料 — 33 landmarks 格式與 MediaPipe Pose 相容

---

## 資料使用流程

```
raw/ 原始資料
  ↓ (extract_landmarks.py)
processed/landmarks/  33 landmarks CSV
  ↓ (compute_angles.py)
processed/angles/     關節角度特徵
  ↓ (label_phases.py)
processed/phase_labels/  動作階段標籤
  ↓ (validate_thresholds.py)
validation/           閾值驗證結果
  ↓ (generate_finetune_data.py)
gemma_finetune/       Gemma 微調語料
```

---

## Gemma 微調語料格式

### prompts/ 輸入範例
```json
{
  "exercise": "squat",
  "phase": "bottom",
  "errors": ["bad_back"],
  "severity": "high",
  "confidence": 0.91,
  "angles": {
    "torso_angle": 42.3,
    "knee_angle": 88.5,
    "hip_angle": 71.2
  }
}
```

### responses/ 輸出範例
```
背部有明顯彎曲，請先減少下蹲深度，挺胸並讓核心收緊，想像背後有一面牆要靠著。
```

---

## 三大錯誤判斷對應資料

| 錯誤類型 | 驗證資料集 | 判斷特徵 |
|---------|-----------|---------|
| 膝蓋內夾 (Knee Valgus) | squat_kaggle | 膝距 / 踝距比例 < 0.8 |
| 龜背 (Bad Back) | squat_zenodo | 軀幹角度、肩髖線偏差 |
| 重心前傾 / 腳跟抬起 | squat_zenodo | 腳跟 landmark 相對高度、肩膀投影 |
