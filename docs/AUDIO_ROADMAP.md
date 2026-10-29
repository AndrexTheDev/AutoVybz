# Audio pipeline – research roadmap

Where the next real gains are, and where they are not. Every claim carries a number from
`tools/probe/pipeline_findings.py` (reproducible, numpy only) or from the live probe
(`tools/probe/live_probe.py`). Nothing here is a wish list: each work package names the defect, the
evidence, the design, the test that proves it and the cost.

Status: research (round 10). Nothing in this document is implemented yet.

```
route ─► rate negotiation ─► stimulus ─► capture ─► audit ─► DC removal ─► deconvolution ─► latency+IR
      ─► pink average ─► SNR ─► blend ─► mic compensation ─► target inversion ─► PEQ fit ─► pre-gain ─► limiter
```

The nine stages are documented in [AUDIO_PIPELINE.md](AUDIO_PIPELINE.md); this file is about stage ten
onwards: what to *add*, what to *stop trusting*, and in which order.

## 1. Where the pipeline stands

Verified by the probe on two reference clips (rounds 8–9): tonal error 0.9–3.5 dB → **0.34–1.24 dB**, tonal p95 ≤ 2.7 dB,
zero clipped samples, loudness within ±1.5 dB on every limiter class, limiter mean gain reduction 0.00 dB
(max 3.5 dB, stress 0.0 %). That is a good place. The remaining problems are therefore *not* in the
filters – they are in what the app measures, what it claims, and what it never looks at.

## 2. Five measured findings

Reproduce: `python3 tools/probe/pipeline_findings.py --music Demosong.mp3` – the script *asserts* its
twelve claims (soft-clip THD ≈ 3 %, monotone growth, true-peak gap 0.3–0.6 dB, window spread < 0.1 dB,
T30 within 0.2 s, band saturation, noise invariance), and the music-free half runs in CI after
`check_parity.py`. If the port changes so that these numbers no longer hold, the roadmap is out of date
and CI says so.

### F1 – Harmonic distortion is already in the data, free

The exponential sweep separates distortion from the linear response: the n-th harmonic appears at
`T·ln(n)/ln(f2/f1)` **before** the impulse-response peak (Farina 2000). With the app's 5 s sweep from
20 Hz to 20 kHz that is +0.502 s / +0.795 s / +1.003 s for H2/H3/H4. Measured on the port:

| stimulus | H2 | H3 | H4 |
|---|---|---|---|
| linear reference | −110.6 dB | −114.5 dB | −116.5 dB |
| soft clip, drive 0.02 | −89.1 dB | **−31.4 dB** | −110.7 dB |
| soft clip, drive 0.08 | −82.1 dB | **−25.2 dB** | −106.0 dB |
| soft clip, drive 0.25 | −70.7 dB | **−16.9 dB** | −94.2 dB |
| hard clip at 0.05 | −53.6 dB | **−9.9 dB** | −61.1 dB |

Two consequences. **Good news:** the app's `irPreSeconds = 0.004 s` window ends long before the first
harmonic peak, so the measured magnitude is not distorted by the speaker *and* the peaks are never
averaged in. **Opportunity:** THD can be computed from the deconvolution the app already performs – no
extra measurement time, no extra permission, one extra pass over an array it already has.

### F2 – The acceptance test checks the wrong peak

