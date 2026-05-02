"""
rep_counter.py — 通用 Rep Counter 狀態機

不依賴動作命名，僅根據主導關節角度變化判斷：
  - 是否已完成一次完整動作循環 (rep)
  - 當前處於動作週期的哪個階段
  - 每 rep 的品質分數 (基於安全規則觸發次數)

狀態機設計：
  動作週期 = T_TOP → DESCENT → BOTTOM → ASCENT → T_TOP

  不對稱動作（如弓箭步）使用單側判定。
  每完成一次 TOP → BOTTOM → TOP 循環計為 1 rep。

使用方式：
  rep_counter = RepCounter(primary_joint="knee")
  for frame_angles in frames:
      is_rep_complete = rep_counter.update(frame_angles, safety_flags)
      if is_rep_complete:
          print(f"Rep {rep_counter.rep_count} complete, quality: {rep_counter.last_rep_quality:.1f}")
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Dict, List, Optional, Tuple

import numpy as np


class RepPhase(Enum):
    """動作週期階段"""
    TOP = "top"              # 起始/結束位置 (關節接近伸直)
    DESCENT = "descent"      # 離心下降
    BOTTOM = "bottom"        # 最低點 (關節最大彎曲)
    ASCENT = "ascent"        # 向心上升


@dataclass
class RepRecord:
    """單次 rep 的記錄"""
    rep_number: int
    frames: int                 # 此次 rep 持續幀數
    duration_sec: float         # 持續秒數
    min_angle: float            # 最低點角度
    max_angle: float            # 最高點角度
    range_of_motion: float      # 活動範圍 (度)
    safety_flags_count: int     # 安全規則觸發次數
    form_quality: float         # 品質分數 0-100
    descent_avg_vel: float      # 下降平均角速度 (deg/s)
    ascent_avg_vel: float       # 上升平均角速度 (deg/s)


class RepCounter:
    """
    通用 Rep 計數器。

    參數：
      primary_joint: 主導關節名稱 (e.g. "left_knee", "right_knee")
      top_threshold: 接近伸直的角度閾值 (度)，預設 > 160° 判定為 TOP
      bottom_threshold: 接近最大彎曲的閾值 (度)，預設 < 110° 視為進入 BOTTOM 範圍
      min_rom: 最低有效活動範圍 (度)，ROM 小於此值不計 rep
      fps: 幀率
      safety_frame_decay: 安全規則觸發對品質的衰減係數 (每次觸發扣幾分)
    """

    def __init__(
        self,
        primary_joint: str = "left_knee",
        top_threshold: float = 160.0,
        bottom_threshold: float = 110.0,
        min_rom: float = 15.0,
        fps: float = 30.0,
        safety_frame_decay: float = 2.0,
    ):
        self.primary_joint = primary_joint
        self.top_threshold = top_threshold
        self.bottom_threshold = bottom_threshold
        self.min_rom = min_rom
        self.fps = fps
        self.safety_frame_decay = safety_frame_decay

        # 狀態
        self.phase: RepPhase = RepPhase.TOP
        self.prev_phase: RepPhase = RepPhase.TOP
        self.rep_count: int = 0
        self.history: List[RepRecord] = []

        # 當前 rep 累積器
        self._rep_frames: int = 0
        self._rep_min_angle: float = 180.0
        self._rep_max_angle: float = 0.0
        self._rep_safety_count: int = 0
        self._rep_descent_vels: List[float] = []
        self._rep_ascent_vels: List[float] = []

        # 前一幀
        self._prev_angle: Optional[float] = None
        self._cycle_started: bool = False
        self._has_reached_bottom: bool = False

    @property
    def last_rep_quality(self) -> float:
        if not self.history:
            return 0.0
        return self.history[-1].form_quality

    @property
    def current_cycle_phase(self) -> float:
        """返回 0-1 的動作週期位置，用於 UI 進度條。"""
        mapping = {RepPhase.TOP: 0.0, RepPhase.DESCENT: 0.33,
                    RepPhase.BOTTOM: 0.5, RepPhase.ASCENT: 0.66}
        return mapping.get(self.phase, 0.0)

    def _determine_phase(self, angle: float) -> RepPhase:
        """
        根據角度判定當前階段。
        使用 hysteresis 避免相位抖動。
        """
        # 階段轉換邏輯（含滯後）
        if self.phase == RepPhase.TOP:
            if angle < self.top_threshold - 5:
                return RepPhase.DESCENT
            return RepPhase.TOP

        elif self.phase == RepPhase.DESCENT:
            if angle < self.bottom_threshold:
                return RepPhase.BOTTOM
            if angle > self.top_threshold:
                return RepPhase.TOP
            return RepPhase.DESCENT

        elif self.phase == RepPhase.BOTTOM:
            if angle > self.bottom_threshold + 5:
                return RepPhase.ASCENT
            return RepPhase.BOTTOM

        elif self.phase == RepPhase.ASCENT:
            if angle > self.top_threshold:
                return RepPhase.TOP
            if angle < self.bottom_threshold:
                return RepPhase.BOTTOM
            return RepPhase.ASCENT

        return self.phase

    def _finalize_rep(self):
        """完成一次 rep 的記錄並重置累積器。"""
        if not self._has_reached_bottom:
            return  # 未真正完成下降，不計 rep

        rom = self._rep_max_angle - self._rep_min_angle
        if rom < self.min_rom:
            # ROM 不足，不計 rep (淺層動作)
            self._reset_accumulators()
            return

        duration = self._rep_frames / self.fps if self.fps > 0 else 0
        descent_vel = np.mean(self._rep_descent_vels) if self._rep_descent_vels else 0
        ascent_vel = np.mean(self._rep_ascent_vels) if self._rep_ascent_vels else 0
        quality = max(0.0, 100.0 - self._rep_safety_count * self.safety_frame_decay)

        record = RepRecord(
            rep_number=self.rep_count + 1,
            frames=self._rep_frames,
            duration_sec=duration,
            min_angle=self._rep_min_angle,
            max_angle=self._rep_max_angle,
            range_of_motion=rom,
            safety_flags_count=self._rep_safety_count,
            form_quality=quality,
            descent_avg_vel=descent_vel,
            ascent_avg_vel=ascent_vel,
        )
        self.history.append(record)
        self.rep_count += 1
        self._reset_accumulators()

    def _reset_accumulators(self):
        self._rep_frames = 0
        self._rep_min_angle = 180.0
        self._rep_max_angle = 0.0
        self._rep_safety_count = 0
        self._rep_descent_vels = []
        self._rep_ascent_vels = []
        self._has_reached_bottom = False

    def update(self, angle: float, safety_flags: int = 0) -> bool:
        """
        每幀更新。

        angle: 主導關節角度 (度)
        safety_flags: 此幀觸發的安全規則數量 (0-8)

        returns: True 表示剛完成一次 rep
        """
        # 階段判定
        self.prev_phase = self.phase
        self.phase = self._determine_phase(angle)

        # 追蹤角速度
        if self._prev_angle is not None:
            vel = abs(angle - self._prev_angle) * self.fps  # deg/s
            if self.phase == RepPhase.DESCENT:
                self._rep_descent_vels.append(vel)
            elif self.phase == RepPhase.ASCENT:
                self._rep_ascent_vels.append(vel)

        # 累積當前 rep 統計
        if self.phase != RepPhase.TOP or self._rep_frames > 0:
            self._rep_frames += 1
            self._rep_min_angle = min(self._rep_min_angle, angle)
            self._rep_max_angle = max(self._rep_max_angle, angle)
            self._rep_safety_count += safety_flags
            self._cycle_started = True

        # 追蹤是否曾到達 BOTTOM
        if self.phase == RepPhase.BOTTOM:
            self._has_reached_bottom = True

        # 完成一次循環
        rep_completed = False
        if self.prev_phase == RepPhase.ASCENT and self.phase == RepPhase.TOP:
            self._finalize_rep()
            rep_completed = self.rep_count > 0

        self._prev_angle = angle
        return rep_completed

    def get_session_summary(self) -> dict:
        """回傳訓練摘要。"""
        if not self.history:
            return {"reps": 0, "avg_quality": 0.0, "avg_rom": 0.0}

        qualities = [r.form_quality for r in self.history]
        roms = [r.range_of_motion for r in self.history]
        return {
            "reps": self.rep_count,
            "avg_quality": float(np.mean(qualities)),
            "min_quality": float(np.min(qualities)),
            "max_quality": float(np.max(qualities)),
            "avg_rom": float(np.mean(roms)),
            "avg_descent_vel": float(np.mean([r.descent_avg_vel for r in self.history])),
            "avg_ascent_vel": float(np.mean([r.ascent_avg_vel for r in self.history])),
        }


# ── 測試 ────────────────────────────────────────────────────────────
if __name__ == "__main__":
    # 模擬一個深蹲 rep 的角度序列 (度)
    # TOP(175°) → DESCENT → BOTTOM(85°) → ASCENT → TOP(175°)
    mock_angles = np.concatenate([
        np.full(10, 175.0),                    # 站立準備
        np.linspace(175, 90, 30),              # 下蹲
        np.full(5, 90.0),                      # 底部停留
        np.linspace(90, 175, 30),              # 起立
        np.full(5, 175.0),                     # 頂點
        np.linspace(175, 80, 25),              # 第二下 (更深)
        np.full(3, 80.0),
        np.linspace(80, 175, 25),              # 起立
        np.full(5, 175.0),
    ])

    # 模擬安全規則觸發 (隨機在底部附近觸發)
    np.random.seed(0)
    mock_safety = np.zeros(len(mock_angles), dtype=int)
    # 在底部位置 (index 35-45) 偶發性觸發
    mock_safety[35:45] = np.random.choice([0, 1, 2], size=10, p=[0.5, 0.3, 0.2])

    counter = RepCounter(primary_joint="left_knee", fps=30.0)

    for i, (angle, safety) in enumerate(zip(mock_angles, mock_safety)):
        completed = counter.update(angle, int(safety))
        if completed:
            r = counter.history[-1]
            print(f"[OK] Rep {r.rep_number} | ROM={r.range_of_motion:.1f} | "
                  f"Quality={r.form_quality:.0f}/100 | "
                  f"Duration={r.duration_sec:.1f}s | "
                  f"Descent={r.descent_avg_vel:.0f}°/s | Ascent={r.ascent_avg_vel:.0f}°/s")

    print(f"\n{'='*50}")
    summary = counter.get_session_summary()
    print(f"Total Reps:   {summary['reps']}")
    print(f"Avg Quality:  {summary['avg_quality']:.1f}/100")
    print(f"Avg ROM:      {summary['avg_rom']:.1f}°")
    print(f"Avg Descent:  {summary['avg_descent_vel']:.1f}°/s")
    print(f"Avg Ascent:   {summary['avg_ascent_vel']:.1f}°/s")
