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
| 14 | **Speaker health** | `HarmonicDistortion`, `RoomDecay` | THD, H2…H5, `SPEAKER_DISTORTION`, `DRIVER_RATTLE`, room T60, `REVERBERANT_ROOM` |
| 14b | **Loudness + true peak (BS.1770)** | `IntegratedLoudness` | integrated LUFS, dBTP, block count |
| 15 | Target inversion | `AutoEqRenderer` | PEQ bands, pre-gain, safety (lower limit = detected roll-off, or the declared subwoofer crossover) |
| 16 | **Verification (Check run)** | `Verification` | measured vs predicted change, `Verdict` |

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

## 8b. Several positions: average what everyone hears, drop what nobody heard

A single microphone position contains its own cancellations: at 40 cm from the same speaker a mode can
move 10–15 dB, and a 10 dB dip at 74 Hz is a property of *the spot*, not of the speaker. An EQ that fills
it spends excursion that the other seats pay for. The app therefore measures up to three positions
(indoors `recommendedPositions = 3`; the free tier stays at one) and combines them with a power
("RMS") average – `SpatialAverager` – which keeps the peaks every position shares and lifts the dips that
only one position has.

`analyse()` returns the averaged result *and* the story behind it:

* **spread** – RMS disagreement of the positions against their average over 100 Hz–10 kHz (each position
  level-aligned, so "closer to the speaker" is not an error). 1–3 dB is a normal room; > 6 dB means the
  phone moved a lot or one measurement was disturbed (`HIGH_POSITION_SPREAD_DB` in the wizard).
* **outliers** – a position is dropped when its deviation from the *median* curve of all positions
exceeds `max(3 × median deviation, 1.5 dB)` (the median, because against the mean a single disturbed
position drags the reference towards itself and every position looks equally wrong). Two limits
  make this safe: with two positions nothing can be dropped (no majority says which one is wrong), and
  never fewer than two positions survive. A dropped position raises `POSITION_OUTLIER` with the wizard's
  "repeat this position" action instead of silently skewing the result.
* **rejectedPositions / positionSpreadDb** travel with the `MeasurementOutcome`, and the spread is
  computed on the *kept* positions, so a dropped outlier does not leave a scary number behind.

Indoors, boosts additionally follow a 1-octave smoothed curve (`broadBoostOctaves = 1.0`): a narrow dip
inside a region where the positions disagree by more than 6 dB gets no boost at all. Measured with the
probe's position-aware room model, the largest narrow boost inside such a region is 1.96 dB, where the
unconstrained fitter put 4 dB of Q 4 boost into a mode null.

## 8c. Two free metrics: distortion and room decay

Both come out of the deconvolution the analyzer already has – no extra playback, no extra capture second.

**Distortion (`HarmonicDistortion`).** Farina's sweep maps the n-th harmonic of a memoryless nonlinearity to
a fixed lag, `T·ln(n)/ln(f2/f1)` before the main peak (≈ −0.5 s for H2 on the 5 s sweep). The analyzer reads
H2…H5 there in ±1 ms windows and compares them with a *local* floor sampled between the harmonics – the
room's own decay, not a global noise number. A harmonic that does not clear the floor by 6 dB is dropped;
a peak that does not clear it by 12 dB yields no THD at all (rather than "0.0 %"). Above 3 % the wizard
raises `SPEAKER_DISTORTION` (severe: measure one step quieter), and a harmonic series that does not fall
with order (`H5` within 6 dB of `H3` – the signature of a buzzing panel, unlike the odd-order-only series
of a soft clip) raises `DRIVER_RATTLE`.

**Room decay (`RoomDecay`).** Schroeder backward integration of the *unwindowed* response, direct sound
skipped, slope fitted over −5 … −25 dB and extrapolated to T60. Two gates keep the number honest: the
direct sound must be out of the window (it otherwise makes every room ~40 % drier) and the noise plateau
must sit 10 dB below the fit region (a plateau has no slope – extrapolating it turned a 0.9 s room into
5.4 s in the traffic model). Otherwise the answer is "unknown". From 0.8 s the wizard says "this room rings
a lot (1.0 s)"; the result itself is unchanged, because the boost gate that could act on it could not be
shown to help (see `docs/AUDIO_ROADMAP.md`, W4).

## 8d. The verification pass: measuring the promise

