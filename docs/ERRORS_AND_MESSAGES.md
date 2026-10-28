# Error messages: one sentence in the user's language, the rest as technical detail

AutoVybz has three layers that can fail: the audio engine (Kotlin + Oboe/native), the analysis, and storage
(preset files, entitlement preferences). All of them throw exceptions whose messages are **English technical
strings** – good for a log, wrong for a German or Spanish user.

## The contract

```
AudioEngines / MeasurementAnalyzer / storage
        │  typed exception (kind / reason / cause)
        ▼
WizardViewModel  ──────────────►  WizardError + technical detail
        │                              │
        │                              ├─► string resource  (complete, translated sentence)
        │                              └─► "Technical details: …"  (raw English message, secondary style)
        ▼
WizardStep.Error(error, detail, resumePosition, measuredPositions)
```

* `CaptureException.Kind` is machine-readable: `NATIVE_UNAVAILABLE`, `RECORD_TIMEOUT`, `SPEAKER_UNAVAILABLE`,
  `MIC_OPEN_FAILED`, `MIC_BUSY`, `AUDIO_IO`, `SAMPLE_RATE`, `OTHER`. `SampleRateMismatchException` sets
  `SAMPLE_RATE`, so the retry logic and the UI recognise it without parsing text.
* `WizardError.of(e)` maps the kind to the view-level error (unit-tested in `WizardErrorMappingTest`), and the
  error screen maps each `WizardError` to exactly one string key.
* `WizardStep.Error.detail` keeps the raw message. It is rendered *below* the sentence in a small, muted line
  ("Technical details: …"), because it is what a support request needs – but it is never spliced into the
  sentence itself.
* `StringResourceAuditTest.userFacingErrorSentencesNeverEmbedEngineText` enforces this: no `avz_err_*` string may
  take a format argument, except the detail line and the two resume counters (our own numbers).

Why it matters: before this, the German error screen read *"Audio konnte nicht gestartet werden: Cannot open
microphone at 48000 Hz"* – mixed languages in the most stressful moment of the app.

## What each message tells the user to do

| Error | User-facing sentence (EN) | Typical cause |
|---|---|---|
| `PERMISSION_DENIED` | needs microphone access | permission revoked / denied first run |
| `NO_SIGNAL` | test signal was not heard | speaker off, muted, wrong output, far too quiet |
| `CAPTURE_TIMEOUT` | no sound arrived from the microphone | another app holds the mic, silent virtual device |
| `MIC_BUSY` | another app or a call is using the microphone | call, voice recorder, other EQ |
| `SAMPLE_RATE` | mic and speaker do not share a sample rate | 44.1 kHz-only capture path, Bluetooth SRC |
| `SPEAKER_UNAVAILABLE` | speaker is not accepting audio | route loss, output locked by another app |
| `CAPTURE_FAILED` | audio could not be started | driver/NDK problem, unreadable stream |
| `ROUTE_CHANGED` | output changed during the measurement | Bluetooth dropped, headphones plugged in |
| `INTERRUPTED` | a call / alarm / other app took the audio | audio focus loss |
| `UNKNOWN` | something went wrong | anything without a better bucket |

`ROUTE_CHANGED`, `INTERRUPTED`, `NO_SIGNAL` and the new kinds never lose already-measured positions: a failure
from the second position on keeps the earlier ones and offers "repeat position n" / "finish with n positions".

## Other rules that follow from this

* Anything the **user** sees comes from a string resource (the app is en/de/es; the audit test forbids
  hardcoded `Text("…")` in composables).
* Numbers that a user sees are formatted with `Locale.getDefault()`, except in machine-readable exports
  (Equalizer APO / PEQ JSON / REW CSV), which stay `Locale.US` so that other tools can parse them.
* A toast that reports a *failure* states what to do next (`avz_save_failed`, `avz_apply_failed`,
  `avz_export_failed`, `avz_share_failed`, `avz_pro_file_invalid`), never just "error".
