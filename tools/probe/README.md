# `tools/probe` – the offline live probe

A dependency-light Python port of the AutoVybz DSP/Auto-EQ stack plus a harness that runs **real programme
material** through the complete chain (measurement → analysis → correction → gain staging → limiter) and
judges the *result*. It exists because the Kotlin unit tests can only check the app against its own
assumptions; the probe checks it against physics and against music the user would actually play.

```
python3 tools/probe/check_parity.py      # 27 self-checks against the Kotlin golden values
python3 tools/probe/live_probe.py --music <file[:file…]> [--json out.json] [--markdown out.md]
```

Requirements: `numpy` (plus `ffmpeg` on `PATH`, or `imageio-ffmpeg`) for `live_probe.py`;
`check_parity.py` needs `numpy` only.

## What the probe models

| Piece | Source of truth in the app |
|---|---|
| Sweep + pink stimulus, Kirkeby deconvolution, IR window, SNR, dynamic smoothing | `measurement/dsp/StimulusGenerator`, `MeasurementAnalyzer`, `FrequencyGrid`, `SpectrumBinning` |
| Target curves, low-limit detection, desired curve (excursion guard, range fade, SNR gate) | `measurement/eq/AutoEqRenderer`, `TargetCurve` |
| Band fitter (greedy + Nelder-Mead + merge), pre-gain policy, limiter | `measurement/eq/PeqFitter`, `PreampPlanner`, `AntiClipping` |
| Speaker / room / microphone physics | `avzprobe/rooms.py` (explicit, documented model – not a measured speaker) |

`avzprobe/rooms.py` builds **minimum-phase** impulse responses. That is not cosmetic: a zero-phase
`irfft(magnitude)` splits its energy into negative and positive time, a linear convolution keeps only the
causal half, and the probe measured the played bass **+6.6 dB louder than the modelled response** (20 Hz on
a 60 Hz mini box) – every before/after comparison was made against a transfer function the app never saw.
`check_parity.py` now asserts that playing a model impulse response through the analyzer recovers
`SpeakerModel.response` within 0.3 dB RMS (see `playback matches the model`).

## What is judged

Six scenarios (`CASES`) covering all three speaker classes, both environments, four targets and three noise
profiles, each judged on the programme material the user supplied:

| Metric | Meaning | Threshold |
|---|---|---|
| `fit_rms_db` / `fit_max_db` | did the fitted biquads realise the *plan*? | 0.75 / 2.0 dB |
| `tonal_rms` / `tonal_p95` | weighted tonal error after vs. before, inside the full-authority band | ≤ 1.0 dB or 0.25 dB better; p95 ≤ 3.0 dB |
| `loudness_delta_db` | perceived level (A-weighted, music-weighted) before vs. after | ±1.5 dB (limiter classes) |
| `peak_dbfs`, `clipped_samples` | real peaks after the limiter | ≤ −0.1 dBFS, 0 clipped |
| `protect_boost_db` | boost below half the detected bass limit | ≤ 1.0 dB |
| `bass_regression_db` | did the correction make the region below the fade *worse*? | ≤ 0.5 dB |
| `above_range_db` | large change above the (mic-untrusted) correction range | ≤ 3.0 dB |

Two judging rules exist for a reason and should not be "simplified" away:

* **The lower edge of the judged band is `lowLimit × √2`, not `lowLimit`.** `AutoEqRenderer.rangeWeight`
  fades the whole correction in over the half octave above the limit (0 dB at the limit, 100 % at
  `limit × 1.41`) and the excursion guard tapers boosts further. Scoring inside that fade grades the
  documented design, not the result – on the mini box it contributed 9.7 dB at the limit itself, where no
  filter in the plan is allowed to act. The region below the edge is still checked: it may not get worse.
* **Truth is smoothed with the same 1/1→1/3-octave width the app fits to.** Comparing raw truth against a
  smoothed model blames the app for comb-filter physics it deliberately does not chase.

## Round-8 findings (2026-10)

