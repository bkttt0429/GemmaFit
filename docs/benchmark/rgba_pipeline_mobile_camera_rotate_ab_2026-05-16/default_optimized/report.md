# RGBA/RGB Pipeline Audit

Duration requested: 30 seconds

Sample count: 374

Total seen since reset: 374

Estimated sample rate: 12.43 Hz

Pipeline:

`	ext
Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.
`

Pipeline variants:

`json
{
    "CAMERAX_ROTATED_YUV_BITMAP":  374
}
`

CameraX output rotation enabled:

`json
{
    "true":  374
}
`

Input formats:

`json
{
    "YUV_420_888":  374
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  374
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 1272 | 903 | 3672 | 10389 |
| Rotate | 0 | 0 | 0 | 0 |
| BitmapImageBuilder | 125 | 98 | 227 | 2380 |
| detectAsync enqueue | 3441 | 2731 | 6856 | 18248 |
| Appearance snapshot copy | 2168 | 1703 | 4396 | 7193 |
| Total accepted frame | 5507 | 4448 | 11012 | 19146 |

Artifacts:

- summary.json
- reset.json