Everything above ends with "here is what we predict". `Verification` is the only stage that looks at the
result: an optional second measuring run is taken *through* the active EQ, and three curves are compared –
the measured change (`after − before`), the plan's own filter response, and the residual to the target
before / after / as predicted.

Both shapes are normalized in 300 Hz–3 kHz, so the comparison is level-invariant: a user who turns the
volume down between the runs invalidates nothing, because the pre-gain cancels. The measured difference is
aligned in that band once more before it is judged – the analyzer's normalisation already does this, but a
capture-gain change that survived it must not be able to pass for a tonal change. What it does catch is the
chain lying in between, and the verdict order is what makes that possible:

| condition | verdict | typical cause |
|---|---|---|
| nothing predicted, nothing measured | `AS_PREDICTED` | the speaker already matched the target |
| measured change < 0.5 dB | `NO_CHANGE` | wrong route, EQ off, preset not saved |
| RMS(measured − predicted) ≤ 1.5 dB | `AS_PREDICTED` | it did what it said |
| shape correlation < 0.5 | `UNEXPECTED` | something else changed entirely |
| measured RMS < predicted RMS | `WEAKER_THAN_PREDICTED` | limiter clamped it, engine dropped bands |
| measured RMS > predicted RMS | `BETTER_THAN_PREDICTED` | the plan was conservative |

Magnitude alone cannot separate "nothing happened" from "the wrong thing happened" – that is why the
correlation exists. The judged band starts half an octave above the bass limit (`1.41 × lowLimit`, floored
at 150 Hz): below it the correction has no authority, and judging there would punish the app for a refusal
it made on purpose. It is deliberately *not* an auto-retry: a verification that disagrees with its own
prediction is shown to the user, not silently re-fitted.

**How the wizard runs it.** The check is offered on the *saved* card and only when the preset was applied –
a saved-but-inactive preset would measure the unchanged speaker and report a failure that is not one. It
re-uses the whole measurement path with one difference: `MeasurementController.run(keepEqActive = true)`
does **not** call `EqBypass.pauseIfRunning`, so the stimulus is played through the correction that is
actually in the signal path (engine, route and preset included – that is what makes the failure modes above
visible). The result is compared with the calibration measurement and its own `AutoEqResult`; the card then
shows the verdict in words plus "measured x dB better, predicted y dB". It costs ten seconds, is repeatable,
and a failed check costs nothing: the calibration stays saved, and the card offers the check again.

`pipeline_findings.py` finding 14 runs the loop measure → correct → re-measure on three scenario models at
18 dB SNR: measured improvement +1.66 / +1.73 / +2.64 dB against +1.73 / +1.82 / +2.53 dB predicted (worst
gap 0.11 dB), while the same comparison on an unapplied correction returns `NO_CHANGE` every time.

**Where the check goes afterwards (round 15).** A report that only exists on one screen is worth little: the
result the user shares and exports is the one that travels. The report is therefore stored with the
calibration (`MeasurementSnapshot.verification`) and re-drawn from there:

* **share card** – one line on the baseline of the key figure, right-aligned: *"✓ Checked: 1.8 dB measured,
  2.1 dB predicted"*, in green when the check confirmed the plan and in the warning colour when it did not.
  A shared result that did not do what was promised must not look like one that did;
* **APO / Peace export** – a `# Check: …` comment line, invisible to Equalizer APO and to the app's own
  parser (a comment cannot change a preset, only describe it);
* **REW export** – one more `*` header line;
* **internal `*.avz.json`** – the whole report, so a re-imported preset still knows it was verified. A
  missing block (older files) or a verdict from a newer build loads as "not checked"; the numbers are never
  guessed;
* **the lists that show stored calibrations** – the Sound screen's speaker chips get the tick as a leading
  icon (its content description is the whole verdict, so a screen reader gets the sentence), and the Pro list
  prints the badge under the metadata line. Both use one shared component and one shared wording
  (`verificationVerdictLabel`), and a `null` report renders nothing: "not checked" is the absence of a badge,
  not a third state to explain.

The wizard writes the block back to the stored preset after a successful check (best effort – failing to
rewrite the file must not cost the user the ten seconds they just spent). A new plan – another target, or a
new measurement – starts without a report, because a report describes exactly one plan.

## 8e. A subwoofer is declared, not detected

A phone microphone cannot tell whether the bass it hears comes from the speaker or from a sub. The user
declares it (`80 / 100 / 120 Hz`, or "none"), and `AutoEqSettings.subwooferCrossoverHz` then does two things:

