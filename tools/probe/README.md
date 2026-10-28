# `tools/probe` – the offline live probe

A dependency-light Python port of the AutoVybz DSP/Auto-EQ stack plus a harness that runs **real programme
material** through the complete chain (measurement → analysis → correction → gain staging → limiter) and
judges the *result*. It exists because the Kotlin unit tests can only check the app against its own
assumptions; the probe checks it against physics and against music the user would actually play.

```
python3 tools/probe/check_parity.py      # 29 self-checks against the Kotlin golden values
python3 tools/probe/live_probe.py --music <file[:file…]> [--json out.json] [--markdown out.md]
python3 tools/probe/pipeline_findings.py --music <file>   # the numbers behind docs/AUDIO_ROADMAP.md
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
| `limiter_mean_gr_db` / `limiter_max_gr_db` / `limiter_stress_pct` | how hard the limiter works (safety net vs. compressor) | ≤ 2.0 dB mean; ≤ 5 % of the programme > 6 dB |
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

## Round-9 findings (2026-10) – a real, dense master

The probe had only ever run on `Intro1.mp4` (15.9 s, 8.2 dB crest, peak-normalised). Round 9 ran it on the
user's own recording (`Demosong.mp3`, 174 s, **5.9 dB crest**, source peak +1.0 dBFS, −11.5 LUFS): a
loudness-war master, i.e. the opposite corner of the signal space. Two harness bugs and one real defect
surfaced; `Demosong.mp3` is now the reference clip for anything peak- or loudness-related.

1. **The loudness check applied the room a second time.** `in_room_after` was added to a signal that had
   already been rendered through the correction, so the comparison measured "correction + room" against
   "room" twice and reported a −3.29 dB level loss on the phone speaker that did not exist (measured:
   −0.95 dB). Both sides now use `in_room_before`.
2. **The free-level offset was taken with a different band than the one judged** (see the judging rules
   above). On a bass-heavy master this alone flipped the party box from "worse" to "better".
3. **The limiter release was too slow for a 5.9 dB-crest master.** With the round-8 defaults
   (−2 dB / 20:1 / **0.2 ms**) the dense material left the correction at **+1.08 dBFS with 30 clipped
   samples** (phone speaker, preamp +2.1 dB) and +0.84 dBFS / 49 clips (BASS_BOOST). A sweep gave:

   | attack | phone speaker | BASS_BOOST |
   |---|---|---|
   | 0.2 ms | +1.08 dBFS, 30 clips | +0.84 dBFS, 49 clips |
   | 0.05 ms | +0.26 dBFS, 3 clips | +0.04 dBFS, 1 clip |
   | **0.01 ms** | **−1.44 dBFS, 0 clips** | **−1.52 dBFS, 0 clips** |

   0.01 ms is fast enough because the limiter has no look-ahead: the overshoot it has to catch is one
   sample wide. Below-first-audibility pumping is not a concern at 20:1 with a −2 dB threshold; the
   stress test (+0…+5 dB of extra make-up) shows mean gain reduction of −0.09…−1.86 dB and ≤ 3.2 % of the
   programme needing more than 6 dB – i.e. the limiter stays a safety net. Both values are checked in
   Kotlin (`AntiClippingTest`) and here.
4. **New metric: how hard the limiter has to work.** A static over-shoot is inaudible; a limiter that
   clamps a large share of the programme is a compressor. `audio.limiter_demand_db()` computes the static
   demand curve sample-wise (vectorised: ten million samples per case) and the probe reports
   `limiter_mean_gr_db` (≤ 2.0 dB) and `limiter_stress_pct` (≤ 5 % of the programme needing > 6 dB), plus
   the deepest single reduction `limiter_max_gr_db` for context. `check_parity.py` asserts the demand curve
   against the textbook `(1 − 1/ratio)` curve: on the current material the *mean* is ~0.00 dB while the
   *max* is 2.8…3.5 dB, i.e. the limiter touches a handful of transients and never rides the programme –
   without the max column that would read like a metric that never fires.

Result on `Demosong.mp3` (same six scenarios):

| Scenario | Class | Env | Target | Noise | Tonal before | after | p95 | Loudness | Peak | Preamp | Bands |
|---|---|---|---|---|---|---|---|---|---|---|---|
| bt-minibox | STANDARD | INDOOR | SMART | quiet-room | 2.50 dB | **0.57 dB** | 1.06 dB | −7.08 dB | −4.72 dBFS | −6.9 dB | 10 |
| bt-minibox | STANDARD | INDOOR | HARMAN | street | 3.46 dB | **1.24 dB** | 2.61 dB | −7.04 dB | −4.81 dBFS | −6.8 dB | 10 |
| party-box | RESCUE | OUTDOOR | HARMAN | party | 0.91 dB | **0.79 dB** | 1.67 dB | +0.27 dB | −1.70 dBFS | +0.3 dB | 9 |
| phone-speaker | DEVICE | INDOOR | HARMAN | quiet-room | 1.27 dB | **0.39 dB** | 0.58 dB | −0.95 dB | −1.44 dBFS | +2.1 dB | 7 |
| laptop | STANDARD | INDOOR | SMART | quiet-room | 1.19 dB | **0.34 dB** | 0.63 dB | −5.17 dB | −3.82 dBFS | −5.2 dB | 5 |
| party-box | STANDARD | OUTDOOR | BASS_BOOST | street | 0.89 dB | **0.64 dB** | 1.33 dB | +0.17 dB | −1.52 dBFS | +0.3 dB | 10 |

**6/6**, worst tonal error 1.24 dB, worst p95 2.61 dB, worst peak −1.44 dBFS, 0 clipped samples.
`Intro1.mp4` still passes 6/6 with the same settings (tonal 2.03→0.54, 2.69→0.85, 1.77→0.88, 0.73→0.32,
0.88→0.38, 1.79→0.80) – the sharper limiter changes peaks, not tonal balance.

Note: `Demosong.mp3` is a **Matroska/WebM container with Opus audio** despite the extension. The probe
decodes it with `ffmpeg` (`imageio-ffmpeg` fallback), so it does not care, but browsers do – the landing
page therefore uses a real 128 kb/s MP3 re-encode (`site/static/audio/demosong.mp3`).

## Round-11 findings (2026-10) – where does the correction actually act?

Round 11 gave the probe ears at more than one place. `rooms.position_variants()` moves the listening spot
by up to `POSITION_SPREAD_M` = 0.4 m: each room mode (43–145 Hz) gets its own gain from the mode shape's
coupling plus a ±4 dB scatter and its own Q, and each reflection path its own delay (±0.4 m ≈ 1.2 ms) and
level. That is the mechanism that produces a position-specific comb notch – and, at 74 Hz on the mini box,
a 12 dB disagreement that a full-range EQ cannot resolve.

1. **A judge that never fires is not a judge.** The first position model varied only the modal gains; the
   three "positions" then agreed to within a few dB everywhere, the > 6 dB spread gate never opened and
   `null_boost_db` was structurally 0.0. Real rooms differ by 10–15 dB per mode across 40 cm, so the
   scatter and the reflection-delay shift were added; the mini box now shows 6.7 dB total spread (12.2 dB
   at 43 Hz) and the gate has teeth.
2. **What the gate measures matters.** `null_boost_db` is the largest boost of a *narrow* band (Q ≥ 3)
   inside a region where the positions disagree by more than 6 dB – checked per band, not on the summed
   correction, because a broad bass shelf legitimately crosses such a region. It is only asserted with
   `--positions > 1`: with one position the app cannot know that a dip is local.
3. **A unit bug that looked like a finding.** The first version fed `Grid.response()` (already in dB)
   through `20 log10 |x|` and reported 3.8–7.0 dB of null-filling – a metric artefact, not a defect.
   With the fix: 1.58 dB (SMART), 1.96 dB (HARMAN), 0.63 dB (laptop), gate 2.5 dB. Lesson repeated from
   finding F2: check what the unit of a mirror function is before believing a scary number.
4. **Is one measurement enough?** `--positions 1 --truth-positions 3` fits from a single spot and judges
   three: mini box SMART 0.63 dB RMS / p95 1.38 dB, HARMAN 0.68 / 1.41. Fitting from three positions:
   0.50 / 0.84 and 0.53 / 1.02. Averaging buys ~20 % of the residual and a third of the tail – modest, and
   exactly why the app *recommends* three positions indoors instead of demanding them.
5. **The 1-octave indoor rule is not free.** Against a three-seat truth it costs 0.13 dB of RMS
   (0.37 → 0.50 dB on the mini box) and buys a third of the p95 tail plus the null gate. Kept, documented.

`Intro1.mp4`, default run: 6/6 within every threshold (indoor mini box 2.03 → 0.85 dB).
`--positions 3`: 6/6, worst `null_boost_db` 1.96 dB, worst peak −1.05 dBTP (party box, was −1.28 dBTP
with a sample-peak meter).

Note on the second clip: the full 174 s `demosong.mp3` is OOM-killed in the coding sandbox (3.9 GB, the
probe keeps several 48 kHz float copies per case); a 60 s excerpt of the same file passes 6/6 (mini box
2.58 → 0.69 dB, loud master 6.2 dB crest) – the crest and the tonal numbers match the round-9 table, so
nothing about round 11 depends on the full length.

