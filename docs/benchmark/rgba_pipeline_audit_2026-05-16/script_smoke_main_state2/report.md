# RGBA/RGB Pipeline Audit

Duration requested: 5 seconds

Sample count: 121

Total seen since reset: 121

Estimated sample rate: 12.89 Hz

Pipeline:

`	ext
CameraX YUV_420_888 -> ImageProxy.toBitmap() -> Bitmap ARGB_8888 -> BitmapImageBuilder -> MediaPipe Pose
`

Input formats:

`json
{
    "YUV_420_888":  121
}
`

Frame bitmap configs:

`json
{
    "ARGB_8888":  121
}
`

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 888 | 721 | 1386 | 4976 |
| Rotate | 8504 | 7371 | 13965 | 49563 |
| BitmapImageBuilder | 122 | 101 | 183 | 940 |
| detectAsync enqueue | 2331 | 2106 | 3755 | 5726 |
| Appearance snapshot copy | 1365 | 1134 | 2160 | 4294 |
| Total accepted frame | 12265 | 11178 | 18552 | 52927 |

Artifacts:

- summary.json
- reset.json