Running the probe on live-music material found six defects that no unit test caught; all are fixed and
mirrored in Kotlin:

1. **Zero-phase harness impulse responses** – see above (harness bug, `avzprobe/rooms.py`).
2. **The band fitter stopped early**: the greedy acceptance threshold was frozen at 1.5 % of the *initial*
   loss, so it gave up after 4 of 10 bands and left 2.33 dB of the plan un-fitted (`PeqFitter`).
3. **Only one residual extremum was offered** to the optimiser per step, so the greedy followed one lobe at
   a time (`PeqFitter.MAX_PEAK_CANDIDATES`).
4. **The limiter could not hold what the pre-gain promised**: 6 dB of make-up into a −1 dB/10:1/1 ms limiter
   with no look-ahead produced +0.14 dBFS and 13 clipped samples. Measured replacement: −2 dB/20:1/0.2 ms and
   a 4 dB allowance (`AntiClipping.DEFAULT_LIMITER`, `PreampPlanner`).
5. **The clip-safe pre-gain never credited a cut**, so cut-heavy device corrections lost level while the
   static bound demanded attenuation for a boost band that never carried programme energy.
6. **An uncapped 6 dB treble boost** above 10 kHz: ~0 % of the programme energy, 6 dB of master level
   (`SpeakerClassTuning.STANDARD`), and an indoor excursion ramp of a full octave above a limit that is
   already conservative (`ListeningEnvironment.INDOOR_EXCURSION_GUARD_OCTAVES`).

After the fixes (`Intro1.mp4`, 15.9 s, 8.2 dB crest, peak-normalised to −1 dBFS):

| Scenario | Class | Env | Target | Noise | Tonal before | after | p95 | Loudness | Peak | Preamp | Bands |
|---|---|---|---|---|---|---|---|---|---|---|---|
| bt-minibox | STANDARD | INDOOR | SMART | quiet-room | 2.56 dB | **1.06 dB** | 1.62 dB | −7.06 dB | −5.92 dBFS | −6.9 dB | 10 |
| bt-minibox | STANDARD | INDOOR | HARMAN | street | 3.46 dB | **1.90 dB** | 2.48 dB | −6.17 dB | −5.33 dBFS | −6.8 dB | 10 |
| party-box | RESCUE | OUTDOOR | HARMAN | party | 1.77 dB | **0.88 dB** | 1.78 dB | −0.64 dB | −1.42 dBFS | +0.3 dB | 9 |
| phone-speaker | DEVICE | INDOOR | HARMAN | quiet-room | 1.05 dB | **0.42 dB** | 0.57 dB | −1.47 dB | −1.81 dBFS | +2.1 dB | 7 |
| laptop | STANDARD | INDOOR | SMART | quiet-room | 0.88 dB | **0.38 dB** | 0.69 dB | −5.71 dB | −6.01 dBFS | −5.2 dB | 5 |
| party-box | STANDARD | OUTDOOR | BASS_BOOST | street | 1.79 dB | **0.80 dB** | 1.56 dB | −0.77 dB | −1.47 dBFS | +0.3 dB | 10 |

**6/6 cases within every threshold**: worst tonal error 1.90 dB, worst p95 2.48 dB, worst peak −1.42 dBFS,
zero clipped samples, loudness within ±1.5 dB on every limiter-armed class.

The loudness column is *not* a defect where it is large: a correction that boosts the bass by 3-6 dB costs
that much headroom on full-scale material. Only the limiter-armed classes promise loudness neutrality, and
only within the 4 dB the limiter can actually absorb.

### Sensitivity to a wrong microphone profile

`--mic-error <dB>` injects a **frequency-dependent** profile error (a smooth mid bump plus a treble tilt -
a constant offset would cancel in the 300 Hz-3 kHz normalisation and hide itself). With ±3 dB, 5/6 cases
still pass; the one failure is the mini box with HARMAN under street noise (p95 3.91 dB instead of 2.48 dB).
That is the honest limit of the concept, not a bug: no EQ can undo a measurement error it cannot see.
