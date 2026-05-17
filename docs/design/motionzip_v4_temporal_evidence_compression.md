# MotionZip-V4 Temporal Evidence Compression

## Summary

MotionZip-V4 is GemmaFit's safety-preserving temporal evidence compression
layer. It borrows the systems idea behind DeepSeek V4's hybrid attention:
preserve recent detail, compress middle-range context into indexed blocks, and
summarize long-range history aggressively. In GemmaFit this is applied outside
the language model, before E2B sees anything.

MotionZip does not change Gemma 4/E2B architecture. It changes the app-side
packet that converts pose-derived time series into compact, auditable motion
evidence.

```text
Pose stream
-> Sliding Motion Window
-> CSA-like Event Blocks
-> HCA-like Session Summary
-> Evidence Packet
-> E2B function/report/refusal
```

## Why

Raw video and raw skeleton streams are too large, noisy, and unsafe to pass
directly into the local language model. Simple uniform frame sampling can miss
short safety-relevant events. Single-frame summaries lose temporal meaning.

MotionZip keeps the useful parts:

- recent local detail for live interpretation
- event boundaries and motion extrema
- low-confidence / subject-loss spans
- phase or primitive transitions
- compact session-level summary
- evidence refs and cannot-judge boundaries

The goal is not perfect action recognition. The goal is a small packet that
preserves the evidence needed for bounded coaching and makes unsupported claims
easy to block.

## DeepSeek V4 Analogy

| DeepSeek V4 mechanism | GemmaFit MotionZip equivalent |
| --- | --- |
| Sliding-window local attention | Keep the latest pose/features at higher detail for live state. |
| Compressed Sparse Attention (CSA) | Compress 4-8 frame groups into event blocks and index important blocks. |
| Lightning indexer | Select rep boundaries, extrema, instability, occlusion, and subject-switch blocks. |
| Heavily Compressed Attention (HCA) | Collapse long session history into reps, tempo trend, stability count, and blocked claims. |
| Hybrid attention | E2B receives recent context + key event blocks + global summary. |

This is an engineering analogy only. GemmaFit does not modify transformer
attention, KV cache, or Gemma model weights.

## Compression Contract

MotionZip may discard redundant frame details, but it must not discard
safety-critical evidence.

Always preserve:

- confidence floor
- pose ownership / subject tracking state
- subject lost or predicted spans
- angle extrema
- velocity peak
- rep/event boundary
- stability event
- phase or primitive transition boundary
- view-limited / low-confidence reason
- unsupported medical/force/EMG claim boundary
- evidence refs used by any downstream output

Never infer or store:

- force
- ground reaction force
- joint moment
- ligament strain
- EMG
- muscle activation
- heart-rate status without a sensor
- fall-risk score
- diagnosis or rehab progress

## P0 Packet Shape

```json
{
  "schema_version": "motion_zip_v4_v1",
  "window_id": "motion.rep.1",
  "trigger": "REP_COMPLETED",
  "sliding_window": {
    "last_ms": 1600,
    "frames_kept": 24,
    "reason": "recent_motion_context"
  },
  "compressed_sparse_blocks": [
    {
      "block_id": "motion.rep.1.block.rep_completed",
      "compression_mode": "csa_like_event_block",
      "time_range_ms": [2400, 3200],
      "tokens": ["low_stable", "upward_transition", "high_stable"],
      "preserved_extrema": {
        "knee_angle_min": 78,
        "knee_angle_max": 166,
        "peak_velocity_deg_s": 43,
        "confidence_floor": 0.82
      },
      "event_score": 0.91,
      "evidence_refs": [
        "metric.motion.rom",
        "layer2.event.rep_completed"
      ]
    }
  ],
  "heavily_compressed_summary": {
    "completed_reps": 12,
    "tempo_band": "controlled",
    "stability_events": 1,
    "confidence_floor": 0.82
  },
  "safety_preserved": [
    "confidence_floor",
    "angle_extrema",
    "velocity_peak",
    "event_boundary",
    "evidence_refs",
    "unsupported_claim_boundaries"
  ],
  "limits": [
    "derived_from_single_camera_pose",
    "no_force_or_grf",
    "no_emg_or_muscle_activation"
  ]
}
```

## Relationship To Layer 2

Layer 2 produces deterministic activity, phase, event, sub-action, rule-policy,
and abstain evidence. MotionZip compresses those outputs with
`MotionFeatureWindow`; it does not replace Layer 2.

```text
Layer2TemporalInterpreter
-> Layer2Output

TemporalMotionAnalyzer
-> MotionFeatureWindow

MotionZipPacketBuilder
-> MotionZipPacket
```

If Layer 2 abstains or downgrades a frame to monitor-only, MotionZip must carry
that state forward. E2B may explain the packet but may not override the
deterministic gate.

## Implementation Stages

### P0: Debug-only packet

- Build `MotionZipPacket` from `MotionFeatureWindow + Layer2Output`.
- Add packet to debug records for completed rep events.
- Do not change model prompt format yet.
- Unit test compression preserves extrema, confidence floor, event refs, and
  unsupported limits.

### P1: Prompt integration

- Add MotionZip packet to E2B event packet when token budget permits.
- Add validator requiring all cited refs to exist.
- Add fallback when packet has missing evidence refs or low-confidence state.

### P2: Event indexer

- Keep a rolling block store across the session.
- Index blocks by event score and safety relevance.
- Select top blocks for session summary and persona reports.

### P3: Learned sequence layer

- Optional tiny TCN/GRU over derived features only.
- Use for phase/event suggestions, not final safety verdicts.
- Deterministic gates remain authoritative.

## Acceptance Criteria

- No raw video is stored in MotionZip.
- No full raw skeleton stream is stored in MotionZip.
- Safety-critical extrema and confidence floor survive compression.
- `person_tracking_state=predicted/lost` cannot become hard judgment through
  compression.
- E2B receives compact evidence only after capability contract and Layer 2
  gates.
- Debug packet shows why a conclusion was judged, monitored, or abstained.
