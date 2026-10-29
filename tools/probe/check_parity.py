"""Self-check of the Python port against the golden values of the Kotlin unit tests.

The live-music probe is only meaningful if it runs *the app's* algorithms. These checks
copy the numeric expectations of the JVM tests (`AutoEqRendererTest`, `PreampPlannerTest`)
and fail loudly if the port drifts. Run: `python3 tools/probe/check_parity.py`.
"""

from __future__ import annotations

import math
import sys
from typing import NamedTuple

import numpy as np

sys.path.insert(0, __file__.rsplit("/", 1)[0])

from avzprobe import audio, dsp, eqfit, loudness, rooms, verify  # noqa: E402

GRID = dsp.log_grid()


def _curve(*bands: dsp.PeqBand) -> np.ndarray:
    """`AutoEqRendererTest.curveOf`: summed band magnitudes, normalised to 300 Hz - 3 kHz."""
    v = dsp.Grid(GRID).response_many(list(bands))
    return v - dsp.band_mean(GRID, v, 300.0, 3000.0)


class _Meas(NamedTuple):
    """The two fields `verify.compare` reads off a `dsp.MeasurementResult`."""

    frequencies: np.ndarray
    smoothed_db: np.ndarray


def check(name: str, ok: bool, detail: str = "") -> bool:
    print(f"{'ok  ' if ok else 'FAIL'} {name}{' - ' + detail if detail else ''}")
    return ok