After the limiter (bit-identical chain to the app's):

| case | sample peak | true peak (4x, BS.1770) |
|---|---|---|
| source `Demosong.mp3` | −1.00 dBFS | −0.79 dBTP |
| phone-speaker, HARMAN, pre-gain +2.1 dB | −1.62 dBFS | **−1.16 dBTP** |
| party-box, BASS_BOOST, pre-gain +0.3 dB | −1.63 dBFS | **−1.20 dBTP** |

The limiter is sample-peak driven, and the reconstructed waveform overshoots it by 0.43–0.46 dB. The
probe's rule "peak ≤ −0.1 dBFS, 0 clipped samples" and `PreampPlanner`'s static bound both describe the
*sample* grid. The DAC, and every lossy codec after it, sees the other one.

### F3 – The IR window is not the lever; the room is

Measured curve vs. model truth, 1/3-octave, 100 Hz–16 kHz, as reverberation is added to the model:

| RT60 | T30 estimate | 0.08 s window | 0.30 s (app) | 0.80 s window |
|---|---|---|---|---|
| 0.0 s (anechoic tail) | 0.02 s | 0.35 dB | 0.35 dB | 0.35 dB |
| 0.5 s | 0.51 s | 0.96 dB | 1.00 dB | 1.00 dB |
| 0.8 s | 0.80 s | 1.88 dB | 1.86 dB | 1.86 dB |
| 1.2 s | 1.06 s | 2.34 dB | 2.35 dB | 2.35 dB |

Changing the window length by a factor of ten moves the error by **< 0.05 dB**; the decay time moves it by
**2 dB**. T30 falls out of the same impulse response for free. So: frequency-dependent windowing is not a
priority, but *knowing* the room is reverberant is – and the app currently discards that information.

### F4 – More bands do nothing

`bt-minibox / HARMAN / street` on the same material:

| bands offered | tonal after | p95 | fit rms / max | bands actually used |
|---|---|---|---|---|
| 5 | 1.69 dB | 3.12 dB | 0.66 / 2.54 dB | 5 |
| 8 | 1.34 dB | 2.70 dB | 0.33 / 1.20 dB | 8 |
| **10 (default)** | **1.33 dB** | **2.72 dB** | **0.22 / 0.81 dB** | 10 |
| 12 | 1.33 dB | 2.72 dB | 0.22 / 0.82 dB | 11 |
| 16 | 1.33 dB | 2.72 dB | 0.22 / 0.82 dB | 11 |

The fitter saturates at 8–10 bands. After that the residual lives in the *desired curve*, not in the
filter count – so "more PEQ bands", "FIR instead of PEQ" and "better optimiser" all target the wrong end
of the chain. (Free tier at 5 bands does cost 0.36 dB – a product decision, but now a quantified one.)

### F5 – More sweeps do nothing

0.43 dB rms measurement error at 60 dB, 24 dB **and** 18 dB SNR – identical. The 5 s sweep has ≈42 dB of
processing gain, so stationary noise is already irrelevant. Repeating the sweep would only help against
*impulsive* disturbances, and those are what `NOISE_UNSTEADY` already reports. Don't buy measurement time.

## 3. What code inspection adds

| # | Observation | Consequence |
|---|---|---|
| C1 | `ListeningEnvironment.INDOOR` sets `broadBoostOctaves = null`, `OUTDOOR` sets 1.0, and `CorrectionPlanner` prefers the class value (`?:`), which is `null` for `STANDARD` | For BT boxes and laptops indoors – exactly where dips are position-dependent nulls – the fitter may boost narrow notches. Measured on `bt-minibox/STANDARD/INDOOR/SMART`: **+4.0 dB at Q 4.0 (353 Hz)** and +1.7 dB at Q 4.0 (95 Hz); the same speaker outdoors gets no narrow boost. `DEVICE`/`RESCUE` are unaffected (1.0 in the class). |
| C2 | `SpatialAverager` power-averages *all* positions, unweighted | One disturbed position poison-pills a 3-position calibration; there is no rejection and no way to redo just that position. |
| C3 | `spreadDb` is surfaced as a hint above 4 dB (`HIGH_POSITION_SPREAD_DB`) but has no consequence | The user is told "your positions disagree" and can only start over – the diagnostic exists, the remedy does not. |
| C4 | Nothing re-measures after applying | The promise "better" is a model prediction. The probe can judge it; the app cannot, and the user has no way to verify. |
| C5 | `AnalyzerConfig` keeps the whole tail; no decay/RT60/distortion metric exists | Free information (F1, F3) thrown away. |
| C6 | `LufsProcessor` is momentary LUFS only (no gating, no true peak) | Level claims cannot be verified on device with BS.1770 semantics. |
| C7 | A PipeWire-derived partitioned convolver (`libeq314hrir`, `HrirConvolver`) already ships – but its playback path is `VirtualSurroundService` over **MediaProjection** | A FIR correction path is technically present, but only behind a screen-capture permission. That is the real cost of "FIR mode", not the DSP. |
| C8 | The probe's "truth" is one position of a fixed room model | It cannot see position dependence, so it *rewards* boosting a single-position null (C1). Fixing the harness must come first. |

## 4. Work packages

Priority is value per unit of risk; `S` < 2 days, `M` 2–5 days, `L` > 1 week (one developer, this repo's
test discipline included).

### W1 – Harness: a position-aware room model (S, enabler)

*Why:* C8. Every dip-filling decision (C1, W5) is currently judged by a model that has only one listening
position, so the judge is biased towards exactly the mistake the literature warns about (EQ cannot fill a
null; move the speaker instead).

*Design:* `rooms.SCENARIOS` gains 3–5 position variants per scenario – the comb-notch delays shift by
±1–3 cm and the modal gains vary by ±3–6 dB, the way they do over a 30–50 cm move. `run_case` gains
`--positions N`: measure each position, judge each, and judge the *average* against the power-averaged
truth. A new metric `null_boost_db` = the largest boost inside a region where the truth varies by more
than 6 dB across positions.

*Test:* the current INDOOR correction must show a non-zero `null_boost_db`; with `broadBoostOctaves = 1.0`
it must drop below 1 dB while the average tonal error stays within +0.2 dB.

*Cost/risk:* small, contained to `tools/probe`. Risk: an over-eager judge that forbids legitimate broad
bass correction – mitigated by the 6 dB position-variance gate.

**Done (round 11).** `rooms.position_variants(model, count, room=…)` moves the listening spot by up to
`POSITION_SPREAD_M` = 0.4 m: every room mode (43–145 Hz) gets its own gain (coupling of the mode shape,
±4 dB scatter, Q 8–11) and every reflection path its own delay (±spread/c) and level, which is what
produces the position-specific comb notch. The probe measures and judges per position
(`--positions N`), averages with the app's own rule (power mean, 300 Hz–3 kHz renormalisation) and adds
two gates: `position_spread_db` and `null_boost_db`.

Two corrections to the plan came out of the first honest run, both of which changed the design:

1. The naive variant model (mode gain only) produced **no** spread above 6 dB, so the null gate could
   never fire – a judge that always passes. Real rooms vary 10–15 dB per mode across 40 cm; with the
   scatter the mini box now shows 12.2 dB spread at 43 Hz, i.e. the gate has teeth.
2. `null_boost_db` measures the *band response* (dB) of every narrow boost (Q ≥ 3) inside the spread
   region, not the summed correction as first drafted: a broad bass shelf legitimately crosses such a
   region and must not be flagged. Threshold 2.5 dB.

A third lever followed from the model: `--truth-positions M` judges a result at other seats than it was
fitted at, which answers "is one measurement enough?". Measured on the mini box, judged at three seats:
one position 0.63 dB RMS (p95 1.38), three positions 0.50 dB (p95 0.84) – the averaging is worth ~20 % of
the residual and a third of the tail, which is the empirical basis for W5's `recommendedPositions = 3`.

### W2 – True peak as the acceptance quantity (S)

*Why:* F2. "0 clipped samples" is a sample-grid statement.

*Design:* `measurement/dsp/TruePeak.kt` – 4x oversampled magnitude peak (polyphase FIR, 32 taps, or a
2× FFT upsample), pure Kotlin, unit-tested against a known inter-sample-peak signal. Used by (a) the
probe (`peak_dbtp ≤ −1.0`, replacing `peak_dbfs`), (b) `PreampPlanner` as an *explanatory* bound
(`LIMITER_HEADROOM_ALLOWANCE_DB` becomes 4.0 − the measured ISP margin ≈ 0.5 → 3.5 dB, or the margin is
computed per plan), (c) `AntiClipping` docs. No new runtime cost in the audio thread – this is a planner
number, not a real-time meter.

*Test:* `check_parity` gains the oversampled peak of a known waveform; the probe's six cases must stay
≤ −1.0 dBTP with the 3.5 dB allowance (one probe run settles it).

*Risk:* low. It may cost ~0.4 dB of make-up loudness – the honest price of not trusting a sample-peak
meter.

**Done (round 11).** `measurement/dsp/TruePeak.kt`: 4× oversampling with a 24-tap/phase Kaiser(β = 8)
windowed-sinc kernel, per-phase DC normalisation (so a DC input reads −6.021 dBTP instead of a
phase-dependent value), zero-phase centring and a power-of-two FFT for the offline variant. Python mirror
in `avzprobe/audio.py`, parity in `check_parity.py`: FIR vs FFT agree to **+0.007 dB** on 20 kHz
band-limited noise, the 12 kHz/45° acceptance signal reads −0.168 dBTP where the sample grid says
−3.01 dBFS (a 2.84 dB lie), and the gain staging drops to `LIMITER_HEADROOM_ALLOWANCE_DB = 3.5`
(4.0 − 0.5 dB intersample margin). The probe now asserts `peak_dbtp ≤ −1.0`: all six cases pass, the
party box moving from −1.28 to −1.05 dBTP – i.e. the sample meter had been claiming 0.6 dB of headroom
that was not there.

*Known limit, documented in the KDoc:* a 4× grid still under-reads a crest that sits between the
oversampled taps by `1 − cos(π/8)` = 0.17 dB near fs/4. 8× would halve that; the cost is not worth it for
a planner number, but the residual belongs in the safety margin (it is inside the 0.5 dB allowance).

### W3 – THD from the sweep (M)

*Why:* F1. Detects the two field failures nothing else sees: a speaker driven into distortion by the
measurement level, and a damaged/rattling driver. It also opens W11 and strengthens the "tiny speaker"
classes.

*Design:* in `MeasurementAnalyzer`, after `deconvolveSweep`, for orders 2…5 read the energy in a ±1 ms
window at `peak − T·ln(n)/ln(f2/f1)`, expressed relative to the linear IR energy → `Hd_n_db`, `thd_pct`.
Guard: only report when the peak is ≥ 12 dB above the local noise (the deconvolved noise floor sits at
−110 dB in the clean case, so the margin is real). New warnings: `SPEAKER_DISTORTION` (> 3 % THD: the
measurement level is too high or the speaker is broken → suggest one step quieter and a repeat) and
`DRIVER_RATTLE` (H3 −> H2 by > 6 dB, the signature of mechanical contact).

*Test:* goldens from the table above (`soft clip 0.02` → −31.4 dB ±1 dB), one JVM test that a linear
system reports < 0.5 % THD, plus a port parity check.

*Risk:* medium – the windows must not catch the pink-noise segment into the deconvolution; the ±1 ms
window and the noise-guard limit that. Needs the device to calibrate the 3 % threshold against real
speakers.

**Done (round 12).** `measurement/dsp/HarmonicDistortion.kt` reads H2…H5 at `T·ln(n)/ln(f2/f1)` before the
main peak, with a ±1 ms window each and a *local* floor taken from five control points placed between the
harmonics (ln 2…ln 5 = 0.69 / 1.10 / 1.39 / 1.61, controls at 0.55 / 0.90 / 1.25 / 1.50 / 1.85) – so the
threshold is "the harmonics must clear the room's own decay", not "must clear a global noise number".
A harmonic that does not clear that floor by 6 dB is *left out* instead of being reported as noise, and a
peak that does not clear it by 12 dB produces no THD number at all rather than "0.0 %".

Two things the implementation taught us:

1. **The golden value reproduces the roadmap's prediction exactly.** `pipeline_findings.py` now prints
   `soft clip 0.02 → H3 −31.4 dB` (the roadmap said "−31.4 dB ±1") and a monotonically rising series
   2.69 % / 4.02 % / 6.54 % THD for drive 0.02 / 0.05 / 0.10.
2. **The proposed rattle rule was wrong.** "H3 − H2 > 6 dB" fires on *every* symmetric soft clip, because a
   symmetric nonlinearity produces only odd orders: our soft clip reads H2 −74 dB against H3 −29 dB, i.e.
   +45 dB, while nothing rattles. A mechanical buzz is impulsive, i.e. broadband, and its series does not
   fall with order. The shipped rule is therefore "H5 within 6 dB of H3" – `DRIVER_RATTLE`. The old rule
   would have flagged every driven speaker as broken, which is worse than not having the warning.

Warnings: `SPEAKER_DISTORTION` (≥ 3 %) is *severe* in the wizard (the fix is one step quieter and a repeat),
`DRIVER_RATTLE` is informational-with-advice (an EQ cannot repair a loose panel). Both are silent on the six
standard probe cases, whose chain is linear.

### W4 – Room decay (T30) and a trust gate (M)

*Why:* F3 – reverb costs 2 dB of accuracy and the app throws the information away.

*Design:* Schroeder backward integration on the tail of the recording (the capture already contains latency +
`irPost` + margin; `CaptureTiming.tailSeconds` grows by up to 0.5 s, clamped by `MAX_TAIL_SECONDS`). Report `room_rt60_s`, raise `REVERBERANT_ROOM` above 0.8 s, and *gate* the correction: above the
transition frequency scale `cutTrust` by 1/(1 + rt60) and keep `broadBoostOctaves` ≥ 0.5. UI: one line –
"lively room (0.9 s), correcting less in the midrange".

*Test:* `pipeline_findings.py` already recovers T30 within 0.14 s on synthetic rooms; the JVM test reuses
that shape; the probe must not regress its six cases.

*Risk:* medium-low. Estimating decay from the *unwindowed* IR in a noisy outdoor recording is the weak
spot – require a 10 dB decay range and report "unknown" otherwise.

**Done (round 12), partly – and the part that is not done is a decision, not an omission.**
`measurement/dsp/RoomDecay.kt` integrates the unwindowed response (Schroeder), skips the direct sound and
fits a line over −5 … −25 dB, extrapolated to T60. Two thresholds were *calibrated against failures*:

1. **The direct sound is not room decay.** Including it made every room ~40 % drier (0.50 s read as 0.27 s)
   because the first milliseconds fill the top of the Schroeder curve. Skipping 10 ms fixes it to within
   2 % (0.40 / 0.80 / 1.20 s → 0.41 / 0.81 / 1.19 s).
2. **A plateau has no slope.** A threshold-crossing T30 happily extrapolates the noise floor: our own
   traffic model turned a 0.9 s room into **5.3–5.5 s** at 20 dB SNR. The fit is now rejected unless the
   plateau sits 10 dB below the fit region and R² ≥ 0.98 – in the noisy cases that means "unknown".

`REVERBERANT_ROOM` is raised from 0.8 s and reports the number ("This room rings a lot (1.0 s)").

The **gate** (`AutoEqSettings.roomRt60S`, `RoomDecay.boostTrust` = 1/(1+T60) above the room's Schroeder
frequency for an assumed 40 m³) is implemented, mirrored in the probe and **deliberately off**: measured on
the rever(berant) cases it costs 0.11 dB of tonal accuracy (mini box 0.89 → 1.00 dB) and its benefit - "less
muddy" - is a listening impression no model here can score. Turning it on would be a claim we cannot
verify; the knob exists so a device run can settle it.

### W5 – Multi-position indoors, with outlier rejection (S)

*Why:* C1/C2/C3. Two defects for the price of one: indoors the app *recommends one position* where the
physics says three (`OUTDOOR` already recommends 3), and a disturbed position corrupts the average
without being excluded (only a hint appears, after the fact).

*Design:* `ListeningEnvironment.INDOOR.recommendedPositions = 3` (still capped by entitlement); set
`broadBoostOctaves = 1.0` for `INDOOR` in `ListeningEnvironment` – which only affects the classes that
leave it `null` (`STANDARD`), since `CorrectionPlanner` prefers the class value (after W1 proves it); in `SpatialAverager`, reject any position whose
`spreadDb` contribution exceeds 3× the median and raise `POSITION_OUTLIER` with a "repeat just this
position" action (`WizardStep.Error.resumePosition` already supports it).

*Test:* `SpatialAveragerTest` gains an outlier case (two clean positions + one 8 dB-off) → the average
must match the two clean ones within 0.2 dB; the wizard test drives the resume path.

*Risk:* low, but it changes a user-facing recommendation – needs the German/English/Spanish copy and the
entitlement limits reviewed.

**Done (round 11).** `SpatialAverager.analyse()` returns `SpatialAverage(result, spreadDb, rejected,
deviations)`; a position is dropped when its level-aligned RMS deviation from the *median* curve of all
positions (100 Hz–10 kHz, normalised 300 Hz–3 kHz) exceeds `max(3 × median deviation, 1.5 dB)`, never
below three positions and never below two kept ones. The median is not cosmetic – the first version used
the mean, and the test showed why that cannot work: with one 20 dB suck-out among four positions the
deviations read 5.8/4.6/4.3/4.7 dB (nothing stands out, nothing is rejected), against the median
2.5/2.4/1.4/**8.5** dB. `live_probe` mirrors the rule, so its spread is measured on the kept positions. `MeasurementController.combine()` averages the kept positions, adds `POSITION_OUTLIER` to the
warnings and reports `rejectedPositions`; the wizard has copy for it in en/de/es (the existing
`resumePosition` path repeats exactly one position). Indoors, `recommendedPositions = 3` and
`broadBoostOctaves = 1.0` (free tier still capped at one position by `EntitlementPolicy`).

Measured effect (probe, three seats): mini box SMART 0.63 → 0.50 dB, p95 1.38 → 0.84 dB; HARMAN
0.68 → 0.53 dB. With the 1-octave broad rule the largest narrow boost inside a > 6 dB spread region is
**1.96 dB** (HARMAN, mini box) – under the 2.5 dB gate, where the old unconstrained fitter put 4 dB of
Q 4 boost at 74 Hz into a mode null.

### W6 – Verification pass: measure the result (L)

*Why:* C4, and it is the only feature that turns "trust us" into "here is your speaker before/after".

*Design:* after `applyComposed`, an optional 10 s "Check" run re-measures through the active EQ, computes
the residual tonal error with the same judge the probe uses (already ported: `weighted`, `free_offset`,
the fade edge), and shows *measured* before/after plus the predicted one. Fails loudly when the result
differs from the prediction by > 1.5 dB (wrong route, user changed the volume, speaker rattling).
Reuses the whole measurement path – no new DSP.

*Test:* probe case that simulates a verification run (measure → correct → re-measure) and asserts the
measured improvement is within 0.5 dB of the prediction; E2E test for the new step.

*Risk:* high-ish: it is a new wizard state, a new failure surface and it costs the user 10 s. Worth it:
it is also the field-diagnostics channel for everything else in this list.

**Done (round 13, DSP; round 14, wizard step).** The comparison: `measurement/Verification.kt`
(`compare(before, after, autoEq)` → `Report`) judges three curves: the measured change
(`after − before`), the plan's own filter response, and the residual against the target, before / after / as
predicted. It reports `correlation`, `predictionErrorDb`, the three residuals, `measuredImprovementDb` and
`predictedImprovementDb`, and a `Verdict`.

Decisions worth keeping:

* **Level-invariant on purpose.** Both shapes are normalised in 300 Hz–3 kHz, so a user who changes the
  volume between the runs invalidates nothing (`aLevelChangeBetweenTheRunsDoesNotMatter`). The pre-gain is a
  pure level change and disappears in the normalisation – exactly right for a *shape* comparison. The first
  implementation relied on the *analyzer* having normalised both curves and forgot that the test's own
  "volume down 4 dB" fixture is applied afterwards; CI failed it, and the fix is one band-mean on the measured
  difference, which is the right place for it anyway (a capture-gain change must not look tonal).
* **Correlation, not magnitude, separates "nothing happened" from "something else happened".** Verdict order:
  flat-plan pass → `NO_CHANGE` (< 0.5 dB measured change) → `AS_PREDICTED` (≤ 1.5 dB prediction error) →
  `UNEXPECTED` (correlation < 0.5) → `WEAKER` / `BETTER` by comparing the two RMS changes.
* **The judged band starts at `1.41 × lowLimit`** (+0.5 octave above the bass limit, floored at 150 Hz): below
  that the correction has no authority, and judging there would punish the app for what it deliberately
  refused to fix.
* **Not an auto-retry.** A verification that disagrees with its own prediction is information for the user
  ("your speaker does not do what we predicted – here are both curves"); silently re-fitting until the
  numbers agree is how a diagnostic becomes a lie.

Measured (`pipeline_findings.py` finding 14, three scenarios at 18 dB SNR): measured improvement
+1.66 / +1.73 / +2.64 dB against +1.73 / +1.82 / +2.53 dB predicted – worst gap **0.11 dB**, all
`AS_PREDICTED` – while the same comparison on a chain that never applied the filter returns `NO_CHANGE`
in all three. `live_probe.py --verify` reproduces it on the six full scenarios (prediction errors
0.05–0.34 dB).

The wizard step (round 14) is deliberately small: no new DSP and no new measurement state, just

* `MeasurementController.run(keepEqActive = true)` – the one line that matters. Every normal calibration
  pauses the EQ (`EqBypass.pauseIfRunning`), because it must measure the speaker and not the speaker plus
  the previous correction. The check run must do the exact opposite, and that is the whole trick: the
  stimulus then travels through the engine, the route and the saved preset.
* a `Verification.Report?` on `WizardStep.Saved` plus `WizardViewModel.onVerifyClicked()` (countdown →
  measure → `Verification.compare` → same card, now with the verdict), and `VerificationCard` in
  `WizardScreen`.
* three decisions worth keeping: the check is offered **only when the preset was applied** (a saved-only
  preset is not in the signal path, and "nothing changed" would be the app's own fault); it sets the
  measuring volume again, because a check at a much lower volume would verify with less SNR than the run it
  judges; and a **cancelled or failed check returns to the saved card** rather than the start screen - the
  calibration is stored, so throwing the user out of the flow would be the only real damage the check could
  do. `Report.isConfirmation` (AS_PREDICTED or BETTER) is what decides between gold and warning; `WEAKER`
  is an improvement but not a confirmation, and a test pins that.

### W7 – Bounded adaptive target (M)

*Why:* F4 – after 10 bands the residual is the target's shape. Fixed shelves fight a speaker's natural
tilt; a bounded tilt that follows the measured trend removes the systematic part without hiding defects.

*Design:* `TargetType.HARMAN`/`SMART` keep their shelf but add a *bounded* extra tilt (0 … −1 dB/oct),
fitted to the measured in-room trend between 300 Hz and 16 kHz, and always ≤ half of the measured tilt.
Store the applied tilt in the preset so it is visible and reversible in Pro.

*Test:* probe must keep or improve every case (target: ≤ 0.8 dB tonal on all six) **and** a new check that
a deliberately wrong measurement (a 2 dB tilt error) does not move the target by more than 30 %.

*Risk:* medium – it can mask a real defect; the "≤ half of the measured tilt" cap is the guard.

**Measured (round 14) – not adopted.** Implemented in the probe (`live_probe.py --adaptive-target`,
`--adaptive-pivot`) and in `avzprobe/eqfit.py` (`adaptive_tilt_db_per_octave`: least-squares slope over
300 Hz–16 kHz, never upward, half of it, at most 1 dB/octave, pivoting at the lower edge of the correction
range so it cannot ask for more bass). Then measured against the *fixed* target, which is the only honest
reference – a target that follows the speaker cannot grade its own homework.

| model | measured tilt | distance to the fixed target (fixed → adaptive) | plan's bass gain |
|---|---|---|---|
| bt-minibox | −0.38 dB/oct | 0.55 → 0.84 dB | +1.89 → +1.99 dB |
| party-box | −0.64 dB/oct | 0.94 → 1.38 dB | −0.47 → +0.46 dB |
| laptop | −0.32 dB/oct | 0.08 → 0.56 dB | +0.07 → +0.06 dB |
| phone-speaker | 0.00 (rising trend, no tilt) | 2.47 → 2.47 dB | +0.48 → +0.48 dB |

Every case that gets a tilt ends up *further* from the target the user picked, and the party box – the one
case where the app was cutting bass – starts boosting it instead. That is structural, not a tuning problem:
a tilt is renormalised to 0 dB in 300 Hz–3 kHz, so a downward tilt in the treble is a bass boost in
disguise. The full probe agrees: on the six live scenarios the tonal error after correction went
0.85/1.04/0.88/0.32/0.38/0.80 → 0.87/1.15/1.11/0.32/0.15/0.94 dB with the 300 Hz pivot (worse in four of
six, better only for the laptop, and the RESCUE case regressed in the bass transition band).

So: fixed targets stay, and "follow the room" can only ever be an explicit *taste* option ("a bit more
bass"), never an automatic adaptation – it would silently move every calibration towards the same warm
curve. `pipeline_findings.py` finding 16 keeps that decision measured, and the probe flags stay so a future
change can re-run it.

### W8 – Bass management for speakers with a sub (M)

*Why:* party boxes and hi-fi setups are in the probe's scenario list, but the app has no concept of a
subwoofer: it protects the mains with `protectiveHighPassHz` and otherwise treats the system as
full-range.

*Design:* a Pro setting "subwoofer below 80/100/120 Hz": raise the protective high-pass to the crossover,
forbid correction below it (the existing `lowLimit` machinery already does this), and let the correction
judge the system response from the crossover up. No channel splitting – the sub has its own crossover.

*Test:* new probe scenario `party-box + sub` (model: mains high-passed at 100 Hz, sub added 100 Hz–40 Hz);
tonal error must not grow and the `protect_boost_below` rule must hold at the crossover.

*Risk:* low DSP risk, medium product risk (users expect the app to know their hardware – it does not).

### W9 – Convolution mode (L, research)

*Why:* the only way past the PEQ ceiling is an FIR correction – but F4 says the ceiling is the *plan*, not
the filters, so the expected gain is small (narrow resonances, excess phase below ~300 Hz).

*Design (investigate first, build later):* compute the exact minimum-phase inverse in numpy, judge it with
the probe, and only then decide. The playback problem is already solved in the tree (`HrirConvolver`,
`libeq314hrir`) but requires `VirtualSurroundService`'s MediaProjection path (C7) – a screen-capture
permission for a bass correction is a bad trade unless the gain is demonstrable.

*Test:* extend `pipeline_findings.py` with "10 PEQ vs. exact inverse, same truth" – if the difference is
< 0.2 dB, close the package.

*Risk:* high (permission UX, CPU, battery). Do the measurement before any code.

**Measured (round 14) – closed.** `pipeline_findings.py` finding 15 realises *the same bounded plan* twice:
once with the ten PEQ bands the app fits and once as an exact minimum-phase inverse (the limit of any filter),
then scores both against the target over the full-authority band.

| case | before | 10 PEQ | exact inverse | gain |
|---|---|---|---|---|
| bt-minibox | 2.56 dB | 0.77 dB | 0.69 dB | 0.08 dB |
| party-box | 2.69 dB | 0.85 dB | 0.69 dB | 0.17 dB |
| laptop | 1.36 dB | 0.08 dB | 0.02 dB | 0.06 dB |
| bt-minibox + a deliberately narrow 10 dB peak at 1.2 kHz | 2.81 dB | 0.98 dB | 0.75 dB | 0.24 dB |

That is 0.06–0.24 dB for unlimited filter capability, and the narrow resonance – the one case where the
roadmap expected FIR to matter – gains the most and still stays under a quarter of a decibel. What survives
the exact inverse (~0.7 dB on the two speakers that need a correction) is the *plan*: range limits, boost
caps, the 6 dB/null gates. A screen-capture permission, a second audio path and the battery cost of a
convolution engine would buy about a fifth of a decibel - not a trade worth making. W9 is closed by
measurement instead of opinion.

### W10 – BS.1770 verification of the level promise (S)

*Why:* C6. The app promises "better, not quieter"; `LufsProcessor` only knows momentary loudness.

*Design:* add *integrated* loudness (400 ms blocks, −70 LUFS absolute gate, −10 LU relative gate) and true
peak (W2) to `LufsProcessor`; use it in W6 to show "before −11.5 LUFS, after −11.3 LUFS".

*Test:* EBU Tech 3341-style cases (a −23 LUFS sine, a gated silence) – the standard vectors are small and
make an excellent JVM test.

*Risk:* low, pure DSP with published test vectors.

**Done (round 13).** `measurement/dsp/IntegratedLoudness.kt`: K-weighting as the *two biquads of the
standard* (`shelf`, `rlb`), gated integrated loudness (`integrated`, `integrate`, `analyse` →
`integratedLufs` + `truePeakDbtp` + `blocks`), `loudnessDifferenceLu`, `samplePeakDbfs`. The probe mirror is
`tools/probe/avzprobe/loudness.py`; `live_probe.py` now reports **measured** loudness instead of the
A-weighted proxy (`loudness_proxy_delta_db` keeps the proxy alongside, and a > 1 dB disagreement between the
two is itself a failure – the proxy has been right on all six scenarios so far).

Two traps, both found by the checks rather than by ear:

1. **RBJ cookbook biquads are the wrong derivation.** The standard's filters are *analog prototypes*
   mapped with the bilinear transform, prewarped with `tan(π f0 / fs)`. Using the RBJ shelf/high-pass
   formulas gives 1 kHz +0.44 dB instead of +0.70 dB – a constant **0.26 dB error on every LUFS value**,
   which no plausibility check would have caught. With the prewarped form the coefficients reproduce the
   48 kHz tables of BS.1770-4 to 8.9e-16 (`check_parity.py`).
2. **Do not apply −0.691 dB twice.** The offset belongs to each block's loudness; the gated mean already
   averages in the power domain and therefore carries it. Applying it again shifts everything by exactly
   0.691 dB – the classic way to be "close but not the standard".

Anchors: a full-scale 1 kHz sine reads −3.00 LUFS, a −23/−33 LUFS sine reads back exactly, EBU 3341 case 3
(−72 / −36 / −72) gives −36.02, the relative-gate case gives −23.11 against a raw energy average of −25.92,
and 44.1 kHz reads the same loudness as 48 kHz (the coefficients are derived, not tabulated).

Two more numbers the first CI run corrected: the gating block straddles quiet/loud boundaries, and those
blocks are genuine programme – with a 2 s / 8 s / 2 s fixture the absolute-gate test read −36.16 instead of
−36.00, and even 2/20/2 only reaches −36.06. The test now uses the standard's 10/60/10 layout (−36.02) and
says why. And the true-peak expectation was copied from the *full-scale* 12 kHz fixture: at amplitude 0.5 the
same tone is −6.02 dBTP (read: −6.19 through the 4× grid), not around 0 dBTP.

Third lesson: the K-weighting *shape* assertions must use the actual curve, not the asymptote. The first
version of the JVM test demanded 40 Hz < −6 dB and 20 Hz < −14 dB; the filters give −5.6 and −13.3 dB there
(the −12 dB/octave asymptote only holds an octave below the 38 Hz corner). `check_parity.py` failed before
CI ever saw it – which is the entire point of the parity harness.

### W11 – Distortion-driven measurement level (M, needs W3)

*Why:* the app picks 70 % of `STREAM_MUSIC` (50 % for device speakers). That is a guess; THD turns it
into a measurement: raise the level until THD reaches 2 %, then back off 3 dB.

*Design:* `VolumeGuard` + one extra step in the wizard: measure once, read THD, adjust, re-measure if the
level moved by more than one step. Bounded to one retry.

*Test:* probe scenario with a nonlinear speaker model; THD must land in 1–3 % and the tonal error must not
degrade.

*Risk:* medium (more measurement time), depends entirely on W3's calibration.

## 5. Deliberately not doing

| Idea | Why not |
|---|---|
| More PEQ bands (12/16) | F4: identical output, more CPU and more places to go wrong. |
| Repeating sweeps for SNR | F5: 18 dB SNR already costs nothing; a repeat only helps impulsive noise, which is detected instead. |
| Frequency-dependent windowing (FDW) | F3: 0.08 → 0.80 s moves the error by < 0.05 dB. The lever is the room's decay, not the window. |
| Look-ahead limiter | The engine's stage has no look-ahead and cannot get one; the 0.01 ms attack already holds dense masters at 0 clipped samples. Revisit only if a case clips. |
| Per-channel L/R correction | The upstream engine supports it, but per-channel bass correction unbalances the modes between the two speakers (and requires two mic positions per channel). Research spike only, after W5. |
| A better global optimiser than the greedy + Nelder-Mead | The fitter realises its plan to 0.22 dB rms; the plan is the limit (F4). |

## 6. Suggested order

```
W1 (judge first) ──► W5 (positions + dip rule, needs W1)
   │
   ├──► W2 (true peak)  ──► W10 (BS.1770)  ┐
   ├──► W3 (THD)        ──► W11 (level)    ├──► W6 (verification pass)
   └──► W4 (decay)                          ┘
                                   W7 (adaptive target) ──► W8 (sub)
                                   W9 (convolution: measurement only)
```

* **Round 11 (done):** W1, W2, W5 – harness first, then the two "we measure the wrong thing" defects.
* **Round 12 (done):** W3, W4 – the two free metrics (THD, RT60) and their warnings (W4's gate measured,
  deliberately off).
* **Round 13 (done):** W10, W6 – BS.1770 integrated loudness + the verification comparison (DSP and probe).
* **Round 14 (in progress):** W6's wizard step (done: the "Check" run on the saved card), W9's measurement
  (done: closed, it would buy 0.06–0.24 dB) and W7 (done: measured, not adopted). Left: W8.

## 7. Questions that need a device, not a model

1. Real THD of the ten most common budget speakers at 70 % volume – sets the 2 %/3 % thresholds (W3).
2. Typical in-room RT60 for the "indoor" use case – sets the 0.8 s gate (W4).
3. How far apart do users actually hold the phone between the three positions – sets the outlier
   threshold of W5 (is 3× the median right, or 2×?).
4. Whether a real phone's mic clipping, not the speaker's THD, limits the measurement level first (W11).
