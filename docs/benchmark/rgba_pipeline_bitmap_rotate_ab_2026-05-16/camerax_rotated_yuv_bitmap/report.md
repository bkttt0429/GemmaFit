# RGBA/RGB Pipeline Audit

Duration requested: 30 seconds

Sample count: 364

Total seen since reset: 364

Estimated sample rate: 10.23 Hz

Pipeline:

`	ext
Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.
`

Pipeline variants:

`json
{
    "CAMERAX_ROTATED_YUV_BITMAP":  364
}
`

CameraX output rotation enabled:

`json
{
    "true":  364
}
`

Input formats:

`json
{
    "YUV_420_888":  364
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  364
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 874 | 816 | 1324 | 3435 |
| Rotate | 0 | 0 | 0 | 0 |
| BitmapImageBuilder | 108 | 88 | 161 | 2161 |
| detectAsync enqueue | 2154 | 2070 | 2566 | 9947 |
| Appearance snapshot copy | 1200 | 1150 | 1430 | 3102 |
| Total accepted frame | 3584 | 3179 | 4824 | 10925 |

Artifacts:

- summary.json
- reset.json
