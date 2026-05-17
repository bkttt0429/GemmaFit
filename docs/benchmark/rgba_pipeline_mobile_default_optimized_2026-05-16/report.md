# RGBA/RGB Pipeline Audit

Duration requested: 30 seconds

Sample count: 222

Total seen since reset: 222

Estimated sample rate: 12.52 Hz

Pipeline:

`	ext
Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.
`

Pipeline variants:

`json
{
    "CAMERAX_ROTATED_YUV_BITMAP":  222
}
`

CameraX output rotation enabled:

`json
{
    "true":  222
}
`

Input formats:

`json
{
    "YUV_420_888":  222
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  222
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 1194 | 849 | 3469 | 6366 |
| Rotate | 0 | 0 | 0 | 0 |
| BitmapImageBuilder | 117 | 85 | 314 | 1399 |
| detectAsync enqueue | 3087 | 2557 | 6750 | 16154 |
| Appearance snapshot copy | 1895 | 1579 | 4727 | 6282 |
| Total accepted frame | 4996 | 4161 | 10217 | 17130 |

Artifacts:

- summary.json
- reset.json
