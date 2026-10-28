# Audio pipeline – stage by stage

This document describes the measurement audio pipeline from "user presses Calibrate" to the fitted filter
set, and the refinements that make each stage robust on real phones, real Bluetooth speakers and real
rooms. It is the reference for the code in `measurement/audio`, `measurement/dsp` and
`app/src/main/cpp/autovybz`; every claim here is guarded by a JVM unit test (see the table at the end).

```
 route check ─► rate negotiation ─► EQ pause + focus ─► stimulus at negotiated rate
      ─► full-duplex capture (Oboe, else AudioRecord/Track) ─► capture audit (xruns, clipping)
      ─► DC removal ─► sweep deconvolution ─► latency + IR window ─► pink-noise power average
      ─► noise floor / SNR ─► SNR-weighted combine ─► mic compensation ─► normalization
      ─► target inversion (AutoEqRenderer) ─► preset
```

## 1. Stage map

| # | Stage | Code | Output |
|---|---|---|---|
| 0 | Route check, EQ pause, audio focus | `RouteInspector`, `EqBypass`, `CaptureGuard` | safe route, quiet system |
| 1 | **Sample-rate negotiation** | `CaptureRates`, `AudioCapabilities` | rate of stimulus *and* capture |
| 2 | **Capture budget** | `CaptureTiming` | recording tail, watchdog |
| 3 | Stimulus synthesis | `StimulusGenerator` | 10 s buffer (silence / pink / gap / sweep / tail) |
| 4 | Full-duplex capture | `OboeMeasurementEngine` → `JavaMeasurementEngine` | float recording + xrun count |
| 5 | **Capture audit** | `CaptureAudit` | `AUDIO_GLITCH`, `CLIPPING` |
| 6 | **DC removal** | `MeasurementAnalyzer` | DC-free recording |
| 7 | Sweep deconvolution (Kirkeby) | `MeasurementAnalyzer.deconvolveSweep` | impulse response |
| 8 | Latency + windowed IR | `locateIrPeak`, `windowedIrPower` | \|H(f)\|², latency |
| 9 | **Latency plausibility** | `MeasurementAnalyzer` | `HIGH_LATENCY`, `LATENCY_OUT_OF_RANGE` |
| 10 | Pink-noise power average | `FftEngine.powerSpectrum` | leakage-free steady-state response |
| 11 | Noise floor + SNR | `NoiseChecks`, `SpectrumBinning` | per-band SNR, `NOISE_UNSTEADY`, `LOW_FREQ_NOISE`, `DROPOUT` |
| 12 | **SNR-weighted combine** | `blendWeight` | one magnitude curve |
| 13 | Mic compensation + normalization | `MicCompensation`, `FrequencyGrid.bandMean` | 0 dB @ 300 Hz–3 kHz |
| 14 | Target inversion | `AutoEqRenderer` | PEQ bands, pre-gain, safety |

The bold stages are the refinements of this iteration.

## 2. Sample rate: negotiate instead of assume

The whole analysis is sample-exact: the sweep reference, the pink-noise period, the noise-floor window
and the latency estimate only line up if playback and capture run at **exactly** the rate the stimulus was
generated for. The pipeline used to hard-code 48 kHz, which had two failure modes:

