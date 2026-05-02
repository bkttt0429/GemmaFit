"""
smooth_angle.py — Savitzky-Golay 角度平滑濾波 + 角速度計算

使用 Savitzky-Golay 多項式擬合濾波，相較 moving average 更能保留極值特徵。
從平滑後的角度序列計算一階導數（角速度），Rule 6 觸發條件為 > 600 deg/s。

用法：
  from smooth_angle import SavitzkyGolay, compute_angular_velocity
  sg = SavitzkyGolay(window=5, polyorder=2)
  smoothed = sg.filter(angle_series)
  vel_deg_s = compute_angular_velocity(smoothed, fps=30.0)

參考：
  Savitzky & Golay (1964), Analytical Chemistry, 36(8), 1627-1639.
  scipy.signal.savgol_filter 的純 numpy 移植版（無需 scipy 依賴）
"""

import math

import numpy as np


RAPID_MOVEMENT_THRESHOLD_DPS = 600.0


class SavitzkyGolay:
    """
    Savitzky-Golay 濾波器。
    window: 滑動窗口大小（奇數，預設 5）
    polyorder: 多項式階數（預設 2）
    deriv: 導數階數（0 = 平滑值，1 = 一階導數/速度）
    """

    def __init__(self, window: int = 5, polyorder: int = 2, deriv: int = 0):
        if window % 2 == 0:
            raise ValueError("window 必須為奇數")
        if window <= polyorder:
            raise ValueError("window 必須 > polyorder")
        if deriv > polyorder:
            raise ValueError("deriv 必須 <= polyorder")

        self.window = window
        self.polyorder = polyorder
        self.deriv = deriv
        self._coeff = None
        self._half = window // 2

    def _compute_coefficients(self):
        """預先計算摺積係數，只需執行一次。"""
        # 建立 Vandermonde 矩陣 A[i, j] = i^j
        order = self.polyorder
        half = self._half
        x = np.arange(-half, half + 1, dtype=np.float64)
        A = np.vander(x, N=order + 1, increasing=True)

        # (A^T A)^{-1} A^T，取 deriv 階對應的列
        coeff_matrix = np.linalg.pinv(A)
        self._coeff = coeff_matrix[self.deriv]

        # 若 deriv > 0，乘上階乘以得到正確導數尺度
        if self.deriv > 0:
            self._coeff *= float(math.factorial(self.deriv))

    def filter(self, data: np.ndarray) -> np.ndarray:
        """
        對一維訊號做 Savitzky-Golay 濾波。
        data: 1D numpy array (角度序列)
        returns: 與輸入等長的平滑後序列
        """
        if self._coeff is None:
            self._compute_coefficients()

        data = np.asarray(data, dtype=np.float64)
        n = len(data)
        half = self._half

        if n < self.window:
            # 資料太短，直接用原始值
            return data.copy()

        result = np.empty_like(data)

        # 中央部分：標準摺積
        for i in range(half, n - half):
            result[i] = np.dot(self._coeff, data[i - half:i + half + 1])

        # 邊界處理：鏡像填充
        front = 2 * data[0] - data[1:1 + half][::-1]
        back = 2 * data[-1] - data[-half - 1:-1][::-1]

        for i in range(half):
            # 前端
            segment = np.concatenate([front[half - i - 1:], data[:half + i + 1]])
            if len(segment) == self.window:
                result[i] = np.dot(self._coeff, segment)
            else:
                result[i] = data[i]
            # 後端
            segment = np.concatenate([data[n - half - i - 1:], back[:half + i]])
            if len(segment) == self.window:
                result[n - 1 - i] = np.dot(self._coeff, segment)
            else:
                result[n - 1 - i] = data[n - 1 - i]

        return result


def create_velocity_filter(window: int = 7, polyorder: int = 3):
    """
    建立角速度計算專用的 Savitzky-Golay 濾波器 (deriv=1)。
    稍大的 window (7) 可抑制角度抖動對速度的放大效應。
    """
    return SavitzkyGolay(window=window, polyorder=polyorder, deriv=1)


