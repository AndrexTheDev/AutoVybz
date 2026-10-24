# Microphone profiles

Phone microphones are not flat. AutoVybz subtracts a microphone response from every measurement. The data
lives in [`app/src/main/assets/autovybz/mic_profiles.json`](../app/src/main/assets/autovybz/mic_profiles.json).

> **Important:** all built-in curves are **estimated archetypes** of typical MEMS bottom-port microphones,
> not lab measurements of the listed devices. They are better than nothing, but a real calibrated
> measurement (or a USB measurement mic in the Pro area) is always more accurate.

## Structure

```jsonc
{
  "schemaVersion": 1,
  "units": { "frequency": "Hz", "level": "dB relative to 1 kHz (what the mic adds)" },
  "default": "mems-bottom-port",            // archetype used for unknown devices
  "archetypes": {
    "mems-bottom-port": {
      "label": "Generic MEMS bottom-port microphone",
      "curves": {                            // one curve per Android input source
        "UNPROCESSED":       [[20, -5.0], [100, -0.1], [1000, 0.0], [10000, 2.8], [20000, -3.5]],
        "VOICE_RECOGNITION": [[20, -14.0], [200, 0.0], [1000, 0.0]]
      }
    }
  },
  "devices": [
    {
      "id": "samsung-galaxy-s-legacy",
      "manufacturer": "samsung",             // compared with Build.MANUFACTURER (normalised)
      "models": ["SM-G9[5-8]\\d.*"],         // regexes on Build.MODEL, case-insensitive; [] = all models
      "label": "Samsung Galaxy S8-S20 series",
      "curveRef": "mems-bottom-port",        // or an inline "curves" object
      "confidence": "estimated"              // measured | community | estimated
    }
  ]
}
```

Archetypes shipped: `mems-bottom-port`, `mems-bottom-port-strong-resonance`, `mems-bottom-port-flat`.
Levels are interpolated logarithmically between the points and normalised to 0 dB at 1 kHz. The curve for
the input source actually obtained (`UNPROCESSED`, otherwise `VOICE_RECOGNITION`, otherwise `MIC`) is used.

## Pro: USB measurement microphone

In the Pro area you can load a calibration file in the common UMIK/miniDSP text format (frequency, dB,
optional phase; a `Sens Factor` header is read) or enter a manual offset profile. That calibration
replaces the phone profile.

## Contributing a measured profile

1. Measure the phone microphone against a calibrated reference mic (same position, same stimulus) –
   ideally outdoors or in a large room with gating to avoid reflections.
2. Add a `devices` entry with inline `curves` for `UNPROCESSED` (and `VOICE_RECOGNITION` if available),
   `"confidence": "measured"` and a `notes` field describing the setup.
3. Open a pull request. `./gradlew testDebugUnitTest` validates that the database still parses.
