"""Reproducible measurements behind `docs/AUDIO_ROADMAP.md`.

The roadmap makes five claims about where the next gain is *not* and where it is. Each one is a number
produced here, on the app's own Python port (`avzprobe`), so a future change can be checked against them
instead of against memory. Nothing in this file is used by the app - it is an experiment bench.

    python3 tools/probe/pipeline_findings.py [--music <file>]

Needs `numpy`; `--music` additionally needs `ffmpeg` or `imageio-ffmpeg` (like `live_probe.py`).

The five findings (2026-10, `Demosong.mp3` / `Intro1.mp4`, port at commit of round 9):

 1. **Harmonic distortion is free.** The ESS deconvolution already contains the harmonic responses, at
    `T * ln(n) / ln(f2/f1)` *before* the linear impulse response (Farina 2000). They are measurable, they
    scale with the drive, and the app's 4 ms pre-window excludes them - so the magnitude measurement is
    not biased by speaker distortion, and THD can be added without one extra second of measurement.
 2. **True peak, not sample peak.** After the limiter the sample peak is -1.6 dBFS but the 4x-oversampled
    true peak is -1.2 dBTP: the acceptance test "peak <= -0.1 dBFS" checks the wrong quantity.
 3. **The IR window is not the lever.** 0.08 s ... 0.80 s changes the measured curve by < 0.05 dB rms;
    the reverberation *level* is what costs accuracy (0.35 -> 2.35 dB rms as RT60 goes 0 -> 1.2 s), and
    RT60 itself is recoverable from the same impulse response.
 4. **More bands do nothing.** 12 and 16 bands reproduce the 10-band result; the fitter saturates at
    8-10. The residual error lives in the *plan*, not in the filter count.
 5. **More sweeps do nothing.** 0.43 dB rms measurement error at 18 dB SNR - identical to 60 dB. The
    sweep's processing gain already covers stationary noise; repetition would only help against impulsive
    disturbances, which the existing `NOISE_UNSTEADY` check reports instead.

Added in round 13:

14. **The prediction can be checked.** A second measurement through the actual EQ reproduces the plan's
    promised improvement to within a few tenths of a dB on every scenario model, and the same comparison
    calls a chain that never applied the EQ what it is (`NO_CHANGE`) - the verification pass is a
    measurement, not a reassurance.

Added in round 14:

15. **Convolution would buy a fifth of a decibel.** Realising the same bounded plan exactly (an ideal
    minimum-phase inverse instead of ten PEQ bands) leaves 0.02-0.75 dB of target error where the PEQ
    version leaves 0.08-0.98 dB - an advantage of 0.08-0.23 dB, even with a deliberately narrow resonance
    added to the response. What is left is the *plan*, not the filter: the same experiment shows the exact
    inverse cannot do better than ~0.7 dB either, because the correction is range-limited and boost-capped
    by design. W9 (convolution mode, which would need a screen-capture permission) is therefore closed by
    measurement rather than by opinion.
16. **A target that follows the room is a taste, not an improvement.** W7 proposed tilting the target by up
    to half of the measured trend, capped at 1 dB/octave and never upward. Implemented and measured
    (`live_probe.py --adaptive-target`), it moves the result *away* from the fixed reference target on every
    model that gets a tilt (mini box 0.55 -> 0.84 dB, party box 0.94 -> 1.38 dB, laptop 0.08 -> 0.56 dB) and
    turns the party box's bass *cut* (-0.47 dB) into a boost (+0.46 dB) - a tilt renormalised to 0 dB in
    300 Hz - 3 kHz always buys bass at the midrange's expense. A bright speaker gets no tilt at all (the
    guard works: the phone speaker measures a rising trend and stays unchanged). Not adopted; the fixed
    targets stay, and following the room would have to be an explicit user choice, not an automatic one.
"""

from __future__ import annotations

import argparse
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from avzprobe import audio, dsp, eqfit, rooms, verify  # noqa: E402

FS = audio.SAMPLE_RATE
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def _rel(grid: np.ndarray, db: np.ndarray, lo: float = 100.0, hi: float = 16000.0) -> np.ndarray:
    mask = (grid >= lo) & (grid <= hi)
    return db - dsp.band_mean(grid[mask], db[mask], lo, hi)


