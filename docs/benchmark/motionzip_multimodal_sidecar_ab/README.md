# MotionZip multimodal sidecar A/B smoke

Date: 2026-05-15

## Question

Can Gemma Vision understand the task-relevant content of a short workout segment without receiving dense video frames?

This smoke test compares:

- **A: Dense raw montage**: one image containing six raw frames sampled from the source video.
- **B: MotionZip sidecar**: one setup/environment keyframe, one MotionZip sparse event montage, and compact MotionZip evidence JSON.

The goal is not to let Gemma Vision make safety decisions. The goal is to test whether the sidecar can integrate:

- visual scene/equipment context from a keyframe;
- sparse event imagery from MotionZip;
- activity/event/velocity/confidence/limits from compact MotionZip evidence.

## Source

- Source video: `test_assets/videos/internet_public/lunge_forward_army.webm`
- Existing packet: `prototype/data/validation/results/lunge_forward_army_motionzip_packet.json`
- Model: `models/gemma4_e2b_vision_gguf/gemma-4-E2B-it-UD-IQ2_M.gguf`
- Projector: `models/gemma4_e2b_vision_gguf/mmproj-gemma-4-E2B-it-Q8_0.gguf`
- Tool: `tools/llama.cpp-b9159-vulkan/llama-mtmd-cli.exe`
- Backend: Vulkan, `Vulkan1: NVIDIA GeForce RTX 3050 Ti Laptop GPU`
- Image token budget: `--image-min-tokens 128 --image-max-tokens 128`
- Output constraint: `--json-schema-file sidecar_schema.json`

## Generated Assets

| Asset | Purpose |
|---|---|
| `dense_raw_montage.jpg` | A: dense raw-frame montage |
| `env_candidate_210.jpg` | B: setup keyframe focused on visible kettlebells / studio floor |
| `motionzip_event_montage.jpg` | B: sparse MotionZip event frames from source frames 210, 258, 1392, 1440 |
| `sidecar_schema.json` | JSON-only output contract |

## A: Dense Raw Montage Output

Input: `dense_raw_montage.jpg`

```json
{
  "activity_guess": "workout",
  "equipment": "kettlebells",
  "scene": "white indoor studio",
  "visible_body_region": "legs and torso",
  "motion_states": "standing",
  "event": "unknown",
  "velocity_hint": "moderate",
  "confidence_hint": "high",
  "limits": "unknown"
}
```

Observation: the dense montage recovers coarse visual context, but it does not recover the task-critical event, velocity, confidence, or evidence limits.

## B: MotionZip Sidecar Output

Inputs:

- Image 1: `env_candidate_210.jpg`
- Image 2: `motionzip_event_montage.jpg`
- Compact evidence:

```text
activity_hint=lunge_like_unilateral_motion
event=monitor_only
source_frames=[210,258,1392,1440]
peak_velocity_deg_s=[776.422,857.147]
confidence_floor=[0.5145,0.5682]
geometry_quality_flags=[low_keypoint_visibility, extreme_angle_geometry_caution, rapid_motion_proxy_high]
limits=[single_camera_pose, sampled_video_pose_not_every_frame, no_force_or_grf, no_medical_or_fall_risk_claim]
```

Output:

```json
{
  "activity_guess": "lunge_like_unilateral_motion",
  "equipment": "kettlebells",
  "scene": "white indoor studio",
  "visible_body_region": "lower_body",
  "motion_states": "step_or_descent_or_return",
  "event": "monitor_only",
  "velocity_hint": "high_velocity",
  "confidence_hint": "low_moderate_confidence",
  "limits": "single_camera_pose; sampled_video_pose_not_every_frame; no_force_or_grf; no_medical_or_fall_risk_claim"
}
```

Observation: the MotionZip sidecar preserves the task-relevant information that the dense montage missed: activity hint, event state, high velocity, confidence floor, and claim boundaries.

## Official E2B Phone Probe

The same organized sidecar contract was tested on-device with the official Gemma 4 E2B Vision GGUF already present on the Pixel:

- Model: `/data/local/tmp/gemmafit/e2bvision/gemma-4-E2B-it-UD-IQ2_M.gguf`
- Projector: `/data/local/tmp/gemmafit/e2bvision/mmproj-gemma-4-E2B-it-Q8_0.gguf`
- Runtime: `llama-server`
- Backend: Vulkan, Pixel GPU
- Images: `env_candidate_210.jpg` and `motionzip_event_montage.jpg`
- Image token budget: `--image-min-tokens 128 --image-max-tokens 128`
- Important server flags: `--reasoning off --reasoning-budget 0 --parallel 1 --cache-ram 0`

With reasoning enabled, the OpenAI-compatible response returned an empty `message.content` because generated tokens were consumed by the thinking/channel path. Re-running with reasoning disabled produced the expected schema output:

```json
{
  "activity_guess": "lunge_like_unilateral_motion",
  "equipment": "kettlebells",
  "scene": "white_indoor_studio",
  "visible_body_region": "lower_body",
  "motion_states": "step_or_descent_or_return",
  "event": "monitor_only",
  "velocity_hint": "high_velocity",
  "confidence_hint": "low_moderate_confidence",
  "limits": "single_camera_pose;sampled_video_pose_not_every_frame;no_force_or_grf;no_medical_or_fall_risk_claim"
}
```

Measured request stats:

| Metric | Value |
|---|---:|
| elapsed wall time | 106.537s |
| prompt tokens | 602 |
| completion tokens | 166 |
| prompt eval | 64.828s |
| decode | 41.260s |
| decode rate | 4.02 tok/s |

This confirms that official E2B can follow the organized GemmaFit sidecar schema when reasoning is disabled and the prompt is explicit. It also confirms that this path is too slow and memory-heavy for the live/demo main flow.

## Interpretation

This supports the intended GemmaFit framing:

```text
raw setup keyframe -> scene/equipment context
MotionZip sparse event montage -> visual temporal support
MotionZip compact JSON -> activity/event/velocity/confidence/limits
Gemma Vision -> structured sidecar summary
Android validator -> final product boundary
```

The test does not prove that a visual model can infer all motion facts from the synthetic montage alone. Instead, it shows a safer and more product-relevant result: Gemma Vision can combine low-frequency visual context with MotionZip evidence and return the same task-critical fields without receiving dense video or raw landmarks.

## Limits

- This is a one-video smoke test, not a quality benchmark.
- The final B prompt used fixed compact evidence values, so the model is acting as an evidence integrator, not as the source of safety truth.
- The sidecar still needs schema/validator enforcement. Without output constraints, Gemma 4 can emit thought-channel text or underfilled JSON.
- The live coaching path should still remain MediaPipe / Layer 2 / MotionZip / LiteRT evidence routing. Vision sidecar should stay low-frequency.