* a phone whose microphone only runs at 44.1 kHz could not measure at all ("Cannot open microphone at
  48000 Hz") – and `StimulusGenerator.plan(44100)` even failed its own window check, so a silent fallback
  to 44.1 kHz was not possible either;
* the Oboe engine asked for 48 kHz with `SampleRateConversionQuality::High`, so on a 44.1 kHz device
  **both** directions were resampled – extra group delay and a small HF response error exactly in the
  band that is being measured.

Now:

1. `AudioCapabilities` reads `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` and probes the capture HAL with
   `AudioRecord.getMinBufferSize` (no stream, no permission prompt, no side effect).
2. `CaptureRates.choose` prefers the device's own rate (no conversion), then 48 kHz, then 44.1 kHz.
3. The stimulus is generated **for that rate** (`StimulusGenerator.generate(rate)`), and the capture config
   carries it, so both engines open their streams at the negotiated rate.
4. If the HAL still disagrees, the engines throw `SampleRateMismatchException(requested, actual)` (Oboe via
   the `rate-mismatch:<rate>` marker of the native engine) and `MeasurementController` regenerates the
   stimulus for the offered rate and retries **once**. Everything else is reported, not retried.

The pink-noise analysis window follows the rate: `StimulusGenerator` picks the largest power of two that
still fits into the 0.75 s lead silence. At 48 kHz that is unchanged (`32768` samples = 0.68 s = 1.46 Hz),
at 44.1 kHz it is `16384` (0.37 s = 2.7 Hz) – coarser bass bins, but a valid measurement.

## 3. Capture budget: the tail is derived, not guessed

The recording has to contain the stimulus plus the response *after* the stimulus has ended:

```
tail = route latency + IR window (irPostSeconds) + margin
```

`CaptureTiming.tailSeconds(0.8 s, 0.3 s) = 1.3 s`. The old hard-coded 1.0 s was enough for wired routes
but not for laggy Bluetooth: the recording then ended inside the impulse-response window, the second
noise-floor snapshot (taken from the very end of the recording) was skipped, and the freshest part of the
sweep was cut off. The tail is clamped to 1.0 s … 2.5 s so a run stays below ~13 s, and the capture
watchdog is derived from the same numbers (`CaptureTiming.timeoutMillis`).

## 4. Capture: two engines, one contract

| Aspect | Oboe (`measurement_engine.cpp`) | Java fallback (`JavaMeasurementEngine`) |
|---|---|---|
| Input | preset chain `UNPROCESSED → VOICE_RECOGNITION → GENERIC`, each pinned device first, then default | source chain, pinned device as preference |
| Output | stereo float, pinned active media device first, then default | stereo float, `setPreferredDevice` |
| Buffer size | full ring capacity (non-low-latency path) | 0.25 s output, 0.5 s input |
| Xruns | `getXRunCount()` of both streams, snapshotted before the streams close | output `underrunCount` + conservative input-stall detector |
| Rate mismatch | `rate-mismatch:<rate>` start error | `SampleRateMismatchException(fs, actualRate)` |

* **Input pinning** keeps the capture on the microphone the compensation profiles describe (the built-in
  primary mic) instead of whatever accessory is attached.
* **Output pinning** (API 33+) keeps the stimulus on the route that was classified and confirmed. The
  capture guard already aborts on route *changes*; pinning closes the smaller window between the check
  and the stream start. Both engines treat a failed pinned device as a hint and fall back to the default
  route, so pinning can never make a measurement impossible.
* **Native engine thread safety**: the Kotlin side polls `state`, `progress`, `inputPeak` and `xruns` from
  another thread while the streams can be closed. The stream pointers are now guarded by a mutex, and the
  xrun counter is snapshotted in `closeStreams()` so a read after the run still reports the real value
  instead of 0.
* `stop()` is idempotent and always runs in `finally`; the recording is copied out before the streams go
  away.

## 5. Capture audit: gaps and clipping

Two failure modes are invisible to the analysis and are therefore decided on the raw capture
(`CaptureAudit`):

* **Buffer over-/underruns** (more than `XRUN_TOLERANCE = 2`) leave holes in playback or recording. The
  sweep still deconvolves, just with gaps, so this becomes `AUDIO_GLITCH` and the wizard offers a repeat.
  `JavaMeasurementEngine` counts output underruns directly and estimates input overruns from reads that
  return far later than the frames they delivered (`3 × expected + 40 ms`) – deliberately conservative so
  a normal scheduling hiccup is not reported as a glitch.
* **Clipping** is counted on the *raw* samples: a run of `≥ 3` samples at `|x| ≥ 0.98`, or `≥ 8` scattered
  ones, is clipping; a single full-scale sample is a click. This happens **before** DC removal on purpose:
  a clipped recording with a DC offset can drop below full scale after the correction and would then slip
  through the analyzer's own peak check.

## 5b. Stream volume: set for the measurement, and given back

The measurement needs ≈70 % of `STREAM_MUSIC` (device-speaker calibration ≈50 %, stepping down after mic
clipping). `VolumeGuard` remembers the user's value and restores it when the wizard finishes, fails or is
cancelled.

The in-memory value was not enough: a run killed mid-way (process death, force stop, low memory) left the
media volume at the measurement level for good, with no indication to the user that the app had changed it.
The guard therefore records `{saved, applied}` in `autovybz_volume` before touching the stream, and
`VolumeGuard.restoreAbandoned()` (called once per cold start in `AutoVybzActivity.onCreate`) puts `saved`
back **only if** the stream still holds exactly `applied` – a volume the user adjusted in the meantime is
their decision and stays untouched (`VolumeMath.shouldRestoreAbandoned`, unit-tested). The record is cleared
on every normal `restore()`, so nothing is ever restored twice.

## 6. DC offset removal

Phone microphones – and USB-C dongles after a converter – can deliver a constant offset. It is not part of
the room response, but it inflates the peak (fake clipping), biases the noise floor towards 0 Hz and leaks
into the sweep deconvolution at DC. `MeasurementAnalyzer` subtracts the mean of the recording when it
exceeds `1e-5` (≈ −100 dBFS; below that it is measurement noise) and works on the corrected buffer
thereafter. A recording that is *only* DC is rejected as `NO_SIGNAL`.

## 7. Latency: use it, and check it

The impulse-response peak gives the exact round-trip latency (no clock alignment needed), which
* shifts the pink-noise analysis window by exactly that latency, and
* is reported to the user (`HIGH_LATENCY` above 400 ms – normal for Bluetooth, already compensated).

What was missing is the upper bound: if the true delay is longer than `AnalyzerConfig.maxLatencySeconds`,
the peak is found somewhere and the result silently looks plausible. The peak is now compared against the
search limit; within 2 ms of it, `LATENCY_OUT_OF_RANGE` is raised (severe: the wizard recommends a repeat).
Search limits stay generous (0.8 s) but are no longer a silent cliff.

## 8. Combining sweep and pink noise

Two independent estimates of the same magnitude response:

* the **sweep** (Farina/ESS, Kirkeby-regularised) – robust against uncorrelated noise and reflections, but
  windowed (short IR window ⇒ limited frequency resolution in the bass);
* the **pink noise** – leakage-free power average over several periods, good in the bass, but it *is*
  noise, so it needs SNR.

`blendWeight(snr, w)` ramps linearly from "sweep only" at ≤ 3 dB SNR to the configured weight at ≥ 12 dB.
The previous rule was a hard switch at 6 dB, which could put a step of up to `1 - w` dB between
neighbouring grid points; the fitter then spent a parametric filter on that ledge. The ramp keeps the
curve continuous while still distrusting the pink path exactly where it is noise-dominated.

## 9. Failure modes → countermeasures → tests

| # | Failure mode | Symptom without countermeasure | Countermeasure | Test |
|---|---|---|---|---|
| 1 | Device cannot record at 48 kHz | Measurement impossible, or double resampling | rate negotiation + one regenerate-and-retry | `CaptureRatesTest`, `StimulusGeneratorTest.everyRateTheCaptureLayerCanNegotiateBuildsAValidPlan`, `MeasurementAnalyzerTest.theWholePipelineAlsoWorksAtTheFallbackRate` |
| 2 | Laggy route (0.5–0.8 s) | Recording ends inside the response, tail noise snapshot missing | tail = latency + IR window + margin | `CaptureTimingTest.defaultBudgetCoversHighLatencyBluetooth` |
| 3 | Latency beyond the search window | plausible-looking but wrong curve | `LATENCY_OUT_OF_RANGE` (severe) | `MeasurementAnalyzerTest.latencyAtTheSearchLimitIsFlagged` |
| 4 | Mic DC offset | fake clipping, DC/low-bin bias | DC removal before all analysis | `…dcOffsetIsRemovedBeforeTheAnalysis`, `…aPureDcRecordingIsStillSilence` |
| 5 | Hard clipping with offset | unnoticed after DC removal | run/scatter clipping counter on the raw samples | `CaptureTimingTest.clippedRunsAndScatteredFullScaleSamplesAreDetected` |
| 6 | Dropped buffers | silent gaps in the deconvolution | xrun counter, `AUDIO_GLITCH` after 2 events | `CaptureTimingTest.aFewXrunsAreToleratedButMoreMeanAGlitch` |
| 7 | Output route changes between check and start | stimulus on the wrong speaker | output pinning, device fallback in both engines | `RouteClassifierTest` (policy), native engine |
| 8 | Step in the curve at the SNR threshold | fitter chases a ledge | continuous SNR-weighted blend | `MeasurementAnalyzerTest.sweepWeightRampsWithSnr` |
| 9 | Native engine unavailable / broken | measurement fails | fallback to the Java engine for *any* non-cancellation failure, progress reset | `SoundE2ETest` (device), `FallbackMeasurementEngine` |

## 10. Tuning knobs (and where they live)

| Constant | Value | Meaning |
|---|---|---|
| `CaptureRates.PRIMARY` / `FALLBACK` | 48000 / 44100 | rates the analysis is defined for |
| `CaptureTiming.MIN/MAX_TAIL_SECONDS` | 1.0 / 2.5 | recording tail bounds |
| `CaptureTiming.MARGIN_SECONDS` | 0.20 | tail slack on top of latency + IR window |
| `CaptureAudit.XRUN_TOLERANCE` | 2 | xruns tolerated before `AUDIO_GLITCH` |
| `CaptureAudit.CLIP_THRESHOLD` / `…_MIN_RUN` / `…_MIN_SAMPLES` | 0.98 / 3 / 8 | clipping definition |
| `MeasurementAnalyzer.DC_REMOVAL_THRESHOLD` | 1e-5 | when the DC offset is removed |
| `MeasurementAnalyzer.BLEND_START/FULL_SNR_DB` | 3 / 12 | sweep↔pink crossfade |
| `MeasurementAnalyzer.LATENCY_EDGE_SECONDS` | 0.002 | "peak at the search limit" |
| `AnalyzerConfig.maxLatencySeconds` | 0.8 | latency search window (also sets the tail) |
| `AnalyzerConfig.irPostSeconds` | 0.30 | IR window after the peak (also sets the tail) |
| `StimulusGenerator.PEAK_LEVEL` | 0.5 | −6 dBFS stimulus headroom |