* **the correction's lower limit becomes the crossover** – but only ever *raises* the detected limit, so a
  small box that already stops at 160 Hz keeps its own. Everything downstream (range weight, excursion guard,
  null gate, fitter window) reads that one limit, so none of them has to know about subwoofers;
* **the protective high pass is dropped.** It exists to protect a driver that cannot play low, and with a sub
  the mains are crossed over anyway – a pass in the app would silence the sub that is supposed to play there.

Below the crossover the app deliberately does nothing: what the microphone sees there is the sub's own
tuning plus the room, and the fix for a sub that is too loud is the knob on the back of the box. What that
costs is measured rather than assumed (`pipeline_findings.py` finding 17, probe `--sub`): without the
declaration the fit starts at 25 Hz and spends its low bands on a −3.9 dB cut at 40–55 Hz, guessing that the
boom is the box's tuning; with the crossover declared the plan touches 40–55 Hz by 0.10 dB and is slightly
*better* above the crossover (0.92 → 0.87 dB), because no filter or headroom is spent where the app has no
authority. On the live probe's music-weighted metric the two land within 0.02 dB (0.21 vs 0.23 dB), which is
the point: the setting is a scope decision, not a tonal one.

## 8f. The measurement level is a distortion knob, not an accuracy knob

The app starts at 70 % of the stream (50 % for device speakers). That is a guess, and a small speaker at
70 % can be well past its excursion limit - the THD the sweep already yields says so after ten seconds.

`MeasurementLevel` turns that number into a level step: `20·log10(THD / 2 %) / 1.89`, clamped to one step of
at most 6 dB, and skipped when it would be under 1 dB. The slope is measured, not assumed: a soft, symmetric
nonlinearity is dominated by its cubic term, so THD grows *quadratically* with the level
(`pipeline_findings.py` finding 18: 4.01 % → 2.14 % → 1.11 % → 0.57 % over 0 / −3 / −6 / −9 dB, i.e.
1.89 dB of THD per dB). That is why a 3 dB step takes a 4 % speaker to 2 %, and why "turn it down a bit"
would land 6 dB of distortion either side of the target.

The wizard retries a position exactly once when the first measurement reports more than 2 % THD. The repeat
is announced in the countdown ("a little quieter – the speaker was distorting at this level") instead of
silently taking longer, and the *second* measurement is the one that counts, so a THD number caused by the
level does not end up as a warning on the result.

Two rules the measurements forced:

* **Only ever down.** The roadmap proposed raising the level until THD reaches 2 %. Finding 18 shows the
  trade is not symmetric: 9 dB quieter measures the same curve to 0.016 dB rms, and the tonal error at 6 dB
  SNR is 0.05 dB *lower* than in a quiet room. Getting louder buys nothing; it only adds distortion.
* **A floor.** At 25 % of the stream range the sweep stops being the loudest thing in the room. A speaker
  that distorts even there gets the `SPEAKER_DISTORTION` warning - the honest answer - rather than an
  ever-quieter measurement.

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
| 10 | Inter-sample peak above 0 dBFS | distortion on the DAC although "0 clipped samples" | 4× oversampled true peak (`TruePeak`), limiter headroom 4.0 → 3.5 dB | `TruePeakTest`, `check_parity.py` peak block, probe `peak_dbtp` |
| 11 | One disturbed microphone position | average pulled towards a response nobody hears | power average + `max(3 × median, 1.5 dB)` outlier rule, `POSITION_OUTLIER` | `SpatialAveragerTest` |
| 12 | EQ fills a position-dependent null | excursion spent on a dip only one seat hears | 1-octave broad-boost rule indoors + `null_boost_db` gate | `AutoEqRendererTest` (broad boost), probe `--positions 3` |
| 13 | Speaker driven past its limit (or damaged) | a "corrected" sound that is distorted at every level | THD from the sweep, `SPEAKER_DISTORTION` above 3 % | `HarmonicDistortionTest`, `pipeline_findings.py` finding 9, probe `--stress` |
| 14 | Loose panel / torn surround | a rattle no EQ can remove | harmonic-series shape (`H5` vs `H3`), `DRIVER_RATTLE` | `HarmonicDistortionTest.onlyAFlatHarmonicSeriesCountsAsRattle`, probe `--stress` |
| 15 | Lively room | measurement smoothed by its own reverb, user wonders why it sounds softer | reverb estimated from the same IR, `REVERBERANT_ROOM` ≥ 0.8 s | `RoomDecayTest`, `pipeline_findings.py` finding 10, probe `--rt60` |
| 16 | The correction never reached the speaker (wrong route, engine off) | the app promises an improvement it did not deliver | second measurement through the EQ, `Verification` → `NO_CHANGE` / `UNEXPECTED` | `VerificationTest`, `pipeline_findings.py` finding 14, probe `--verify` |
| 17 | "Better" turned out to be "quieter" | the user turns it back up and the EQ is gone | integrated LUFS (BS.1770-4) + true peak on both runs | `IntegratedLoudnessTest`, probe loudness column (`LU`), `loudness_proxy_gap_db` gate |
| 18 | A subwoofer is in the chain and the app tries to correct it | filters and headroom spent on the sub's level knob, mains asked to play below the crossover | declared crossover: correction starts above it, no protective pass | `AutoEqRendererTest.aDeclaredSubwooferStopsTheCorrectionAtTheCrossover`, `check_parity.py`, probe `--sub` |
| 19 | The measuring level is too loud for the speaker | a THD number, and a correction fitted to a distorted sweep | THD from the same sweep, one quieter repeat, derived step | `MeasurementLevelTest`, `pipeline_findings.py` finding 18 |

