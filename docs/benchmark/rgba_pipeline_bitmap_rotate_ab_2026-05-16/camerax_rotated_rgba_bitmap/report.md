# RGBA/RGB Pipeline Audit

Duration requested: 30 seconds

Sample count: 325

Total seen since reset: 325

Estimated sample rate: 10.03 Hz

Pipeline:

`	ext
Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.
`

Pipeline variants:

`json
{
    "CAMERAX_ROTATED_RGBA_BITMAP":  325
}
`

CameraX output rotation enabled:

`json
{
    "true":  325
}
`

Input formats:

`json
{
    "RGBA_8888":  325
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  325
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 504 | 453 | 751 | 2121 |
| Rotate | 0 | 0 | 0 | 0 |
| BitmapImageBuilder | 102 | 89 | 148 | 1329 |
| detectAsync enqueue | 2189 | 2131 | 2471 | 5835 |
| Appearance snapshot copy | 1226 | 1184 | 1425 | 3220 |
| Total accepted frame | 3265 | 2845 | 4469 | 11038 |

Artifacts:

- summary.json
- reset.json
