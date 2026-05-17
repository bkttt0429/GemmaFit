# MotionZip-V4 時序證據壓縮

## 摘要

MotionZip-V4 是 GemmaFit 確保安全的時序證據壓縮層。它借鑑了 DeepSeek V4 混合注意力機制背後的系統概念：保留近期的細節，將中程上下文壓縮為索引區塊，並對長程歷史記錄進行深度總結。在 GemmaFit 中，這應用於語言模型外部，在 E2B 看到任何內容之前。

MotionZip 不會改變 Gemma 4/E2B 的架構。它改變的是應用程式端的資料包，該資料包將基於姿態推導出的時間序列轉換為緊湊、可稽核的動作證據。

```text
姿態串流
-> 滑動動作視窗
-> 類似 CSA 的事件區塊
-> 類似 HCA 的會話總結
-> 證據資料包
-> E2B 功能/報告/拒絕
```

## 為什麼需要這樣做

原始影片和原始骨架串流過於龐大、嘈雜且不安全，無法直接傳遞給本地語言模型。簡單的均勻影格採樣可能會遺漏短暫但與安全相關的事件。單一影格的總結則會失去時間上的意義。

MotionZip 保留了有用的部分：

- 用於即時解讀的近期局部細節
- 事件邊界和動作極值
- 低信心度 / 目標丟失的片段
- 階段或基本動作的轉換
- 緊湊的會話層級總結
- 證據參考與無法判斷的邊界

目標不是完美的動作識別。目標是產生一個小型資料包，它既保留了有界限教練所需之證據，又能輕易阻擋無根據的聲明。

## DeepSeek V4 類比

| DeepSeek V4 機制 | GemmaFit MotionZip 對應機制 |
| --- | --- |
| 滑動視窗局部注意力 (Sliding-window local attention) | 為即時狀態保留最新姿態/特徵的較高細節。 |
| 壓縮稀疏注意力 (Compressed Sparse Attention, CSA) | 將 4-8 個影格分組壓縮為事件區塊，並對重要區塊進行索引。 |
| 閃電索引器 (Lightning indexer) | 選擇次數邊界、極值、不穩定、遮擋和目標切換的區塊。 |
| 深度壓縮注意力 (Heavily Compressed Attention, HCA) | 將長程會話歷史折疊為次數、節奏趨勢、穩定性計數以及被阻擋的聲明。 |
| 混合注意力 (Hybrid attention) | E2B 接收近期上下文 + 關鍵事件區塊 + 全局總結。 |

這僅為工程上的類比。GemmaFit 並沒有修改 Transformer 注意力機制、KV 快取或 Gemma 模型權重。

## 壓縮契約

MotionZip 可以丟棄多餘的影格細節，但絕對不能丟棄攸關安全的證據。

務必保留：

- 信心度下限
- 姿態所有權 / 目標追蹤狀態
- 目標丟失或預測的片段
- 角度極值
- 速度峰值
- 次數/事件邊界
- 穩定性事件
- 階段或基本動作轉換邊界
- 視野受限 / 低信心度原因
- 無根據的醫療/受力/肌電圖(EMG)聲明邊界
- 任何下游輸出所使用的證據參考

絕對不推斷或儲存：

- 受力
- 地面反作用力 (Ground reaction force)
- 關節力矩 (Joint moment)
- 韌帶應變
- 肌電圖 (EMG)
- 肌肉啟動
- 無感測器情況下的心率狀態
- 跌倒風險評分
- 診斷或復健進度

## P0 資料包結構

```json
{
  "schema_version": "motion_zip_v4_v1",
  "window_id": "motion.rep.1",
  "trigger": "REP_COMPLETED",
  "sliding_window": {
    "last_ms": 1600,
    "frames_kept": 24,
    "reason": "recent_motion_context"
  },
  "compressed_sparse_blocks": [
    {
      "block_id": "motion.rep.1.block.rep_completed",
      "compression_mode": "csa_like_event_block",
      "time_range_ms": [2400, 3200],
      "tokens": ["low_stable", "upward_transition", "high_stable"],
      "preserved_extrema": {
        "knee_angle_min": 78,
        "knee_angle_max": 166,
        "peak_velocity_deg_s": 43,
        "confidence_floor": 0.82
      },
      "event_score": 0.91,
      "evidence_refs": [
        "metric.motion.rom",
        "layer2.event.rep_completed"
      ]
    }
  ],
  "heavily_compressed_summary": {
    "completed_reps": 12,
    "tempo_band": "controlled",
    "stability_events": 1,
    "confidence_floor": 0.82
  },
  "safety_preserved": [
    "confidence_floor",
    "angle_extrema",
    "velocity_peak",
    "event_boundary",
    "evidence_refs",
    "unsupported_claim_boundaries"
  ],
  "limits": [
    "derived_from_single_camera_pose",
    "no_force_or_grf",
    "no_emg_or_muscle_activation"
  ]
}
```

## 與第二層 (Layer 2) 的關係

第二層 (Layer 2) 產生確定性的活動、階段、事件、子動作、規則策略和棄權 (abstain) 證據。MotionZip 使用 `MotionFeatureWindow` 來壓縮這些輸出；它並不取代第二層。

```text
Layer2TemporalInterpreter (第二層時序解譯器)
-> Layer2Output (第二層輸出)

TemporalMotionAnalyzer (時序動作分析器)
-> MotionFeatureWindow (動作特徵視窗)

MotionZipPacketBuilder (MotionZip資料包建立器)
-> MotionZipPacket (MotionZip資料包)
```

如果第二層棄權，或將某個影格降級為僅監控 (monitor-only)，MotionZip 必須將該狀態向後傳遞。E2B 可以解釋此資料包，但不能推翻這個確定性的閘門判斷。

## 實作階段

### P0：僅限除錯資料包

- 從 `MotionFeatureWindow + Layer2Output` 建立 `MotionZipPacket`。
- 將已完成次數事件的資料包新增至除錯記錄中。
- 暫不更改模型提示 (prompt) 格式。
- 單元測試：壓縮過程能保留極值、信心度下限、事件參考以及不支援之限制。

### P1：提示整合

- 當 Token 預算允許時，將 MotionZip 資料包新增至 E2B 事件資料包中。
- 新增驗證器，要求所有引用的參考必須存在。
- 當資料包缺少證據參考或處於低信心度狀態時，增加後備 (fallback) 機制。

### P2：事件索引器

- 在整個會話中維持一個滾動區塊儲存 (rolling block store)。
- 根據事件分數與安全相關性對區塊進行索引。
- 選擇頂級區塊用於會話總結和人物角色報告。

### P3：學習序列層

- 可選：僅基於推導特徵的小型 TCN/GRU。
- 用於提供階段/事件建議，而非最終的安全裁定。
- 確定性的閘門仍保有絕對權威。

## 驗收標準

- MotionZip 中不儲存任何原始影片。
- MotionZip 中不儲存任何完整的原始骨架串流。
- 攸關安全的極值和信心度下限必須能在壓縮後留存。
- `person_tracking_state=predicted/lost` 絕對不能因為壓縮而變成強硬的判斷。
- 只有在通過能力契約和第二層閘門之後，E2B 才能接收緊湊證據。
- 除錯資料包能顯示某個結論被判定、被監控或被棄權的原因。
