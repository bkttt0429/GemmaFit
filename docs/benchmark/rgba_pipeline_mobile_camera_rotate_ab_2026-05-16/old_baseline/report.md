# RGBA/RGB Pipeline Audit

Duration requested: 30 seconds

Sample count: 382

Total seen since reset: 382

Estimated sample rate: 12.36 Hz

Pipeline:

`	ext
Live camera image pipeline variant selected by files/debug/live_camera_image_pipeline.txt; each sample records the active variant.
`

Pipeline variants:

`json
{
    "CURRENT_YUV_BITMAP_ROTATE":  382
}
`

CameraX output rotation enabled:

`json
{
    "false":  382
}
`

Input formats:

`json
{
    "YUV_420_888":  382
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  382
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 949 | 845 | 1529 | 5580 |
| Rotate | 9171 | 8372 | 15426 | 38486 |
| BitmapImageBuilder | 127 | 113 | 156 | 1660 |
| detectAsync enqueue | 2915 | 2539 | 6166 | 15207 |
| Appearance snapshot copy | 1697 | 1355 | 3381 | 8575 |
| Total accepted frame | 13702 | 12645 | 21552 | 43942 |

Artifacts:

- summary.json
- reset.json