def _third_octave(grid: np.ndarray, db: np.ndarray, points_per_octave: int = 48) -> np.ndarray:
    """1/3-octave power mean on the uniform log grid - the width the app fits to."""
    p = 10.0 ** (db / 10.0)
    half = max(1, int(round((1.0 / 3.0) * points_per_octave / 2)))
    kernel = np.ones(2 * half + 1) / (2 * half + 1)
    return 10.0 * np.log10(np.convolve(np.pad(p, half, mode="edge"), kernel, mode="valid"))


# --------------------------------------------------------------- 1. ESS distortion --


def exp_ess_distortion() -> dict:
    """Farina: the n-th harmonic of an exponential sweep lands at T*ln(n)/ln(f2/f1) before the linear IR."""
    print("\n=== 1. harmonic distortion from the existing sweep deconvolution")
    stim = dsp.stimulus(FS)
    plan = stim.plan
    x = stim.signal
    ir = rooms.impulse_response(rooms.SCENARIOS["bt-minibox"], 8192, FS)
    sweep_s = plan.sweep_length / FS
    ratio = math.log(plan.sweep_end_hz / plan.sweep_start_hz)

    def report(label: str, nonlinear) -> dict:
        dec = dsp.deconvolve_sweep(rooms.convolve(nonlinear(x), ir), stim)
        peak = dsp.locate_ir_peak(dec, int(0.8 * FS), FS)
        lin = abs(dec[peak])
        parts, levels = [], {}
        for order in (2, 3, 4):
            dt = sweep_s * math.log(order) / ratio
            idx = int(round(peak - dt * FS)) % dec.size
            val = float(np.max(np.abs(dec[max(0, idx - 200): idx + 200])))
            levels[order] = 20.0 * math.log10(max(val, 1e-20) / max(lin, 1e-20))
            parts.append(f"H{order} {levels[order]:+6.1f} dB")
        print(f"    {label:26s} at +{sweep_s * math.log(2) / ratio:.3f} / "
              f"+{sweep_s * math.log(3) / ratio:.3f} / +{sweep_s * math.log(4) / ratio:.3f} s: "
              + "  ".join(parts))
        return levels

    found = {"linear": report("linear (reference floor)", lambda v: v)}
    for drive in (0.02, 0.08, 0.25):
        found[drive] = report(f"soft clip {drive:.2f}",
                              lambda v, d=drive: np.tanh(v * (1 + d * 10)) / math.tanh(1 + d * 10))
    for level in (0.05, 0.20):
        found[level] = report(f"hard clip {level:.2f}", lambda v, a=level: np.clip(v, -a, a) / a * 0.5)
    print("    -> the peaks sit exactly where theory puts them, the clean chain is at the numerical floor")
    print("       (-110 dB), and the 4 ms pre-window of `AnalyzerConfig` cannot see them: the magnitude")
    print("       measurement is distortion-free, and THD is available for free.")
    return found


# ------------------------------------------------------------------ 2. true peak --


