# NOTICE

This repository contains the Android-free waveform core extracted from
**MusicSpeedLocal 1.6**:

- `com.local.musicspeed.WaveformMetrics`
- `com.local.musicspeed.WaveformBars`
- `com.local.musicspeed.WaveformColor`
- `com.local.musicspeed.WaveformGeometry`
- `com.local.musicspeed.WaveformMath`

No third-party runtime library is bundled.

JUnit 4.13.2 is used for tests only and is not part of the JAR.

The Android adapters (`WaveformDecoder`, `WaveformCache`, `WaveformLoader`,
`WaveformView`) are not part of this repository. They live in the
MusicSpeedLocal app and feed the core API.

No code or resources from the commercial MusicSpeed app
(`com.smp.musicspeed`) are included. That app was used only for
behavioural/dimensional observation. The implementation here is a clean
Java implementation based on platform APIs and the measured requirements.

## Build tooling in this repository

- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`: Gradle wrapper,
  Apache License 2.0 (Copyright Gradle Inc.).
- JUnit 4.13.2 is fetched from Maven Central for tests only
  (Eclipse Public License 1.0). It is not committed and not in the JAR.
- GitHub Actions (`actions/checkout`, `actions/setup-java`) run only in CI.
