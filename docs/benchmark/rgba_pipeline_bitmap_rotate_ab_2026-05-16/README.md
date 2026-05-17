# Bitmap Rotate Pipeline A/B

Date: 2026-05-16

Device: Pixel 8 Pro

Flow: Live Camera screen, 30 seconds per variant.

Purpose: test whether app-layer `Bitmap.createBitmap(... Matrix ...)` rotation can be removed from the live MediaPipe Pose input path.

## Variants

| Variant | CameraX output | CameraX output rotation | App conversion path |
| --- | --- | --- | --- |
| `current_yuv_bitmap_rotate` | `YUV_420_888` | off | `ImageProxy.toBitmap()` -> app `Bitmap` rotate -> `BitmapImageBuilder` |
| `camerax_rotated_yuv_bitmap` | `YUV_420_888` | on | CameraX rotated image -> `ImageProxy.toBitmap()` -> `BitmapImageBuilder` |
| `camerax_rotated_rgba_bitmap` | `RGBA_8888` | on | CameraX rotated RGBA buffer -> `Bitmap.copyPixelsFromBuffer()` -> `BitmapImageBuilder` |

`ByteBufferImageBuilder` was intentionally not put into the live async path in this run. `detectAsync()` returns before MediaPipe finishes processing the frame, while `ImageProxy.close()` allows CameraX to reuse the backing buffer. That makes a direct buffer path unsafe until lifecycle ownership is redesigned.

## Results

Times are microseconds from the app-side analyzer code path. CameraX internal rotation/conversion work happens before analyzer callback and is not separately visible here.

| Variant | Samples | Hz | Rotation seen by app | Convert avg / p95 | App rotate avg / p95 | Total app preprocess avg / p95 |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| `current_yuv_bitmap_rotate` | 340 | 10.15 | `90` | 1,036 / 1,668 | 7,666 / 9,570 | 11,742 / 14,730 |
| `camerax_rotated_yuv_bitmap` | 364 | 10.23 | `0` | 874 / 1,324 | 0 / 0 | 3,584 / 4,824 |
| `camerax_rotated_rgba_bitmap` | 325 | 10.03 | `0` | 504 / 751 | 0 / 0 | 3,265 / 4,469 |

## Interpretation

The direct answer is yes: avoiding app-layer Bitmap rotation is a real win in this measured path.

`camerax_rotated_yuv_bitmap` removes the measured app rotate stage and cuts app-side accepted-frame preprocessing by about 8.2 ms avg and 9.9 ms p95 versus current.

`camerax_rotated_rgba_bitmap` is the fastest measured analyzer path, mainly because app-side conversion drops to about 0.5 ms avg. It is a candidate for a guarded optimization, but needs a pose-correctness pass with a person in frame before becoming the default.

This does not prove CameraX rotation is free. It means the cost is no longer in GemmaFit's analyzer code path, and the observed sample rate stayed near 10 Hz under the current throttle. A future Perfetto trace is needed if we want to account for CameraX internal thread cost.

## Recommendation

Use `camerax_rotated_yuv_bitmap` as the safer first optimization. It preserves the existing YUV-to-Bitmap behavior and only moves rotation to CameraX.

Implementation status: this is now the default live-camera pipeline when `files/debug/live_camera_image_pipeline.txt` is absent or invalid. The old path can still be forced with `current_yuv_bitmap_rotate` for A/B testing.

Keep `camerax_rotated_rgba_bitmap` behind a debug/feature flag until landmark correctness and color/channel behavior are checked with a person visible.

Do not use live `ByteBufferImageBuilder` directly from `ImageProxy.planes[0].buffer` until the async buffer lifetime is explicitly owned.

## Artifacts

- `current_yuv_bitmap_rotate/summary.json`
- `current_yuv_bitmap_rotate/report.md`
- `camerax_rotated_yuv_bitmap/summary.json`
- `camerax_rotated_yuv_bitmap/report.md`
- `camerax_rotated_rgba_bitmap/summary.json`
- `camerax_rotated_rgba_bitmap/report.md`