## 9b. Why there is no convolution mode

`pipeline_findings.py` finding 15 realises the app's own bounded plan twice - with the ten PEQ bands and with
an exact minimum-phase inverse - and scores both against the target. The exact inverse buys 0.06–0.24 dB,
even on a deliberately narrow 10 dB resonance; what is left after it (~0.7 dB) is the plan's range limits and
boost caps, not the filter. Since convolution would cost a screen-capture permission (`VirtualSurroundService`)
plus a second audio path, the answer is no - and it is a measured no.

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
| `SpatialAverager.OUTLIER_FACTOR` / `…_FLOOR_DB` | 3.0 / 1.5 | when a position counts as disturbed |
| `TruePeak.OVERSAMPLE` / `TAPS_PER_PHASE` | 4 / 24 | intersection peak accuracy (0.17 dB grid limit) |
| `PreampPlanner.LIMITER_HEADROOM_ALLOWANCE_DB` | 3.5 | make-up headroom below the limiter (4.0 − 0.5 dB ISP margin) |
| `ListeningEnvironment.INDOOR.recommendedPositions` | 3 | positions the wizard asks for indoors |
| `HarmonicDistortion.THD_WARN_PERCENT` | 3.0 | distortion that makes the measurement unusable |
| `HarmonicDistortion.RATTLE_RATIO_DB` | −6.0 | H5 − H3 above which the series is flat (rattle) |
| `RoomDecay.REVERBERANT_THRESHOLD_S` | 0.8 | from when the room counts as reverberant |
| `RoomDecay.DIRECT_SKIP_SECONDS` | 0.010 | what is *not* room decay |
| `RoomDecay.MIN_R_SQUARED` | 0.98 | how straight the decay must be to be believed |
| `IntegratedLoudness.LOUDNESS_OFFSET_DB` | −0.691 | the standard's offset (applied **once**, inside each block) |
| `IntegratedLoudness.ABSOLUTE_GATE_LUFS` / `RELATIVE_GATE_LU` | −70 / −10 | the two gates of BS.1770-4 |
| `IntegratedLoudness.BLOCK_SECONDS` / `BLOCK_OVERLAP` | 0.400 / 0.75 | gating block length and overlap |
| `Verification.PREDICTION_TOLERANCE_DB` | 1.5 | RMS(measured − predicted) that still counts as "as predicted" |
| `Verification.MIN_AUDIBLE_CHANGE_DB` | 0.5 | below this a change counts as no change |
| `Verification.MIN_CORRELATION` | 0.5 | below this the change is not the predicted one |
| `Verification.JUDGE_LOW_FACTOR` | 1.41 | +0.5 octave above the bass limit: where the correction has authority |
| `AutoEqSettings.subwooferCrossoverHz` | null (off) / 80 / 100 / 120 | a declared sub: correction starts at the crossover, no protective pass |
| `MeasurementLevel.TARGET_THD_PERCENT` | 2.0 % | above it the position is measured again, one step quieter |
