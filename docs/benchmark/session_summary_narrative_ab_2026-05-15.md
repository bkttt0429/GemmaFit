# Session Summary Narrative Packet A/B Test

Date: 2026-05-15

Device: Pixel 8 Pro (`3A251FDJG004RX`)

Model: `gemmafit-v5-e2b-evidence-router.litertlm`

Backend: `litert-lm:isolated:gpu`

Purpose: compare compact session-summary prompting with and without rep-level narrative evidence.

## Setup

Both variants used the same compressed session memory, event index, output contract, evidence refs, and prewarmed LiteRT Engine. Variant B added:

- `rep_summaries`
- `session_trend`

The test used a supported chair-squat compact evidence fixture. It was a controlled prompt A/B test, not a full video reanalysis pass.

## Results

### V1 Free-Text Narrative Packet

| Variant | Prompt chars | Engine reused | Total elapsed | Generate time | Raw observation |
| --- | ---: | --- | ---: | ---: | --- |
| A: no narrative packet | 1454 | true | 27945 ms | 27934 ms | `Completed 4 chair squats in 27 seconds. There were 2 safety event(s) and 4 view-limited moments to watch.` |
| B: with narrative packet | 2090 | true | 24751 ms | 24745 ms | `Most reps were controlled, but a few showed slower movement and balance checks.` |

### V2 Structured Rep Narrative Packet

V2 replaced the free-text note catalog with bounded fields:

- `rep_summaries[].quality_note`
- `rep_summaries[].tempo_band`
- `rep_summaries[].duration_ms`
- `rep_summaries[].rom_deg`
- `rep_summaries[].peak_velocity_deg_s`
- `rep_summaries[].smoothness_proxy`
- `rep_summaries[].warning_names`
- `session_trend`
- optional `baseline_comparison`

| Variant | Prompt chars | Engine reused | Total elapsed | Generate time | Raw observation |
| --- | ---: | --- | ---: | ---: | --- |
| A: no narrative packet | 1509 | true | 26147 ms | 26096 ms | `Completed 4 chair squats. There were 2 safety event(s) and 4 view-limited moments to watch.` |
| B: with v2 narrative packet | 2611 | true | 32246 ms | 32246 ms | `The first two reps were steady and controlled. Rep 3 had an unsteady feel with a slower tempo and a note on spinal alignment. The final rep was controlled.` |

## Interpretation

Variant B produced a more coach-like summary because the model received rep-level cues rather than only aggregate counts. The v2 packet is slower than the no-narrative control by about 6.1 seconds in this run, but the output is much more useful for the Senior Hero demo because it cites specific reps and describes the early-to-late pattern.

Result: keep the narrative packet enabled for Senior Hero session summaries, with the feature guarded by tests and compact prompt budget checks.
