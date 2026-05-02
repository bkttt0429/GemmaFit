import cv2
import mediapipe as mp
import numpy as np
import json
import math

# 初始化 MediaPipe Pose
mp_pose = mp.solutions.pose
mp_drawing = mp.solutions.drawing_utils
mp_drawing_styles = mp.solutions.drawing_styles

def calculate_angle(a, b, c):
    """計算三點之間的角度"""
    a = np.array(a) # First
    b = np.array(b) # Mid
    c = np.array(c) # End
    
    radians = np.arctan2(c[1]-b[1], c[0]-b[0]) - np.arctan2(a[1]-b[1], a[0]-b[0])
    angle = np.abs(radians*180.0/np.pi)
    
    if angle > 180.0:
        angle = 360 - angle
        
    return angle

def distance(a, b):
    """計算兩點間距離"""
    return math.hypot(a[0]-b[0], a[1]-b[1])

def process_frame(image, pose):
    """處理單幀影像，輸出帶有熱區標示的影像與 JSON 狀態"""
    # 轉換顏色空間
    image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    image_rgb.flags.writeable = False
    
    # 執行姿勢偵測
    results = pose.process(image_rgb)
    
    # 將影像轉換回 BGR 準備繪圖
    image_rgb.flags.writeable = True
    image = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR)
    
    state = {
        "action": "squat",
        "knee_valgus": False,
        "rounded_back": False,
        "forward_lean": False
    }

    if results.pose_landmarks:
        landmarks = results.pose_landmarks.landmark
        h, w, _ = image.shape
        
        # 取得關鍵節點座標 (Pixel)
        def get_coords(landmark_id):
            return [int(landmarks[landmark_id].x * w), int(landmarks[landmark_id].y * h)]

        # --- 節點對應 ---
        l_shoulder = get_coords(mp_pose.PoseLandmark.LEFT_SHOULDER.value)
        r_shoulder = get_coords(mp_pose.PoseLandmark.RIGHT_SHOULDER.value)
        l_hip = get_coords(mp_pose.PoseLandmark.LEFT_HIP.value)
        r_hip = get_coords(mp_pose.PoseLandmark.RIGHT_HIP.value)
        l_knee = get_coords(mp_pose.PoseLandmark.LEFT_KNEE.value)
        r_knee = get_coords(mp_pose.PoseLandmark.RIGHT_KNEE.value)
        l_ankle = get_coords(mp_pose.PoseLandmark.LEFT_ANKLE.value)
        r_ankle = get_coords(mp_pose.PoseLandmark.RIGHT_ANKLE.value)
        l_toe = get_coords(mp_pose.PoseLandmark.LEFT_FOOT_INDEX.value)
        r_toe = get_coords(mp_pose.PoseLandmark.RIGHT_FOOT_INDEX.value)
        
        # --- 1. 膝蓋內夾 (Knee Valgus) ---
        # 比較兩側膝蓋的距離與兩側腳踝的距離
        knee_dist = distance(l_knee, r_knee)
        ankle_dist = distance(l_ankle, r_ankle)
        valgus_ratio = knee_dist / (ankle_dist + 1e-5) # 避免除以零
        
        # 若膝蓋距離遠小於腳踝距離，則判定為內夾
        if valgus_ratio < 0.7:
            state["knee_valgus"] = True
            
        # --- 2. 背部圓潤 / 龜背 (Spinal Flexion) ---
        # 利用肩膀與髖部的相對距離來估算。若側面距離過短，表示背部彎曲。
        # 這裡簡化為：當身軀 Y 軸高度變短，但髖部與膝蓋的角度仍在蹲的狀態
        torso_length = distance(l_shoulder, l_hip)
        # 此處需較多測試數據動態調整閥值，此為範例概念
        # state["rounded_back"] = True (依後續實測補充精確公式)

        # --- 3. 重心前傾 (Excessive Forward Lean) ---
        # 肩膀 X 座標遠大於腳尖 X 座標 (假設面向右側)
        # 判斷是否過度前傾
        if l_shoulder[0] > l_toe[0] + 50:  # 肩膀超過腳尖 50 pixel 
            state["forward_lean"] = True

        # ==========================================
        # 視覺熱區 (Visual Hotspots) 繪製邏輯
        # ==========================================
        
        # 預設先畫上 MediaPipe 標準骨架 (淡色)
        mp_drawing.draw_landmarks(
            image, 
            results.pose_landmarks, 
            mp_pose.POSE_CONNECTIONS,
            landmark_drawing_spec=mp_drawing_styles.get_default_pose_landmarks_style(),
            connection_drawing_spec=mp_drawing.DrawingSpec(color=(200, 200, 200), thickness=2, circle_radius=2)
        )
        
        # 若觸發錯誤，則蓋上粗紅色的熱區標示
        error_color = (0, 0, 255) # BGR 紅色
        error_thickness = 8

        if state["knee_valgus"]:
            # 熱區：兩側的大腿與小腿
            cv2.line(image, tuple(l_hip), tuple(l_knee), error_color, error_thickness)
            cv2.line(image, tuple(l_knee), tuple(l_ankle), error_color, error_thickness)
            cv2.line(image, tuple(r_hip), tuple(r_knee), error_color, error_thickness)
            cv2.line(image, tuple(r_knee), tuple(r_ankle), error_color, error_thickness)
            cv2.putText(image, "WARNING: Knee Valgus!", (50, 50), cv2.FONT_HERSHEY_SIMPLEX, 1, error_color, 2)
            
        if state["rounded_back"]:
            # 熱區：背部中軸 (肩到髖)
            cv2.line(image, tuple(l_shoulder), tuple(l_hip), error_color, error_thickness)
            cv2.line(image, tuple(r_shoulder), tuple(r_hip), error_color, error_thickness)
            cv2.putText(image, "WARNING: Rounded Back!", (50, 90), cv2.FONT_HERSHEY_SIMPLEX, 1, error_color, 2)
            
        if state["forward_lean"]:
            # 熱區：從肩膀到腳尖的警示連線
            cv2.line(image, tuple(l_shoulder), tuple(l_toe), (0, 165, 255), error_thickness) # 橘色警告
            cv2.putText(image, "WARNING: Forward Lean!", (50, 130), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 165, 255), 2)

    return image, state

def main():
    # 開啟攝影機 (或改為讀取測試影片路徑: cv2.VideoCapture("test_squat.mp4"))
    cap = cv2.VideoCapture(0)
    
    with mp_pose.Pose(
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5) as pose:
        
        while cap.isOpened():
            success, image = cap.read()
            if not success:
                print("無法讀取影像 / 影片結束。")
                break
                
            # 翻轉影像讓它看起來像鏡子
            image = cv2.flip(image, 1)

            # 進行姿態計算與熱區繪製
            processed_image, state = process_frame(image, pose)
            
            # 在終端機印出打包好的 JSON 狀態，這是未來傳給 Gemma 4 的內容
            if any(state.values()) and state["action"] == "squat":
                # 這裡只是 Demo，實務上會有平滑化邏輯防止洗版
                print(json.dumps(state, ensure_ascii=False))

            # 顯示結果
            cv2.imshow('GemmaFit - Squat Analyzer Prototype', processed_image)
            
            # 按 'q' 離開
            if cv2.waitKey(5) & 0xFF == ord('q'):
                break
                
    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
