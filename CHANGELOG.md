# Changelog

## 1.6.0 — 2026-10-26

- Extract Android-free waveform core from MusicSpeedLocal 1.6.
- Whole-track scrollable geometry with centre playhead and 2.5/5/10 dp/s zoom.
- Height normalisation: bucket peak p95 + gain cap 8× + gamma 0.75.
- Colour: low/high band energy compared in dB over ±12 dB; red (6°) ↔ cyan (198°).
- Brightness from RMS; played/unplayed colours pre-computed; unplayed visibility floor.
- 61 JVM tests covering metrics, bars, colour, geometry and legacy math.
- No runtime dependencies. JUnit is test-only.