def compute_angular_velocity(
    angles: np.ndarray,
    fps: float = 30.0,
    window: int = 7,
    polyorder: int = 3,
    smooth_first: bool = True,
) -> np.ndarray:
    """
    從角度序列計算角速度 (deg/s)。

    angles: 1D numpy array，連續幀的關節角度 (度)
    fps: 攝影機幀率
    smooth_first: 是否先平滑角度再計算導數（推薦 True）
    returns: 角速度序列 (deg/s)，與輸入等長

    Rule 6 觸發條件：abs(angular_velocity) > 600 deg/s
    """
    if smooth_first:
        sg_smooth = SavitzkyGolay(window=5, polyorder=2, deriv=0)
        angles = sg_smooth.filter(angles)

    sg_vel = create_velocity_filter(window=window, polyorder=polyorder)
    vel_per_frame = sg_vel.filter(angles)
    return vel_per_frame * fps  # 轉換為 deg/s


def detect_rapid_movement(
    velocities: np.ndarray,
    threshold: float = RAPID_MOVEMENT_THRESHOLD_DPS,
    consecutive_frames: int = 3,
) -> np.ndarray:
    """
    偵測急速動作 (Rule 6)。

    velocities: 角速度序列 (deg/s)
    threshold: 觸發閾值 (deg/s)，預設 600 deg/s
    consecutive_frames: 連續觸發幀數閾值（防抖）
    returns: bool array，True = 該幀為急速動作
    """
    exceeded = np.abs(velocities) > threshold

    if len(exceeded) < consecutive_frames:
        return exceeded

    # 連續幀確認：需連續 N 幀都超標才標記
    confirmed = np.zeros_like(exceeded, dtype=bool)
    count = 0
    for i in range(len(exceeded)):
        if exceeded[i]:
            count += 1
        else:
            count = 0
        if count >= consecutive_frames:
            confirmed[i - consecutive_frames + 1:i + 1] = True
    return confirmed


# ── 測試 ───────────────────────────────────────────────────────────
if __name__ == "__main__":
    # 生成含雜訊的模擬角度序列
    np.random.seed(42)
    t = np.linspace(0, 2 * np.pi, 200)
    true_angle = 40 * np.sin(t) + 90       # 模擬膝蓋角度: 50° ~ 130°
    noisy = true_angle + np.random.normal(0, 3, len(t))
    fps = 30.0

    # Savitzky-Golay 平滑
    sg = SavitzkyGolay(window=5, polyorder=2)
    smoothed = sg.filter(noisy)

    # 計算角速度
    vel = compute_angular_velocity(noisy, fps=fps)

    # Rule 6 偵測
    rapid = detect_rapid_movement(vel, threshold=RAPID_MOVEMENT_THRESHOLD_DPS, consecutive_frames=3)

    # Controlled rapid segment for the demo: 120 degrees over 4 samples at 30 FPS.
    rapid_demo_angles = np.concatenate([
        np.full(12, 60.0),
        np.linspace(60.0, 180.0, 4),
        np.full(34, 180.0),
    ])
    rapid_demo_vel = compute_angular_velocity(rapid_demo_angles, fps=fps)
    rapid_demo_flags = detect_rapid_movement(
        rapid_demo_vel,
        threshold=RAPID_MOVEMENT_THRESHOLD_DPS,
        consecutive_frames=3,
    )

    # 輸出總結
    print(f"輸入幀數: {len(noisy)}")
    print(f"角度範圍: {noisy.min():.1f}° ~ {noisy.max():.1f}°")
    print(f"平滑後範圍: {smoothed.min():.1f}° ~ {smoothed.max():.1f}°")
    print(f"角速度範圍: {vel.min():.1f} ~ {vel.max():.1f} deg/s")
    print(f"RMSE (noisy vs true): {np.sqrt(np.mean((noisy - true_angle) ** 2)):.2f}°")
    print(f"RMSE (smoothed vs true): {np.sqrt(np.mean((smoothed - true_angle) ** 2)):.2f}°")
    print(f"急速幀數: {rapid.sum()} / {len(rapid)}")
    print(
        f"受控急速段: max={np.max(np.abs(rapid_demo_vel)):.1f} deg/s, "
        f"flags={rapid_demo_flags.sum()} / {len(rapid_demo_flags)}"
    )

    if rapid.sum() > 0:
        rapid_frames = np.where(rapid)[0]
        print(f"急速幀索引: {rapid_frames[:10].tolist()}{'...' if len(rapid_frames) > 10 else ''}")
