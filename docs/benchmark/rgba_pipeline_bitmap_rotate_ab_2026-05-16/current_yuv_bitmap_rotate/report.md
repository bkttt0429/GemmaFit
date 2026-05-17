# RGBA/RGB Pipeline Audit

Duration requested: 30 seconds

Sample count: 340

Total seen since reset: 340

Estimated sample rate: 10.15 Hz

Pipeline:

`	ext
Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.
`

Pipeline variants:

`json
{
    "CURRENT_YUV_BITMAP_ROTATE":  340
}
`

CameraX output rotation enabled:

`json
{
    "false":  340
}
`

Input formats:

`json
{
    "YUV_420_888":  340
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  340
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 1036 | 861 | 1668 | 7836 |
| Rotate | 7666 | 7610 | 9570 | 18891 |
| BitmapImageBuilder | 125 | 115 | 172 | 840 |
| detectAsync enqueue | 2386 | 2289 | 3252 | 10590 |
| Appearance snapshot copy | 1368 | 1305 | 1704 | 3981 |
| Total accepted frame | 11742 | 11459 | 14730 | 27025 |

Artifacts:

- summary.json
- reset.json
