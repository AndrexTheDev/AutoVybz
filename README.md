<h1><img width="96" src="branding/autovybz_logo_1024.png" alt="AutoVybz" align="absmiddle"> AutoVybz</h1>

**One tap. 10 seconds. Your speaker, tuned to your room.**

🌐 **[andrexthedev.github.io/AutoVybz](https://andrexthedev.github.io/AutoVybz/)** – hear the interactive before/after demo & roast your speaker 🔥

[![CI](https://github.com/AndrexTheDev/AutoVybz/actions/workflows/ci.yml/badge.svg)](https://github.com/AndrexTheDev/AutoVybz/actions/workflows/ci.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-009B3A)](LICENSE)
[![Android 9+](https://img.shields.io/badge/Android-9%2B-FED100)](#building)

AutoVybz is a free, 100 % open-source (GPL-3.0) Android app that **automatically calibrates external
loudspeakers** (Bluetooth, AUX, USB, HDMI) to your room with the phone's microphone — and then runs the
correction system-wide through the proven [Equalizer314](https://github.com/bearinmindcat/Equalizer314)
engine (DynamicsProcessing, 10-band PEQ, multiband compressor, limiter). Donationware: no ads, no tracking.

> Status: **0.1.0-alpha** – first public structure. Build with Android Studio / Gradle (see below).

## Features

- **1-click wizard for everyone** – big "Calibrate speaker" button, safety check, live mic level, result graph.
- **Measurement**: exactly 10 s stimulus = room-noise capture + periodic pink noise + logarithmic sine sweep
  20 Hz – 20 kHz; Kirkeby-regularised deconvolution, automatic latency detection (Bluetooth-safe),
  sweep/noise cross-check, SNR per 1/3 octave, clipping / low-signal warnings.
- **Capture path**: Oboe (C++, AAudio/OpenSL) full-duplex with `UNPROCESSED` input, fallback
  `VOICE_RECOGNITION` → `MIC`; Java `AudioRecord/AudioTrack` fallback if the native engine fails.
  Native C++ FFT via JNI, Kotlin FFT fallback.
- **Mic compensation**: JSON database keyed by `Build.MANUFACTURER` / `Build.MODEL`
  (estimated MEMS archetypes – see [docs/MIC_PROFILES.md](docs/MIC_PROFILES.md)), Pro: USB measurement mic
  with calibration file (UMIK-style) or manual offset profile.
- **Dynamic smoothing**: 1 octave in the sub/bass → 1/3 octave from ~320 Hz (Pro: fine / strong).
- **Targets**: Smart (gentle bass lift, default), Neutral/Flat, Bass Boost, Harman/Loudness (analytic
  approximation), Custom (bass/treble/tilt).
- **Target inversion**: 5 or 10 parametric filters (default 10), bass roll-off detection + excursion guard
  (never boosts bass the speaker can't play), max boost / cut limits, automatic negative pre-gain +
  Equalizer314 limiter as anti-clipping.
- **Presets**: auto-named `AutoVybz_<device>_<yyyy-MM-dd>` (editable), saved into the normal Equalizer314
  preset list, activated with one tap.
- **Export**: Equalizer APO / Peace / AutoEq `ParametricEQ.txt`, parametric EQ JSON, REW-importable CSV,
  internal `.avz.json` (curves + filters + metadata).
- **Pro area**: Equalizer314 10-band PEQ graph, multiband compressor, limiter, measurement & mic settings,
  saved calibrations.
- **Help** with step-by-step phone placement guide; **About** with credits and crypto donate dialog.
- Design: deep charcoal `#121212`, Jamaica green `#009B3A` + gold `#FED100`.

## Measurement tips

1. Connect the external speaker (headphones and the phone's own speaker are blocked on purpose).
2. Phone at the listening position, ear height, bottom mic towards the speaker, 1–2 m away, not hand-held.
3. Normal listening level on the speaker; AutoVybz sets media volume to ~70 % (restored afterwards).
4. Quiet room; disable Dolby Atmos / sound effects / speaker-app EQ. The AutoVybz EQ pauses itself.

## Building

Requirements: Android Studio (AGP 8.2, Kotlin 1.9.20), JDK 17–21, Android SDK 36, **NDK 27.2.12479018**,
CMake 3.22.1.

```bash
./gradlew testDebugUnitTest   # JVM unit tests (DSP, fitter, exports, mic DB, routing)
./gradlew assembleDebug
tools/native/host_check.sh    # optional: FFT vs. DFT + Oboe/JNI syntax check on the host
python3 tools/micdb/generate_mic_profiles.py   # regenerate the mic profile DB
python3 tools/branding/generate_icons.py       # regenerate launcher icons from branding/
```

CI (`.github/workflows/ci.yml`) builds and tests every push. Releases: push a tag `AutoVybz-v<versionName>`
with the secrets `AUTOVYBZ_KEYSTORE_BASE64`, `AUTOVYBZ_STORE_PASSWORD`, `AUTOVYBZ_KEY_ALIAS`,
`AUTOVYBZ_KEY_PASSWORD` (optional variable `AUTOVYBZ_RELEASE_CERT_SHA256`).

## Architecture

```
app/src/main/java/com/andrexthedev/autovybz/
  measurement/dsp     stimulus, FFT, smoothing, analyzer        (pure Kotlin, unit-tested)
  measurement/eq      biquads, targets, fitter, AutoEQ renderer (pure Kotlin, unit-tested)
  measurement/mic     mic profiles, calibration-file parser, DB loader
  measurement/audio   Oboe/JNI + Java engines, route check, volume guard, EQ bypass
  measurement/        MeasurementController (orchestration)
  preset/, export/    AutoVybz preset model, Equalizer314 pool bridge, exporters
  ui/                 Jetpack Compose: wizard, pro, help, about/donate, theme
app/src/main/cpp/autovybz/   C++17 Oboe full-duplex engine + FFT + JNI
app/src/main/java/com/bearinmind/equalizer314/   upstream Equalizer314 (classic Views)
site/                landing page (GitHub Pages, see site/README.md)
```

## Credits & license

AutoVybz by **AndrexTheDev** (hippie.highho@gmail.com) is a fork of
**[Equalizer314](https://github.com/bearinmindcat/Equalizer314) by bearinmindcat** and, like upstream,
licensed under the **GNU GPL v3.0** – see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
The original upstream README is kept in [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md).

## Support

AutoVybz is donationware. If it improved your sound:

| Coin | Address |
|---|---|
| SOL | `79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8` |
| BTC | `bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f` |
| ETH / EVM | `0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7` |
