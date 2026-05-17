# Pixel demo flow smoke - 2026-05-16

## Goal

Validate the nearest demo path on a Pixel 8 Pro:

1. Launch GemmaFit with a local video.
2. Run deterministic video analysis.
3. Prewarm official `Gemma-4-E2B-it` LiteRT-LM.
4. Stream the session summary.
5. Open the real Summary UI.
6. Show evidence refs, timing, and user-readable reasons for flagged events.

## Device and artifact

- Device: Pixel 8 Pro
- App: debug APK installed with `adb install -r`
- Model: `/storage/emulated/0/Android/data/com.gemmafit/files/gemma-4-E2B-it.litertlm`
- Demo video: `test_assets/outputs/video_realtime_ai_chair_sit_to_stand_demo_20260513_204533_h264.mp4`
- Device video path: `/data/user/0/com.gemmafit/files/test_videos/gemmafit_demo_chair.mp4`

External `/sdcard/Android/data/...` file paths were not used for the final run because the app hit Android storage/FUSE permission denial during the first smoke attempt. The stable debug route is `adb push` to `/data/local/tmp`, then `run-as com.gemmafit` copy into app internal files.

## Result

| Check | Result | Evidence |
|---|---:|---|
| APK builds | PASS | `:app:compileDebugKotlin`, `:app:assembleDebug` |
| APK installs | PASS | `adb install -r` |
| UI video path opens | PASS | `ui_after_ui_fix_analysis.png` |
| Full analysis completes | PASS | `analysis_pass_complete`, `pose_hits=39`, `pose_misses=0`, `hit_rate=1` |
| Summary button tappable | PASS | `ui_after_ui_fix_summary_screen.png` |
| Summary screen opens | PASS | `ui_after_ui_fix_summary_detail.png` |
| Local Gemma summary returns | PASS | `fallback=false`, `function=create_care_activity_log` |
| First token under 5s | PASS | `first_token_ms=3270` |
| User-facing reason for flags | PASS | `ui_why_flagged_full_width.png` |
| Main app still alive after summary | PASS | `pid_main_why_flagged_full_width.txt` |
| Debug process still alive after summary | PASS | `pid_debug_why_flagged_full_width.txt` |

## Key timings

Final full-width "why flagged" run:

- Full analysis: `1876 ms`
- Pose hits: `39/39`
- Summary inference: `33514 ms`
- LiteRT generate content: `18805 ms`
- Streaming first token: `3270 ms`
- Streaming token count: `135`
- Summary function: `create_care_activity_log`
- Summary backend: `litert-lm:isolated:gpu`

Earlier run after the bottom-bar fix:

- Full analysis: `2818 ms`
- Pose hits: `39/39`
- Summary inference: `41047 ms`
- Streaming first token: `3202 ms`
- Summary screen tap succeeded.

## UI fixes validated

### Bottom action bar

The original Pixel smoke found that the `View Summary` action sat too close to the system navigation bar and was not reliably tappable. `MinimalBottomBar` now uses `navigationBarsPadding()`.

Validated screenshots:

- `ui_after_ui_fix_before_tap.png`
- `ui_after_ui_fix_summary_screen.png`

### Why flagged explanations

The original Summary UI only showed grouped counts such as `Correct knee alignment 9`, which did not explain the reason. The Summary UI now shows a full-width Safety Events card with:

- friendly rule label,
- count,
- deterministic explanation,
- approximate time/frame,
- measurement description from the recorded `SafetyEvent`.

Validated screenshot:

- `ui_why_flagged_full_width.png`

Example visible copy:

- `Spine / neck alignment`: torso or neck alignment proxy moved outside the upright range.
- `Knee alignment`: knee-tracking proxy moved outside the expected line over the foot.
- `Balance / center of mass`: body-center proxy moved away from the support area.

These are deterministic UI explanations, not model-generated medical claims.

## Known constraints

1. The immediate debug launch starts analysis before app-launch prewarm can always finish. The final run still met first-token `<5s`, but `reused_engine=false` can appear in this forced debug flow. For live demo, either wait for app-launch prewarm to finish before starting the video or trigger the prewarm endpoint first.
2. `motion_zip_blocks=0` in this video-flow smoke. This does not block the text summary demo, but it means the optional visual sidecar is skipped for this clip.
3. Rep count remains `0` on this short chair clip. The demo still proves deterministic pose analysis, senior-safe boundaries, Local Gemma summary, evidence refs, and explanation UI. A stronger final demo clip should include a cleaner full sit-to-stand cycle if rep count is part of the story.
4. Memory remains high with the image-capable LiteRT engine:
   - main process `TOTAL PSS ~= 3.37 GB`
   - debug process `TOTAL PSS ~= 2.97 GB`
   This is acceptable for a controlled demo smoke but confirms that visual sidecar must remain gated and non-live.

## Repro commands

```powershell
$env:JAVA_HOME = 'C:\Users\ken\.jdks\openjdk-23.0.2'
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

adb push test_assets\outputs\video_realtime_ai_chair_sit_to_stand_demo_20260513_204533_h264.mp4 /data/local/tmp/gemmafit_demo_chair.mp4
adb shell "run-as com.gemmafit sh -c 'mkdir -p files/test_videos && cp /data/local/tmp/gemmafit_demo_chair.mp4 files/test_videos/gemmafit_demo_chair.mp4'"
adb shell am start -n com.gemmafit/.MainActivity --es debug_video_file /data/user/0/com.gemmafit/files/test_videos/gemmafit_demo_chair.mp4
```

Useful evidence dumps:

```powershell
adb shell content read --uri content://com.gemmafit.debug/state
adb shell content read --uri content://com.gemmafit.debug/events
adb shell pidof com.gemmafit
adb shell pidof com.gemmafit:debug
adb shell dumpsys meminfo com.gemmafit
adb shell dumpsys meminfo com.gemmafit:debug
```

