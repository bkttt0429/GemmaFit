# LiteRT Prompt JSON Smoke - Partial Thermal Stop

Model: official

Prompt file: litert_ab_a_no_narrative_v2.json

Requested runs: 100

Completed runs: 7

JSON parse success in completed runs: 7 / 7

99% gate: false - full 100-run was not completed

Stop reason: device reached Thermal Status 3 before run 8 and stayed there through repeated 90s pauses.

Backend: litert-lm:isolated:gpu

Average generate ms: 23501

Average endpoint elapsed ms: 27980.1

Notes:

- This partial run does not replace the official 100-run gate.
- The completed runs all produced parseable model JSON.
- The harness now supports endpoint retries and thermal pause before prompts.