def main() -> int:
    ok = True

    # --- FrequencyGrid.log: ceil(9.9658 octaves * 48) + 1 = 480 points, last exactly 20 kHz ---
    ok &= check("log grid 48/oct", GRID.size == 480 and GRID[-1] == 20000.0, f"{GRID.size} points, last={GRID[-1]:.1f} Hz")

    # --- A-weighting (PreampPlannerTest.aWeightingMatchesTheStandard) ---
    for f, expect, tol in ((1000.0, 0.0, 0.05), (500.0, -3.2, 0.3), (2500.0, 1.3, 0.3), (6300.0, -0.1, 0.5), (40.0, -34.6, 0.5)):
        got = eqfit.a_weighting_db(f)
        ok &= check(f"A-weighting {f:g} Hz", abs(got - expect) <= tol, f"{got:.2f} vs {expect}")

    # --- excursion guard / range weight endpoints (AutoEqRendererTest) ---
    ok &= check("excursionGuard(lo,lo)=0", abs(eqfit.excursion_guard(50.0, 50.0)) < 1e-12)
    ok &= check("excursionGuard(2lo,lo)=1", abs(eqfit.excursion_guard(100.0, 50.0) - 1.0) < 1e-12)
    ok &= check("rangeWeight inside=1", abs(eqfit.range_weight(1000.0, 50.0, 16000.0) - 1.0) < 1e-12)
    ok &= check("rangeWeight outside=0", abs(eqfit.range_weight(30.0, 50.0, 16000.0)) < 1e-12)
    mid = eqfit.range_weight(50.0 / 2 ** 0.25, 50.0, 16000.0)
    ok &= check("rangeWeight half-octave fade=0.5", abs(mid - 0.5) < 1e-9, f"{mid:.6f}")

    # --- detectLowLimit: isolated dip above the roll-off must not win (lowLimitIgnoresIsolatedDipAboveRollOff) ---
    dip = _curve(dsp.PeqBand(dsp.HIGH_PASS, 55.0, 0.0, 0.707), dsp.PeqBand(dsp.PEAK, 140.0, -9.0, 2.0))
    lo = eqfit.detect_low_limit(GRID, dip)
    ok &= check("detectLowLimit ignores isolated dip", 35.0 <= lo <= 90.0, f"{lo:.1f} Hz")

    # --- detectLowLimit: phone speaker, default scan saturates at the clamp (lowLimitWideScanFindsPhoneSpeakerRollOff) ---
    phone = _curve(dsp.PeqBand(dsp.HIGH_PASS, 700.0, 0.0, 0.707))
    lo_default = eqfit.detect_low_limit(GRID, phone)
    ok &= check("detectLowLimit default clamp", abs(lo_default - 160.0) < 1e-9, f"{lo_default}")
    lo_wide = eqfit.detect_low_limit(GRID, phone, scan_from_hz=1200.0, max_hz=900.0)
    ok &= check("detectLowLimit wide scan", 560.0 <= lo_wide <= 730.0, f"{lo_wide:.1f} Hz")

    # --- PreampPlanner: cut-only correction keeps 0 dB, boosted one pays for the peak ---
    flat_measured = np.zeros(GRID.size)
    plan = eqfit.preamp_plan(GRID, flat_measured, -3.0 * np.ones(GRID.size), peak_db=0.0)
    ok &= check("cut-only preamp is 0 dB", plan.preamp_db == 0.0 and plan.make_up_db == 0.0, f"{plan.preamp_db}")
    peak = 6.0
    p2 = eqfit.preamp_plan(GRID, flat_measured, np.full(GRID.size, 6.0), peak_db=peak)
    ok &= check("boost pays for itself", p2.preamp_db <= -6.0 and p2.peak_db + p2.preamp_db <= 0.0, f"{p2.preamp_db} dB")

    # --- Biquad magnitude: a +6 dB peak reads +6 dB at its centre (BiquadParityTest domain) ---
    mag = dsp.magnitude_db(dsp.coefficients(dsp.PeqBand(dsp.PEAK, 1000.0, 6.0, 1.0)), dsp.phi(1000.0))
    ok &= check("PEAK +6 dB at fc", abs(mag - 6.0) < 0.05, f"{mag:.3f} dB")
    # RBJ 2nd-order Butterworth: -3 dB at the corner (the "-6 dB point" of AutoEqRenderer is read off
    # the *measured* curve, not from this filter).
    hp = dsp.magnitude_db(dsp.coefficients(dsp.PeqBand(dsp.HIGH_PASS, 100.0, 0.0, 0.707)), dsp.phi(100.0))
    ok &= check("HIGH_PASS -3 dB at fc (Q .707)", abs(hp + 3.01) < 0.1, f"{hp:.2f} dB")

    # --- Target curves (targetShapesAreSensible) ---
    smart = eqfit.TargetCurve(eqfit.SMART).sample(GRID)
    harman = eqfit.TargetCurve(eqfit.HARMAN).sample(GRID)
    i40 = int(np.argmin(np.abs(GRID - 40.0)))
    i10k = int(np.argmin(np.abs(GRID - 10000.0)))
    ok &= check("SMART lifts 40 Hz, flat at 10 kHz", smart[i40] > 2.5 and abs(smart[i10k]) < 0.5, f"{smart[i40]:.2f}/{smart[i10k]:.2f}")
    ok &= check("HARMAN above SMART at 40 Hz, below at 10 kHz", harman[i40] > smart[i40] and harman[i10k] < -1.5)
    ok &= check("targets normalise to 0 dB in 300-3000", abs(dsp.band_mean(GRID, smart, 300.0, 3000.0)) < 1e-9)

    # --- Analyzer: a flat system must measure flat (MeasurementAnalyzerTest.shortcut) ---
    stim = dsp.stimulus()
    rec = np.concatenate([stim.signal, np.zeros(4096)]) * 0.25
    res = dsp.analyze(rec, stim)
    dev = float(np.max(np.abs(dsp.band_mean(res.frequencies, res.smoothed_db, 100.0, 8000.0))))
    ok &= check("flat system measures flat", dev < 0.1, f"{dev:.4f} dB, warnings={res.warnings}")

    # --- the harness itself: what the model says must be what the probe plays (round 8) ----------
    model = rooms.SCENARIOS["bt-minibox"]
    n_fft = dsp.next_power_of_two(stim.signal.size + 8192)
    freqs = np.maximum(np.fft.rfftfreq(n_fft, 1.0 / audio.SAMPLE_RATE), 1e-3)
    analytic = model.response(freqs, audio.SAMPLE_RATE)
    mag_err = float(np.max(np.abs(np.abs(rooms.minimum_phase(analytic)) - np.abs(analytic))))
    ok &= check("minimum_phase keeps the magnitude", mag_err < 1e-9, f"{mag_err:.2e}")

    zero_phase = np.fft.irfft(analytic, n_fft)
    played = []
    for name, ir in (("minimum-phase", rooms.impulse_response(model, n_fft)),
                     ("zero-phase", zero_phase)):
        clean = rooms.convolve(stim.signal, ir) * 0.25
        res = dsp.analyze(clean, stim, config=dsp.AnalyzerConfig(smoothing=dsp.THIRD_OCTAVE))
        measured = dsp.resample(res.frequencies, res.smoothed_db, GRID)
        # smooth the reference exactly like the analyzer smooths the measurement (1/3 octave)
        ref_grid = dsp.power_to_db(dsp.spectrum_to_grid(np.abs(analytic) ** 2, freqs[1] - freqs[0], GRID,
                                                        dsp.THIRD_OCTAVE))
        reference = ref_grid
        band = (GRID >= 40.0) & (GRID <= 16000.0)
        diff = measured[band] - reference[band]
        diff -= float(np.mean(diff))
        played.append(float(np.sqrt(np.mean(diff ** 2))))
    ok &= check("playback matches the model", played[0] < 0.3,
                f"{played[0]:.2f} dB rms (zero-phase was {played[1]:.2f} dB - the old harness bug)")

    # --- PeqFitter: the greedy must deliver the plan (round 8 regression) -----------------------
    lobes = [dsp.PeqBand(dsp.PEAK, 125.0, 5.4, 2.7), dsp.PeqBand(dsp.PEAK, 354.0, 3.1, 4.0),
             dsp.PeqBand(dsp.PEAK, 1577.0, -3.2, 2.6), dsp.PeqBand(dsp.PEAK, 14600.0, 3.0, 0.5)]
    biquad = dsp.Grid(GRID)
    broad = 2.5 * np.exp(-((np.log(GRID / 600.0) / np.log(2.0)) / 0.6) ** 2)
    desired = biquad.response_many(lobes) + broad
    fitted = eqfit.PeqFitter(biquad, desired, np.ones(GRID.size),
                             eqfit.FitConstraints(max_bands=10, min_frequency=30.0)).fit()
    resid = np.abs(desired - biquad.response_many(fitted))
    mask = (GRID >= 100.0) & (GRID <= 14000.0)
    ok &= check("fitter realises a broad residual", float(np.max(resid[mask])) < 1.0,
                f"max {float(np.max(resid[mask])):.2f} dB with {len(fitted)} bands")

    # --- AntiClipping: the measured limiter settings -------------------------------------------
    lim = audio.limiter
    import inspect
    defaults = inspect.signature(lim).parameters
    ok &= check("limiter defaults are the measured ones",
                (defaults["threshold_db"].default, defaults["ratio"].default, defaults["attack_ms"].default)
                == (-2.0, 20.0, 0.01),
                f"{defaults['threshold_db'].default} dB / {defaults['ratio'].default}:1 / "
                f"{defaults['attack_ms'].default} ms")
    # a steady over-threshold tone settles a defined amount above the threshold (ratio, not attack)
    t = np.arange(int(0.5 * audio.SAMPLE_RATE)) / audio.SAMPLE_RATE
    over_db = 6.0
    tone = 10 ** ((over_db - 2.0) / 20.0) * np.sin(2 * np.pi * 1000.0 * t)
    out_peak = float(np.max(np.abs(audio.limiter(tone, audio.SAMPLE_RATE)[-2048:])))
    settled_db = 20.0 * math.log10(out_peak)
    # -2 dB threshold, 20:1: a +6 dB over-shoot settles ~1.7 dB below full scale instead of the +4 dBFS
    # it entered with (and, with no look-ahead, the attack only delays it).
    ok &= check("limiter settles a +6 dB over-shoot below full scale",
                -3.0 <= settled_db <= -1.0, f"{settled_db:+.2f} dBFS")

    # `audio.limiter_demand_db` is the probe's limiter-work metric. It is only worth printing if it
    # returns the textbook static curve: 0 dB below the threshold, and (1 - 1/ratio) of the over-shoot
    # above it. The settled gain above is attack-smoothed; this is the instant the statistics are
    # computed from, so test it directly instead of trusting the near-zero values real cases print.
    demand = audio.limiter_demand_db(tone, threshold_db=-2.0, ratio=20.0)
    expect_db = -(20.0 * math.log10(np.max(np.abs(tone))) + 2.0) * (1.0 - 1.0 / 20.0)
    peak_idx = int(np.argmax(np.abs(tone)))  # the statistic is per sample - look at the crest, not at t=0.5 s
    ok &= check("limiter demand follows the static curve",
                abs(float(demand[peak_idx]) - expect_db) < 1e-3,
                f"{float(demand[peak_idx]):.3f} vs {expect_db:.3f} dB")
    quiet = audio.limiter_demand_db(0.1 * tone, threshold_db=-2.0, ratio=20.0)
    ok &= check("limiter demand is 0 dB under the threshold", float(np.max(np.abs(quiet))) == 0.0)

    # --- TruePeak (app: `measurement/dsp/TruePeak`, pinned by `TruePeakTest`) --------------------
    fs = audio.SAMPLE_RATE
    dc = np.full(4096, 0.5)
    ok &= check("true peak of DC is its level", abs(audio.true_peak_db(dc) + 6.02) < 0.01,
                f"{audio.true_peak_db(dc):+.2f} dBTP")
    fs4 = np.cos(2.0 * math.pi * 12000.0 * np.arange(8192) / fs + math.pi / 4.0)
    ok &= check("true peak sees the crest a sample meter misses",
                abs(audio.true_peak_db(fs4)) < 0.02 and abs(audio.sample_peak_db(fs4) + 3.01) < 0.01,
                f"sample {audio.sample_peak_db(fs4):+.2f} dBFS, true {audio.true_peak_db(fs4):+.2f} dBTP")
    # The app cannot afford an FFT per buffer: its 96-tap polyphase filter is the cheap mirror. It
    # under-reads by up to 0.17 dB (the 4x grid) plus its own truncation - the reserved margin is 0.5.
    poly = audio.true_peak_polyphase_db(fs4)
    ok &= check("polyphase filter mirrors the exact true peak", abs(poly + 0.17) < 0.05, f"{poly:+.3f} dBTP")
    # White noise is the worst case (full energy at Nyquist, where every interpolator transitions);
    # programme material is band-limited, and there the two agree closely. Judge what music looks like.
    rng = np.random.default_rng(4)
    noise = rng.standard_normal(40000) * 0.2
    spectrum = np.fft.rfft(noise)
    spectrum[np.fft.rfftfreq(noise.size, 1.0 / fs) > 20000.0] = 0.0
    band_limited = np.fft.irfft(spectrum, noise.size)
    gap = abs(audio.true_peak_db(band_limited) - audio.true_peak_polyphase_db(band_limited))
    ok &= check("polyphase stays within 0.1 dB of the reference on band-limited material",
                gap < 0.1, f"{gap:.3f} dB")

    # --- HarmonicDistortion + RoomDecay (app: `measurement/dsp/…`, pinned by their JVM tests) ------
    # Same synthetic fixture as `HarmonicDistortionTest`: only the windows' contents differ from zero, so
    # the expected levels are exactly 20 log10 of the amplitude ratios.
    fs_h, sweep_s, f1, f2 = 48000, 5.0, 30.0, 18000.0
    # Same fixture as the JVM test, including the peak position: it must sit behind H5 (~60 400 samples
    # earlier), otherwise the lag indices are negative - numpy wraps them, Kotlin would not.
    n_h, peak_h = 1 << 16, 62000

    def _lag(order: float) -> int:
        return int(round(peak_h - sweep_s * math.log(order) / math.log(f2 / f1) * fs_h))

    def _harmonic_ir(*harmonics: tuple[int, float]) -> np.ndarray:
        x = np.zeros(n_h)
        x[peak_h] = 1.0
        for order, amplitude in harmonics:
            x[_lag(order)] = amplitude
        return x

    clean = dsp.harmonic_distortion(_harmonic_ir(), peak_h, sweep_s, f1, f2, fs_h)
    ok &= check("harmonic analysis rejects a linear system", clean is None)
    found = dsp.harmonic_distortion(_harmonic_ir((2, 0.1), (3, 0.01), (5, 0.001)), peak_h, sweep_s, f1, f2, fs_h)
    ok &= check("harmonic levels land at 20 log10 of the ratios",
                found is not None and abs(found["harmonics_db"][2] + 20.0) < 0.3
                and abs(found["harmonics_db"][3] + 40.0) < 0.3 and abs(found["harmonics_db"][5] + 60.0) < 0.3,
                "H2 {:.1f} / H3 {:.1f} / H5 {:.1f} dB".format(*[found["harmonics_db"][o] for o in (2, 3, 5)]) if found else "none")
    ok &= check("THD is sqrt(sum a_n^2)", found is not None and abs(found["thd_pct"] - 10.05) < 0.05,
                f"{found['thd_pct']:.2f} %" if found else "none")
    buzz = dsp.harmonic_distortion(_harmonic_ir((2, 0.02), (3, 0.02), (4, 0.018), (5, 0.017)), peak_h, sweep_s, f1, f2, fs_h)
    clip = dsp.harmonic_distortion(_harmonic_ir((3, 0.05), (5, 0.005)), peak_h, sweep_s, f1, f2, fs_h)
    ok &= check("rattle rule: flat series buzzes, odd-order clipping does not",
                buzz is not None and clip is not None
                and buzz["harmonics_db"][5] - buzz["harmonics_db"][3] > dsp.RATTLE_H5_MINUS_H3_DB
                and clip["harmonics_db"][5] - clip["harmonics_db"][3] <= dsp.RATTLE_H5_MINUS_H3_DB,
                f"buzz {buzz['harmonics_db'][5] - buzz['harmonics_db'][3]:+.1f} dB, clip {clip['harmonics_db'][5] - clip['harmonics_db'][3]:+.1f} dB")

    # `RoomDecayTest`: noise shaped by exp(-6.9078 t / T60) 10 ms after the direct sound.
    rng = np.random.default_rng(0x5EED)
    t = np.arange(3 * fs_h) / fs_h
    for rt60 in (0.4, 0.9, 1.2):
        # The JVM test normalises the tail to 20 % of the direct sound; scaling does not change T60.
        tail = rng.uniform(-1.0, 1.0, t.size) * np.exp(-6.9078 * np.maximum(t - 0.1, 0.0) / rt60)
        ir_decay = tail * 0.2 + np.where(t >= 0.1, 0.0, 0.0)
        ir_decay[t.size // 10] = 1.0
        estimate = dsp.estimate_t30(ir_decay, t.size // 10, fs_h)
        ok &= check(f"room decay {rt60} s", estimate is not None and abs(estimate - rt60) <= rt60 * 0.15,
                    f"{estimate:.2f} s" if estimate else "unknown")
    dry = np.zeros(3 * fs_h)
    dry[t.size // 10] = 1.0
    ok &= check("room decay of a dry response is 'unknown'", dsp.estimate_t30(dry, t.size // 10, fs_h) is None)
    ok &= check("reverb boost trust is 1/(1+T60)",
                abs(eqfit.dsp_room_boost_trust(1.0) - 0.5) < 1e-12
                and abs(eqfit.dsp_room_transition_hz(0.9) - 300.0) < 10.0)

    # --- IntegratedLoudness (app: `measurement/dsp/IntegratedLoudness`, pinned by its JVM test) ----
    # The coefficients the standard prints for 48 kHz. Anything else means the filters were derived with
    # the RBJ cookbook formulas instead of the prewarped prototypes, which is 0.26 dB off on every value.
    expected_shelf = (1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585)
    expected_rlb = (1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
    coeff_err = max(max(abs(a - b) for a, b in zip(loudness.shelf(fs), expected_shelf)),
                    max(abs(a - b) for a, b in zip(loudness.rlb(fs), expected_rlb)))
    ok &= check("K-weighting coefficients are the ones BS.1770 prints", coeff_err < 1e-9, f"{coeff_err:.2e}")

    def _sine(amplitude: float, seconds: float, frequency: float = 1000.0) -> np.ndarray:
        t = np.arange(int(seconds * fs)) / fs
        return amplitude * np.sin(2 * np.pi * frequency * t)

    def _amplitude_for(lufs: float) -> float:
        return loudness.amplitude_for(lufs, fs)

    shape = tuple(loudness.k_weighting_gain_db(f, fs) for f in (1000.0, 3000.0, 10000.0, 40.0, 20.0))
    ok &= check("K-weighting has the documented shape",
                abs(shape[0] - 0.698) < 0.01 and shape[1] > 3.0 and shape[2] > 3.5
                and shape[3] < -5.4 and shape[4] < -13.0,
                f"1 kHz {shape[0]:+.2f} / 3 kHz {shape[1]:+.2f} / 10 kHz {shape[2]:+.2f} / "
                f"40 Hz {shape[3]:+.1f} / 20 Hz {shape[4]:+.1f} dB")

    full = loudness.integrated_lufs(_sine(1.0, 5.0), fs)
    ok &= check("a full-scale sine reads -3.01 LUFS", full is not None and abs(full + 3.01) < 0.05, f"{full:.2f}")
    for expect in (-23.0, -33.0):
        got = loudness.integrated_lufs(_sine(_amplitude_for(expect), 5.0), fs)
        ok &= check(f"a sine at {expect:.0f} LUFS reads {expect:.0f}", got is not None and abs(got - expect) < 0.05,
                    f"{got:.2f}")

    # Absolute gate: EBU 3341 case 3 - quiet ends at -72 LUFS (below the gate) around loud -36 LUFS.
    # Same fixture as the JVM test, 10 / 60 / 10 s: the middle must be long compared with a gating block, or
    # the straddling blocks (real programme, not silence) drag the answer up (2/8/2 gives -36.16).
    gap = np.concatenate([_sine(_amplitude_for(-72.0), 10.0), _sine(_amplitude_for(-36.0), 60.0),
                          _sine(_amplitude_for(-72.0), 10.0)])
    gated = loudness.integrated_lufs(gap, fs)
    ok &= check("the absolute gate drops silent passages", gated is not None and abs(gated + 36.0) < 0.1,
                f"{gated:.2f} LUFS")
    # Relative gate: 6 s at -23 then 6 s at -40. The quiet part is 17 LU down, so the relative gate (10 LU
    # under the gated mean) must drop it; without it the same material reads ~-24.3 LUFS.
    mixed = np.concatenate([_sine(_amplitude_for(-23.0), 6.0), _sine(_amplitude_for(-40.0), 6.0)])
    relative = loudness.integrated_lufs(mixed, fs)
    naive = 10.0 * math.log10(0.5 * (10.0 ** (-23.0 / 10.0) + 10.0 ** (-40.0 / 10.0)))
    ok &= check("the relative gate ignores quiet passages",
                relative is not None and abs(relative + 23.0) < 0.2 and relative > naive + 0.5,
                f"{relative:.2f} LUFS (raw average {naive:.2f})")
    ok &= check("too short is no measurement", loudness.integrated_lufs(_sine(0.5, 0.2), fs) is None)
    quiet_louder = loudness.loudness_difference_lu(_sine(_amplitude_for(-23.0), 3.0),
                                                   _sine(_amplitude_for(-23.0) * 2.0, 3.0), fs)
    ok &= check("+6 dB is +6 LU",
                quiet_louder is not None and abs(quiet_louder - 6.02) < 0.05,
                f"{quiet_louder:+.2f} LU" if quiet_louder is not None else "none")
    n441 = int(5.0 * 44100)
    at441 = loudness.integrated_lufs(
        loudness.amplitude_for(-23.0, fs) * np.sin(2.0 * np.pi * 1000.0 * np.arange(n441) / 44100.0), 44100.0)
    ok &= check("44.1 kHz reads the same loudness",
                at441 is not None and abs(at441 + 23.0) < 0.1, f"{at441:.2f} LUFS")

    # The pair the app shows next to the loudness: sample peak vs true peak on the 12 kHz / 45 degrees tone
    # of `theResultCarriesTheTruePeakNextToTheLoudness` (amplitude 0.5 = -6.02 dBTP, samples only -9.03).
    crest = 0.5 * np.cos(2.0 * math.pi * 12000.0 * np.arange(2 * fs) / fs + math.pi / 4.0)
    tp, sp = loudness.true_peak_dbtp(crest), audio.sample_peak_db(crest)
    ok &= check("loudness analysis carries a true peak above the sample peak",
                -6.3 < tp < -5.9 and sp < -8.5 and tp - sp > 2.5,
                f"true {tp:+.2f} dBTP, sample {sp:+.2f} dBFS, gap {tp - sp:.2f} dB")

    # --- Verification (app: `measurement/Verification`, pinned by `VerificationTest`) --------------
    a_series = np.array([1.0, 2.0, 3.0, 4.0])
    ok &= check("correlation is a Pearson coefficient",
                abs(verify.correlation(a_series, a_series) - 1.0) < 1e-9
                and abs(verify.correlation(a_series, -a_series) + 1.0) < 1e-9
                and verify.correlation(a_series, np.full(4, 1.0)) == 0.0
                and abs(verify.correlation(a_series, a_series[::-1])) > 0.99)

    def _measure(curve: np.ndarray):
        """`VerificationTest.measurement`: a curve normalised in 300 Hz - 3 kHz, like the analyzer."""
        db = np.asarray(curve, dtype=float)
        db = db - dsp.band_mean(GRID, db, 300.0, 3000.0)
        return _Meas(frequencies=GRID, smoothed_db=db)

    def _bump(centre: float, gain_db: float, width_oct: float) -> np.ndarray:
        x = np.log(GRID / centre) / np.log(2.0) / width_oct
        return gain_db * np.exp(-x * x)

    def _speaker() -> np.ndarray:
        # Same small box as the JVM test: boomy at 80 Hz, shouty at 1.6 kHz, rolled off above 12 kHz.
        return _bump(80.0, 8.0, 0.5) + _bump(1600.0, 5.0, 0.6) + _bump(9000.0, -8.0, 0.8) \
            - 0.5 * np.log10(GRID / 1000.0)

    def _plan(before) -> eqfit.AutoEqResult:
        return eqfit.render(GRID, before.smoothed_db, eqfit.TargetCurve(eqfit.SMART),
                            eqfit.AutoEqSettings(band_count=10, max_boost_db=6.0, high_limit_hz=16000.0),
                            roll_off_db=before.smoothed_db)

    def _shifted(before, response: np.ndarray, scale: float = 1.0):
        return _measure(before.smoothed_db + scale * response)

    before = _measure(_speaker())
    plan = _plan(before)
    applied = verify.compare(before, _shifted(before, plan.filter_response_db), plan)
    ok &= check("a chain that does what the plan said verifies as predicted",
                applied["verdict"] == verify.AS_PREDICTED and applied["prediction_error_db"] < 1.5
                and applied["correlation"] > 0.9 and applied["predicted_improvement_db"] > 0.5,
                f"{applied['verdict']}: err {applied['prediction_error_db']:.2f} dB, "
                f"r {applied['correlation']:.2f}, +{applied['predicted_improvement_db']:.2f} dB")

    # The classic field failure: the user changed the volume by 4 dB between the two runs.
    louder = _measure(_shifted(before, plan.filter_response_db).smoothed_db + 4.0)
    volumed = verify.compare(before, louder, plan)
    ok &= check("a level change between the runs does not matter",
                volumed["verdict"] == verify.AS_PREDICTED, volumed["verdict"])

    unapplied = verify.compare(before, _measure(_speaker()), plan)
    ok &= check("an unapplied correction is not verified as predicted",
                unapplied["verdict"] != verify.AS_PREDICTED and unapplied["prediction_error_db"] > 1.5,
                f"{unapplied['verdict']}, err {unapplied['prediction_error_db']:.2f} dB")

    half = verify.compare(before, _shifted(before, plan.filter_response_db, 0.45), plan)
    ok &= check("a half-applied correction is weaker than predicted",
                half["verdict"] == verify.WEAKER_THAN_PREDICTED and half["correlation"] > 0.5,
                f"{half['verdict']}, r {half['correlation']:.2f}")

    unrelated_shape = _bump(300.0, -12.0, 0.7) - _bump(4000.0, 6.0, 0.5)
    unrelated = verify.compare(before, _shifted(before, unrelated_shape), plan)
    ok &= check("an unrelated change is unexpected", unrelated["verdict"] == verify.UNEXPECTED,
                unrelated["verdict"])

    flat = _measure(np.zeros(GRID.size))
    flat_plan = _plan(flat)
    trivial = verify.compare(flat, _measure(np.zeros(GRID.size)), flat_plan)
    ok &= check("a flat plan verifies trivially", trivial["verdict"] == verify.AS_PREDICTED, trivial["verdict"])

    print("\nparity:", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
