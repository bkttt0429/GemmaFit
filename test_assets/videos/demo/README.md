# GemmaFit Demo Video Assets

## Full Feature Demo

- File: `gemmafit_full_feature_demo_pexels_squat_720p.mp4`
- Source page: https://www.pexels.com/video/a-woman-in-activewear-doing-squats-at-home-8837221/
- Creator: MART PRODUCTION
- License: Pexels License
- Original downloaded file: `test_assets/videos/demo_candidates/pexels_squat_front_8837221_download.mp4`
- Prepared format: H.264 MP4, 720x1366, 25 fps, 25.56 s, no audio

Why this is the preferred app demo asset:

- Single visible subject with stable identity.
- Full body visible for the whole clip.
- Repeated squat movement gives clear rep, tempo, range-of-motion, skeleton overlay, and evidence-card behavior.
- App-style MediaPipe sampling passed 52/52 renderable pose samples.
- Person proposal validation passed 49/49 frames.
- Kalman tracking validation passed with one stable track, zero switches, and zero subject-lost frames.
- Formula validation passed with 213 landmark/angle frames.

Use this clip to demonstrate movement-quality feedback and evidence gates. Do not frame it as a medical, diagnostic, fall-risk, or rehabilitation-prescription result.

## Real Senior Demo

- File: `gemmafit_real_senior_supported_chair_squat_720p.mp4`
- Source file: `D:\GemmaFit\test_assets\Real\800432435.743618.mp4`
- Prepared format: H.264 MP4, 720x1280, 29.97 fps, 16.15 s, no audio

Why this is the preferred real-world senior demo asset:

- Real recorded older-adult-style supported chair squat / sit-to-stand movement.
- Single visible subject with a stable side view.
- Chair support is visible, which matches senior-safe strength-support framing.
- App-style MediaPipe sampling passed 33/33 renderable pose samples.
- Person proposal validation passed 32/32 frames.
- Kalman tracking validation passed with one stable track, zero switches, and zero subject-lost frames.
- Formula validation passed with 162 landmark/angle frames.

Notes:

- This is the best real senior context clip in `test_assets/Real`.
- `800432268.335036.mp4` is a strong secondary option for front-view sit-to-stand, but the subject moves closer to the camera and the outdoor backlight is stronger.
- `IMG_7708.MOV` and `800431965.019943.mp4` are usable but less ideal because of backlight.
- `800432546.545269.mp4` is useful for hinge/forward-lean stress testing, but it is less clean as a first demo.