def true_peak_db(x: np.ndarray, oversample: int = 4) -> tuple[float, float]:
    """Sample peak and 4x true peak (BS.1770-style oversampling, via FFT zero padding)."""
    n = x.size
    spec = np.fft.rfft(x)
    padded = np.zeros(n * oversample // 2 + 1, dtype=complex)
    padded[: spec.size] = spec
    up = np.fft.irfft(padded, n * oversample) * oversample
    return (20.0 * math.log10(max(float(np.max(np.abs(up))), 1e-12)),
            20.0 * math.log10(max(float(np.max(np.abs(x))), 1e-12)))


def exp_true_peak(music_path: str | None) -> dict:
    print("\n=== 2. sample peak vs. true peak (ITU-R BS.1770, 4x)")
    if music_path is None:
        print("    skipped (no --music)")
        return {}
    import live_probe as probe

    music = probe.load_music(music_path)[: 20 * FS]
    tp, sp = true_peak_db(music)
    print(f"    source                 {sp:+6.2f} dBFS sample   {tp:+6.2f} dBTP   ({tp - sp:+.2f} dB)")
    power = 10.0 ** (audio.stats(music).spectrum_db / 10.0)
    gaps = {}
    for index in (3, 5):  # phone-speaker (DEVICE, limiter) and party-box BASS_BOOST (limiter)
        scenario, cls, env, target, noise = probe.CASES[index]
        res = probe.run_case(scenario, cls, env, target, noise, music, power)
        rendered = probe._apply_gain_db(dsp.filter_signal(music, [dsp.PeqBand(**b) for b in res.bands]),
                                        res.preamp_db)
        lim_tp, lim_sp = true_peak_db(audio.limiter(rendered, FS))
        gaps[scenario] = (lim_tp, lim_tp - lim_sp)
        print(f"    {scenario:13s} {target:10s} preamp {res.preamp_db:+5.1f} dB  "
              f"after limiter: {lim_sp:+6.2f} dBFS sample  {lim_tp:+6.2f} dBTP  ({lim_tp - lim_sp:+.2f} dB)")
    print("    -> the limiter is sample-peak driven; the reconstructed waveform overshoots it by ~0.45 dB.")
    return gaps


# --------------------------------------------------------------- 3. IR window / RT60 --


def _t30(ir: np.ndarray, peak: int) -> float:
    """Schroeder backward integration, T30 from -5 .. -35 dB."""
    energy = ir[peak:].astype(float) ** 2
    decay = np.cumsum(energy[::-1])[::-1]
    decay_db = 10.0 * np.log10(np.maximum(decay / max(decay[0], 1e-30), 1e-20))
    t = np.arange(decay.size) / FS

    def cross(threshold: float) -> float:
        i = int(np.argmax(decay_db <= threshold))
        return float(t[i]) if i else 0.0

    return 2.0 * (cross(-35.0) - cross(-5.0))


def exp_ir_window() -> list:
    print("\n=== 3. IR window length vs. reverberation (synthetic room, 1/3-octave, 100 Hz - 16 kHz)")
    rng = np.random.default_rng(7)
    model = rooms.SCENARIOS["bt-minibox"]
    n = 1 << 15
    ir = rooms.impulse_response(model, n, FS)
    peak = int(np.argmax(np.abs(ir)))
    grid = dsp.log_grid()
    freqs = np.maximum(np.fft.rfftfreq(n, 1.0 / FS), 1e-3)
    truth = _rel(grid, _third_octave(grid, dsp.resample(freqs, model.magnitude_db(freqs, FS), grid)))
    mask = (grid >= 100.0) & (grid <= 16000.0)

    def with_reverb(rt60: float, level: float) -> np.ndarray:
        t = np.arange(ir.size) / FS
        tail = rng.standard_normal(ir.size) * np.exp(-6.9078 * t / max(rt60, 1e-3))
        tail[:peak] = 0.0
        tail /= max(float(np.max(np.abs(tail))), 1e-20)
        return ir / float(np.max(np.abs(ir))) + level * tail

    rows = []
    for rt60, level in ((0.0, 0.0), (0.5, 0.10), (0.8, 0.20), (1.2, 0.35)):
        irx = with_reverb(rt60, level)
        row, errs = [], []
        for post in (0.08, 0.30, 0.80):
            cfg = dsp.AnalyzerConfig(ir_post_seconds=post)
            pw = dsp.windowed_ir_power(irx, peak, FS, cfg)
            measured = _rel(grid, _third_octave(grid, dsp.power_to_db(
                dsp.spectrum_to_grid(pw, FS / (2 * (pw.size - 1)), grid))))
            d = measured[mask] - truth[mask]
            errs.append(float(np.sqrt(np.mean(d * d))))
            row.append(f"{post:.2f}s {errs[-1]:5.2f} dB")
        rows.append({"rt60": rt60, "t30": _t30(irx, peak), "errors": errs})
        print(f"    RT60 {rt60:3.1f} s (level {level:4.2f}), T30 estimate {rows[-1]['t30']:4.2f} s:  "
              + "  ".join(row))
    print("    -> the window length moves the error by < 0.05 dB; the reverberation level moves it from")
    print("       0.35 to 2.35 dB rms. T30 comes out of the same impulse response for free.")
    return rows


# ------------------------------------------- 9/10. speaker health from the same IR (W3/W4) --


def exp_speaker_health() -> dict:
    print("\n=== 9/10. distortion and room decay out of the sweep the app already plays")
    stim = dsp.stimulus(FS)
    plan = stim.plan
    model = rooms.SCENARIOS["bt-minibox"]
    ir = rooms.impulse_response(model, dsp.next_power_of_two(stim.signal.size + 8192))
    out = {"thd": {}, "rt60": []}
    for drive in (0.0, 0.02, 0.05, 0.10):
        driven = rooms.apply_distortion(rooms.stressed(model, drive=drive), stim.signal)
        clean = rooms.convolve(driven, ir)
        noise = np.random.default_rng(11).standard_normal(clean.size) * 1e-4
        rec = (clean + noise) * (0.25 / max(float(np.max(np.abs(clean))), 1e-9))
        dec = dsp.deconvolve_sweep(rec, stim)
        peak = dsp.locate_ir_peak(dec, int(0.8 * FS), FS)
        found = dsp.harmonic_distortion(dec, peak, plan.sweep_length / FS, plan.sweep_start_hz, plan.sweep_end_hz, FS)
        thd = None if found is None else found["thd_pct"]
        out["thd"][drive] = thd
        print(f"    drive {drive:4.2f}: THD {'-' if thd is None else f'{thd:5.2f} %'}  "
              f"harmonics {({k: round(v, 1) for k, v in (found or {'harmonics_db': {}})['harmonics_db'].items()})}")
    for rt60 in (0.0, 0.4, 0.8, 1.2):
        tail = rooms.stressed(model, rt60_s=rt60, reverb_level=0.2)
        irx = rooms.impulse_response(tail, dsp.next_power_of_two(stim.signal.size + 8192))
        rec = rooms.convolve(stim.signal, irx)
        rec = (rec + np.random.default_rng(12).standard_normal(rec.size) * 1e-5) * (
            0.25 / max(float(np.max(np.abs(rec))), 1e-9))
        dec = dsp.deconvolve_sweep(rec, stim)
        peak = dsp.locate_ir_peak(dec, int(0.8 * FS), FS)
        est = dsp.estimate_t30(dec, peak, FS)
        out["rt60"].append((rt60, est))
        print(f"    room T60 {rt60:3.1f} s -> estimate {'unknown' if est is None else f'{est:4.2f} s'}")
    print("    -> both metrics are read from the deconvolution that already exists: the windows sit outside")
    print("       the magnitude window, so the frequency response is untouched (finding 1).")
    return out


# ---------------------------------------------------------------- 4. band saturation --


def exp_band_count(music_path: str | None) -> dict:
    print("\n=== 4. does the fitter want more bands? (bt-minibox / HARMAN / street)")
    if music_path is None:
        print("    skipped (no --music)")
        return {}
    import live_probe as probe

    music = probe.load_music(music_path)[: 20 * FS]
    power = 10.0 ** (audio.stats(music).spectrum_db / 10.0)
    tonal = {}
    for bands in (5, 8, 10, 12, 16):
        res = probe.run_case("bt-minibox", "STANDARD", "INDOOR", "HARMAN", "street",
                             music, power, band_count=bands)
        tonal[bands] = res.tonal_rms_after_db
        print(f"    {bands:2d} bands: tonal {res.tonal_rms_before_db:4.2f} -> "
              f"{res.tonal_rms_after_db:4.2f} dB  (p95 {res.tonal_p95_after_db:4.2f})  "
              f"fit rms {res.fit_rms_db:4.2f} / max {res.fit_max_db:4.2f}  used {len(res.bands)}")
    print("    -> 12 and 16 bands reproduce the 10-band result: the fitter saturates at 8-10 bands.")
    return tonal


# --------------------------------------------------------------- 5. noise robustness --


def exp_noise_robustness() -> dict:
    print("\n=== 5. how much does stationary noise cost? (bt-minibox, 1/3-octave, 100 Hz - 16 kHz)")
    import live_probe as probe

    stim = dsp.stimulus(FS)
    model = rooms.SCENARIOS["bt-minibox"]
    ir = rooms.impulse_response(model, dsp.next_power_of_two(stim.signal.size + 8192), FS)
    clean = rooms.convolve(stim.signal, ir) * 0.25
    ref = float(np.sqrt(np.mean(clean[stim.plan.pink_start: stim.plan.sweep_start] ** 2)))
    grid = dsp.log_grid()
    mask = (grid >= 100.0) & (grid <= 16000.0)
    freqs = np.maximum(np.fft.rfftfreq(1 << 19, 1.0 / FS), 1e-3)
    truth = _rel(grid, _third_octave(grid, dsp.resample(freqs, model.magnitude_db(freqs, FS), grid)))
    mic_f, mic_db = probe.mic_curve()

    errors = {}
    for snr_db in (60.0, 24.0, 18.0):
        rec = clean + np.random.default_rng(3).standard_normal(clean.size) * ref * 10.0 ** (-snr_db / 20.0)
        colour = np.array([dsp.interpolate_log(mic_f, mic_db, f)
                           for f in np.maximum(np.fft.rfftfreq(rec.size, 1.0 / FS), 1e-3)])
        rec = np.fft.irfft(np.fft.rfft(rec) * 10.0 ** (colour / 20.0), rec.size)
        measured = dsp.analyze(rec, stim, mic_compensation_db=lambda f: dsp.interpolate_log(mic_f, mic_db, f))
        d = _rel(grid, _third_octave(grid, measured.smoothed_db))[mask] - truth[mask]
        errors[snr_db] = float(np.sqrt(np.mean(d * d)))
        print(f"    SNR {snr_db:4.1f} dB: rms error {errors[snr_db]:4.2f} dB  "
              f"max {float(np.max(np.abs(d))):4.2f} dB")
    print("    -> 18 dB SNR costs nothing measurable: the 5 s sweep has ~42 dB of processing gain.")
    print("       Repeating sweeps would only help against impulsive disturbances.")
    return errors


# -------------------------------------------------------- 14. verification pass --


def exp_verification() -> dict:
    """Measure -> correct -> measure again, and see whether the plan's promise survives the second run."""
    print("\n=== 14. measure -> correct -> re-measure (18 dB SNR, one position)")
    stim = dsp.stimulus(FS)
    rng = np.random.default_rng(23)
    out = {}
    for scenario in ("bt-minibox", "party-box", "phone-speaker"):
        model = rooms.SCENARIOS[scenario]
        ir = rooms.impulse_response(model, dsp.next_power_of_two(stim.signal.size + 8192))

        def capture(signal: np.ndarray) -> "dsp.MeasurementResult":
            clean = rooms.convolve(signal, ir) * 0.25
            ref = float(np.sqrt(np.mean(clean ** 2)))
            rec = clean + rng.standard_normal(clean.size) * ref * 10.0 ** (-18.0 / 20.0)
            return dsp.analyze(rec, stim)

        before = capture(stim.signal)
        plan = eqfit.render(before.frequencies, before.smoothed_db, eqfit.TargetCurve(eqfit.SMART),
                            eqfit.AutoEqSettings(band_count=10, max_boost_db=6.0, high_limit_hz=16000.0),
                            roll_off_db=before.third_octave_db)
        corrected = verify.compare(before, capture(dsp.filter_signal(stim.signal, plan.bands, FS)), plan)
        # The control: the same two measurements with the EQ never applied (wrong route, engine off).
        unapplied = verify.compare(before, capture(stim.signal), plan)
        out[scenario] = (corrected, unapplied)
        print(f"    {scenario:14s} {len(plan.bands):2d} bands: measured "
              f"{corrected['measured_improvement_db']:+.2f} dB, predicted "
              f"{corrected['predicted_improvement_db']:+.2f} dB -> {corrected['verdict']} "
              f"(err {corrected['prediction_error_db']:.2f} dB, r {corrected['correlation']:.2f})"
              f"  |  EQ off -> {unapplied['verdict']}")
    print("    -> the second run reproduces the first run's promise, and the same comparison refuses to")
    print("       confirm a chain that never applied the correction.")
    return out


# ------------------------------------------- 15. PEQ vs exact inverse (W9) --


def exp_fir_ceiling() -> dict:
    """How much would an exact minimum-phase inverse buy over the ten PEQ bands the app ships?

    Both sides realise *the same plan*: the app's own bounded desired correction (range-limited,
    boost-capped, null-gated). The PEQ side is what the fitter makes of it, the exact side is the limit of
    any filter that can realise a magnitude - so the difference is the price of using ten biquads, and
    nothing else. Judged against the target curve over the full-authority band, level offset removed.
    """
    print("\n=== 15. ten PEQ bands vs an exact inverse (does W9 have anything to buy?)")
    grid = dsp.log_grid()
    target = eqfit.TargetCurve(eqfit.SMART).sample(grid)
    freqs = np.maximum(np.fft.rfftfreq(1 << 19, 1.0 / FS), 1e-3)
    settings = eqfit.AutoEqSettings(band_count=10, max_boost_db=6.0, high_limit_hz=16000.0)

    def measured_of(scenario: str, extra: np.ndarray | None = None) -> np.ndarray:
        model = rooms.SCENARIOS[scenario]
        raw = dsp.resample(freqs, model.magnitude_db(freqs, FS), grid)
        smoothed = _third_octave(grid, raw)
        return _rel(grid, smoothed if extra is None else smoothed + extra)

    cases: dict[str, tuple[str, np.ndarray | None]] = {
        scenario: (scenario, None) for scenario in ("bt-minibox", "party-box", "laptop")
    }
    # Where a filter *could* matter: a high-Q resonance a 10-band fit can only approximate.
    peak = 1200.0
    cases["bt-minibox + narrow 10 dB peak"] = (
        "bt-minibox",
        10.0 * np.exp(-((np.log(grid / peak) / np.log(2.0)) / 0.05) ** 2),
    )
    out: dict[str, dict[str, float]] = {}
    for label, (scenario, extra) in cases.items():
        measured = measured_of(scenario, extra)
        plan = eqfit.render(grid, measured, eqfit.TargetCurve(eqfit.SMART), settings, roll_off_db=measured)
        judge = (grid >= plan.low_limit_hz * 1.41) & (grid <= plan.high_limit_hz)

        def score(curve: np.ndarray) -> float:
            e = (curve - target)[judge]
            e = e - float(np.mean(e))  # a constant offset is volume, not tonal balance
            return float(np.sqrt(np.mean(e * e)))

        before = score(measured)
        peq = score(measured + plan.filter_response_db)
        exact = score(measured + plan.desired_correction_db)
        realisation = float(np.sqrt(np.mean((plan.desired_correction_db - plan.filter_response_db)[judge] ** 2)))
        out[label] = {
            "before_db": before, "peq_db": peq, "exact_db": exact,
            "gap_db": peq - exact, "realisation_db": realisation, "bands": len(plan.bands),
        }
        print(f"    {label:28s} {len(plan.bands):2d} bands: target error {before:5.2f} -> "
              f"{peq:5.2f} dB with PEQ, {exact:5.2f} dB with an exact inverse "
              f"(gain {peq - exact:4.2f} dB, realisation error {realisation:4.2f} dB)")
    print("    -> the exact inverse gains 0.08-0.23 dB; what remains is the plan's own limits (range,")
    print("       boost caps), not the filters. Convolution mode is not worth a screen-capture permission.")
    return out


# --------------------------------------- 16. adaptive target (W7, not adopted) --


def exp_adaptive_target() -> dict:
    """Would a target that follows the room's own tilt get closer to a fixed, honest reference?

    The reference is the fixed target - it must not move, or the comparison grades its own homework. Both
    plans are corrected responses of the same measured curve; the question is which one lands closer to the
    target a listener actually asked for.
    """
    print("\n=== 16. does a target that follows the room's tilt help? (reference = the fixed target)")
    grid = dsp.log_grid()
    reference = eqfit.TargetCurve(eqfit.SMART).sample(grid)
    freqs = np.maximum(np.fft.rfftfreq(1 << 19, 1.0 / FS), 1e-3)
    settings = eqfit.AutoEqSettings(band_count=10, max_boost_db=6.0, high_limit_hz=16000.0)
    out: dict[str, dict[str, float]] = {}
    for scenario in ("bt-minibox", "party-box", "laptop", "phone-speaker"):
        model = rooms.SCENARIOS[scenario]
        measured = _rel(grid, _third_octave(grid, dsp.resample(freqs, model.magnitude_db(freqs, FS), grid)))
        tilt = eqfit.adaptive_tilt_db_per_octave(grid, measured)
        fixed = eqfit.render(grid, measured, eqfit.TargetCurve(eqfit.SMART), settings, roll_off_db=measured)
        tilted_curve = eqfit.TargetCurve(eqfit.SMART, adaptive_tilt_db_per_octave=tilt, adaptive_pivot_hz=300.0)
        tilted = eqfit.render(grid, measured, tilted_curve, settings, roll_off_db=measured)
        judge = (grid >= max(fixed.low_limit_hz * 1.41, 150.0)) & (grid <= fixed.high_limit_hz)

        def to_reference(curve: np.ndarray) -> float:
            e = (curve - reference)[judge]
            e = e - float(np.mean(e))
            return float(np.sqrt(np.mean(e * e)))

        def bass_gain(plan) -> float:
            band = (grid >= 40.0) & (grid <= 160.0)
            mid = dsp.band_mean(grid, plan.filter_response_db, 300.0, 3000.0)
            return float(np.mean(plan.filter_response_db[band]) - mid)

        out[scenario] = {
            "tilt_db_per_oct": tilt,
            "fixed_db": to_reference(measured + fixed.filter_response_db),
            "tilted_db": to_reference(measured + tilted.filter_response_db),
            "bass_fixed_db": bass_gain(fixed),
            "bass_tilted_db": bass_gain(tilted),
        }
        print(f"    {scenario:14s} tilt {tilt:+.2f} dB/oct: distance to the fixed target "
              f"{out[scenario]['fixed_db']:4.2f} -> {out[scenario]['tilted_db']:4.2f} dB, "
              f"bass gain {out[scenario]['bass_fixed_db']:+.2f} -> {out[scenario]['bass_tilted_db']:+.2f} dB")
    print("    -> following the room moves every tilted case away from the fixed target and buys bass with")
    print("       midrange accuracy. A taste, not a correction - so it is not adopted automatically.")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--music", help="real recording for findings 2 and 4 (any ffmpeg format)")
    args = ap.parse_args(argv)

    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, ok, detail))
        print(f"{'ok  ' if ok else 'FAIL'} {name}{' - ' + detail if detail else ''}")

    health = exp_speaker_health()
    check("a linear chain reports no THD at all", health["thd"][0.0] is None, "no number, not 0.0 %")
    check("a driven driver is measured above the 3 % warning line",
          health["thd"][0.05] is not None and health["thd"][0.05] > 3.0,
          f"{health['thd'][0.05]:.2f} % at drive 0.05")
    check("THD grows monotonically with the drive",
          all(health["thd"][a] < health["thd"][b] for a, b in ((0.02, 0.05), (0.05, 0.10))),
          " / ".join(f"{d:g}:{v:.2f} %" for d, v in health["thd"].items() if v is not None))
    rt_known = [(true, est) for true, est in health["rt60"] if true > 0.0]
    check("room decay recovered within 15 %", all(est is not None and abs(est - true) <= 0.15 * true
                                                  for true, est in rt_known),
          ", ".join(f"{true:.1f}->{'unknown' if est is None else f'{est:.2f}'}" for true, est in health["rt60"]))
    # A dry model still rings for a few milliseconds (the driver itself, and the minimum-phase response),
    # so the honest requirement is not "unknown" but "never mistaken for a reverberant room": a short
    # number or none at all. What must not happen is a dry room reported as a hall.
    dry = health["rt60"][0][1]
    check("a dry room is never reported as reverberant", dry is None or dry < 0.2,
          f"{'unknown' if dry is None else f'{dry:.2f} s'}")

    distortion = exp_ess_distortion()
    check("clean chain has no harmonic content", distortion["linear"][3] < -90.0,
          f"H3 {distortion['linear'][3]:.1f} dB")
    # The soft clip at drive 0.02 is ~3 % THD: the level a small speaker reaches at a comfortable volume.
    check("soft clip drive 0.02 reads ~3 % THD", -35.0 < distortion[0.02][3] < -28.0,
          f"H3 {distortion[0.02][3]:.1f} dB")
    check("THD grows monotonically with drive",
          distortion[0.02][3] < distortion[0.08][3] < distortion[0.25][3],
          f"{distortion[0.02][3]:.1f} / {distortion[0.08][3]:.1f} / {distortion[0.25][3]:.1f} dB")
    check("hard clipping is louder than any soft clip",
          distortion[0.05][3] > distortion[0.25][3], f"{distortion[0.05][3]:.1f} dB")

    gaps = exp_true_peak(args.music)
    if gaps:
        check("true peak exceeds the sample peak by 0.3-0.6 dB",
              all(0.3 <= gap <= 0.6 for _, gap in gaps.values()),
              ", ".join(f"{k} {gap:+.2f} dB" for k, (_, gap) in gaps.items()))
        check("after the limiter every case stays below -1 dBTP",
              all(tp < -1.0 for tp, _ in gaps.values()),
              ", ".join(f"{k} {tp:+.2f} dBTP" for k, (tp, _) in gaps.items()))

    rows = exp_ir_window()
    spread = max(max(r["errors"]) - min(r["errors"]) for r in rows)
    check("window length is not the lever (< 0.1 dB across 0.08-0.80 s)", spread < 0.1, f"{spread:.3f} dB")
    check("reverberation is (>= 1.5 dB between dry and RT60 1.2 s)",
          rows[-1]["errors"][1] - rows[0]["errors"][1] >= 1.5,
          f"{rows[0]['errors'][1]:.2f} -> {rows[-1]['errors'][1]:.2f} dB")
    t30_err = max(abs(r["t30"] - r["rt60"]) for r in rows if r["rt60"] > 0.0)
    check("T30 recovered from the impulse response within 0.2 s", t30_err < 0.2, f"worst {t30_err:.2f} s")

    tonal = exp_band_count(args.music)
    if tonal:
        check("more than 11 bands change nothing",
              abs(tonal[16] - tonal[12]) < 0.01 and abs(tonal[12] - tonal[10]) < 0.01,
              f"10/12/16 bands: {tonal[10]:.2f} / {tonal[12]:.2f} / {tonal[16]:.2f} dB")
        check("5 bands really are worse than 10", tonal[5] - tonal[10] > 0.2,
              f"{tonal[5]:.2f} vs {tonal[10]:.2f} dB")

    noise = exp_noise_robustness()
    check("18 dB SNR costs nothing measurable",
          max(noise.values()) - min(noise.values()) < 0.05,
          ", ".join(f"{k:.0f} dB: {v:.2f}" for k, v in noise.items()))

    verification = exp_verification()
    check("every scenario verifies as predicted",
          all(report["verdict"] == verify.AS_PREDICTED for report, _ in verification.values()),
          ", ".join(f"{k}: {r['verdict']}" for k, (r, _) in verification.items()))
    gap = max(abs(r["measured_improvement_db"] - r["predicted_improvement_db"])
              for r, _ in verification.values())
    check("the measured improvement is within 0.5 dB of the predicted one", gap <= 0.5, f"worst {gap:.2f} dB")
    check("the same comparison catches a chain that never applied the EQ",
          all(r["verdict"] != verify.AS_PREDICTED for _, r in verification.values()),
          ", ".join(f"{k}: {r['verdict']}" for k, (_, r) in verification.items()))

    fir = exp_fir_ceiling()
    gap = max(v["gap_db"] for v in fir.values())
    check("an exact inverse buys less than 0.3 dB over ten PEQ bands", gap < 0.3, f"best gain {gap:.2f} dB")
    # Scoped to the two speakers that actually need a correction: the laptop model is almost on target
    # (1.36 dB before), so there is nothing left for either filter to prove there.
    big = ("bt-minibox", "party-box")
    check("the plan, not the filter, is the limit (an exact inverse still leaves >= 0.6 dB there)",
          all(fir[k]["exact_db"] >= 0.6 for k in big),
          ", ".join(f"{k}: {fir[k]['exact_db']:.2f} dB" for k in big))
    check("the ten-band fit realises its plan to < 0.7 dB rms (worst case: the narrow resonance)",
          all(v["realisation_db"] < 0.7 for v in fir.values()),
          f"worst {max(v['realisation_db'] for v in fir.values()):.2f} dB")

    adaptive = exp_adaptive_target()
    tilted = {k: v for k, v in adaptive.items() if abs(v["tilt_db_per_oct"]) > 0.05}
    check("following the room's tilt moves away from the fixed target",
          bool(tilted) and all(v["tilted_db"] > v["fixed_db"] for v in tilted.values()),
          ", ".join(f"{k}: {v['fixed_db']:.2f} -> {v['tilted_db']:.2f} dB" for k, v in tilted.items()))
    check("a bright speaker gets no adaptive tilt at all",
          adaptive["phone-speaker"]["tilt_db_per_oct"] == 0.0,
          f"{adaptive['phone-speaker']['tilt_db_per_oct']:+.2f} dB/oct")
    check("the tilt buys bass at the midrange's expense",
          adaptive["party-box"]["bass_tilted_db"] > adaptive["party-box"]["bass_fixed_db"] + 0.5,
          f"{adaptive['party-box']['bass_fixed_db']:+.2f} -> {adaptive['party-box']['bass_tilted_db']:+.2f} dB")

    bad = [c for c in checks if not c[1]]
    print(f"\n{len(checks) - len(bad)}/{len(checks)} findings hold. See docs/AUDIO_ROADMAP.md.")
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main())
