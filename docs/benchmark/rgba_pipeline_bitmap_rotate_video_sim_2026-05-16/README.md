# Bitmap Rotate Local Video Simulation

Date: 2026-05-16

Purpose: repeat the Bitmap rotate optimization against local mp4 input without requiring the live camera.

This is a host-side OpenCV simulation. It validates the direction and size of app-side rotate cost on decoded video frames, but it does not measure CameraX internal thread cost or MediaPipe pose correctness. The Pixel live-camera audit remains the device source of truth.

## Method

- `current_yuv_bitmap_rotate`: decoded frame -> RGBA bitmap-like buffer -> 90 degree rotate -> copy.
- `camerax_rotated_yuv_bitmap`: decoded frame -> RGBA bitmap-like buffer -> copy.
- Input frames are resized to the configured analyzer size before measuring both paths.

## Results

| Video | Frames | Input | Current avg / p95 us | Optimized avg / p95 us | Avg saved | P95 saved | Avg saved % |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| video_realtime_ai_chair_sit_to_stand_demo_20260513_204533_h264.mp4 | 182 | 640x480 | 1,162 / 1,449 | 647 / 838 | 515 | 612 | 44.3% |
| video_realtime_internet_public_phone_full_demo_20260513_201732_h264.mp4 | 300 | 640x480 | 1,077 / 1,362 | 617 / 781 | 459 | 581 | 42.7% |

## Decision

`camerax_rotated_yuv_bitmap` should be the live-camera default. It keeps the existing YUV-to-Bitmap conversion behavior but removes app-layer `Bitmap` rotation from the analyzer hot path.

`camerax_rotated_rgba_bitmap` remains a debug variant until landmark correctness and channel behavior are checked with a person visible.

## Artifacts

- `summary.json`
