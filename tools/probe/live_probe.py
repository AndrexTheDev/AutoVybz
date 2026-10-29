"""Live-music probe: does an AutoVybz calibration make *real recordings* sound right?

The app is normally judged by synthetic criteria (RMS error on a grid, unit tests). This
tool closes the last gap: it plays a real recording through a modelled speaker + room,
lets the app measure and calibrate it exactly as the wizard does, then judges the
result on the recording itself - tonally (energy-weighted, i.e. only where music
actually has content), in level (perceived loudness), in headroom (real peaks, not a
sine) and in protection (no boost below the speaker's physical limit).

Usage:
    python3 tools/probe/live_probe.py --music branding/intro/Intro1.mp4
    python3 tools/probe/live_probe.py --music <file> --json out.json --markdown out.md

Exit code 0 = every case met every threshold ("perfect"), 1 = at least one did not.
Requires numpy (+ ffmpeg, or the `imageio-ffmpeg` wheel) - not part of the CI gate.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from dataclasses import asdict, dataclass, field

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from avzprobe import audio, dsp, eqfit, loudness, rooms, verify  # noqa: E402

# ------------------------------------------------------------------ thresholds --
THRESHOLDS = {
    # 1) The fitter must deliver the plan it was given (filters vs. desired correction).
    "fit_rms_db": 0.75,
    "fit_max_db": 2.0,
    # 2) The result must be good *and* better than the uncorrected speaker on this music.
    "good_enough_db": 1.0,
    "min_improvement_db": 0.25,
    "tonal_p95_db": 3.0,       # the worst 5 % of the music's energy (fidelity-aware band)
    # 3) Safety.
    "loudness_delta_db": 1.5,  # BS.1770 integrated loudness may not drift more than this (limiter classes)
    "loudness_proxy_gap_db": 1.0,  # ... and the A-weighted spectrum proxy must agree with it
    # 7) The verification pass (W6): a second measurement through the active EQ must reproduce the plan.
    "verification_gap_db": 0.5,
    "peak_dbfs": -0.1,         # real programme peaks stay below full scale (after the limiter)
    "clipped_samples": 0,
    "protect_boost_db": 1.0,   # no boost below half the detected bass limit (the app's own boundary)
    "above_range_db": 3.0,     # no large boost in the untrusted region above the correction range
    "bass_regression_db": 0.5, # below the full-authority edge the error may not grow
    # 4) The limiter is a safety net, not a compressor: it may not clamp a large share of the programme.
    "limiter_mean_gr_db": 2.0,
    "limiter_stress_pct": 5.0,
    # 5) Peaks are judged where a DAC sees them: the reconstructed waveform, not the sample grid.
    #    A -1.6 dBFS sample peak was a -1.2 dBTP true peak on the reference material (finding 2).
    "peak_dbtp": -1.0,
    # 6) A correction may not chase a position-dependent null (only meaningful with --positions > 1).
    "null_boost_db": 2.5,
}


@dataclass
class CaseResult:
    scenario: str
    speaker_class: str
    environment: str
    target: str
    noise: str
    measured_snr_db: float
    warnings: list[str]
    bands: list[dict]
    preamp_db: float
    low_limit_hz: float
    high_limit_hz: float
    high_pass_hz: float | None
    rms_before_db: float
    rms_after_db: float
    tonal_rms_before_db: float
    tonal_rms_after_db: float
    full_band_before_db: float
    full_band_after_db: float
    tonal_p95_after_db: float
    loudness_delta_db: float
    loudness_proxy_delta_db: float
    verification: dict | None
    integrated_before_lufs: float | None
    integrated_after_lufs: float | None
    peak_dbfs: float
    # True peak of the same rendered output (4x oversampled): what the DAC and every lossy codec see.
    peak_dbtp: float
    clipped_samples: int
    max_boost_below_limit_db: float
    max_change_above_limit_db: float
    fit_rms_db: float
    fit_max_db: float
    raw_peak_dbfs: float
    limiter_armed: bool
    bass_regression_db: float
    # How hard the limiter has to work to hold the peaks: a static over-shoot only becomes audible when
    # the limiter has to clamp a large share of the programme (pumping, pumping-like level dips).
    limiter_mean_gr_db: float
    limiter_stress_pct: float   # share of the programme needing more than 6 dB of gain reduction
    # How many positions the correction was fitted to, how far they disagreed at the worst point, and
    # the largest boost inside a region where they disagree by more than NULL_SPREAD_DB.
    positions: int
    positions_used: int
    thd_pct: float | None
    room_rt60_s: float | None
    position_spread_db: float
    null_boost_db: float
    # Deepest single sample of gain reduction: the mean is near 0 whenever the peaks only just cross the
    # threshold (a safety net that works *rarely*), so the mean alone reads like "never engaged".
    limiter_max_gr_db: float
    failures: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.failures


# ---------------------------------------------------------------------- helpers --

def load_music(path: str, min_seconds: float = 12.0) -> np.ndarray:
    """Decode and loop a real recording to at least [min_seconds] with an equal-power crossfade."""
    x = audio.decode(path)
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    if peak > 0:
        x = x * (10 ** (-1.0 / 20.0) / peak)  # -1 dBFS, like a normal master
    if x.size / audio.SAMPLE_RATE >= min_seconds:
        return x
    fade = int(0.05 * audio.SAMPLE_RATE)
    y = x.copy()
    while y.size / audio.SAMPLE_RATE < min_seconds:
        ramp = np.linspace(0.0, 1.0, fade)
        tail = y[-fade:] * np.cos(ramp * math.pi / 2)
        head = x[:fade] * np.sin(ramp * math.pi / 2)
        y = np.concatenate([y[:-fade], tail + head, x[fade:]])
    return y


def mic_curve(profile: str = "default", source: str = "UNPROCESSED"):
    """Compensation curve the app would use for this phone mic (from mic_profiles.json)."""
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "..", "app", "src", "main", "assets", "autovybz", "mic_profiles.json")
    with open(path, encoding="utf-8") as fh:
        db = json.load(fh)
    name = db["default"] if profile == "default" else profile
    arch = db["archetypes"][name]
    pts = arch["curves"][source]
    return np.array([p[0] for p in pts]), np.array([p[1] for p in pts])


def _noise_signal(length: int, snr_db: float, lf_rumble_db: float, seed: int = 7) -> np.ndarray:
    rng = np.random.default_rng(seed + length)
    white = rng.standard_normal(length)
    # Room rumble: 2nd-order low pass at 120 Hz (traffic, ventilation, wind)
    rumble = rooms._lowpass_h(120.0, np.fft.rfftfreq(length, 1.0 / audio.SAMPLE_RATE), audio.SAMPLE_RATE)
    rumble = np.fft.irfft(np.fft.rfft(white) * rumble, length)
    return white, rumble


def _band_mean(values: np.ndarray, lo: float, hi: float, freqs: np.ndarray | None = None) -> float:
    """Mean of [values] over the frequencies in [lo, hi] - [freqs] must match [values]."""
    from avzprobe.dsp import band_mean
    return band_mean(audit_freqs(values.size) if freqs is None else freqs, values, lo, hi)


def _smooth_to_grid(values_db: np.ndarray, freqs: np.ndarray, grid: np.ndarray, width_fn) -> np.ndarray:
    power = 10.0 ** (values_db / 10.0)
    bin_hz = float(freqs[1] - freqs[0])
    return dsp.power_to_db(dsp.spectrum_to_grid(power, bin_hz, grid, width_fn=width_fn))


# `AutoEqRenderer.rangeWeight` fades over 0.5 octave; expressed as a frequency factor for the judge.
LOW_FADE_OCTAVES_SCALE = 2.0 ** 0.5

# Gain reduction beyond which the limiter stops being a safety net and starts shaping the sound.
LIMITER_STRESS_GR_DB = 6.0

# How much two listening positions may disagree (dB) before a dip is "the spot, not the speaker".
# 6 dB is the point where a dip is deep enough that no reasonable boost can fill it without taking
# excursion from the whole bass range - the literature's rule of thumb for "do not EQ a null".
NULL_SPREAD_DB = 6.0

# A boost band at least this narrow is "dip filling" by design (Q 3 = 0.48 octave). Broad shelves are
# excluded from the null metric on purpose: they legitimately cross regions where positions disagree.
NARROW_Q = 3.0


def audit_freqs(size: int) -> np.ndarray:
    return np.maximum(np.fft.rfftfreq(1 << 19, 1 / audio.SAMPLE_RATE), 1e-3)[:size]


def _judge_mask(grid: np.ndarray, correction) -> np.ndarray:
    return (grid >= correction.low_limit_hz) & (grid <= correction.high_limit_hz)


def _spread_between(curves: list[np.ndarray]) -> np.ndarray:
    """Per grid point: how far the positions disagree (max - min, dB). The "is this a null?" measure."""
    stack = np.stack(curves)
    return np.max(stack, axis=0) - np.min(stack, axis=0)


# Outlier rule, mirrored from `SpatialAverager` (see its KDoc for the reasoning).
OUTLIER_FACTOR = 3.0
OUTLIER_FLOOR_DB = 1.5


def _level_offset(m) -> float:
    return dsp.band_mean(m.frequencies, m.smoothed_db, 300.0, 3000.0)


def _reject_outliers(measurements: list) -> tuple[list[int], list[float]]:
    """`SpatialAverager.analyse`: drop positions that are far outside the spread of the others.

    The reference is the *median* curve: against the mean, one very different position pulls the
    reference towards itself and every position ends up looking equally wrong.
    """
    if len(measurements) < 3:
        return list(range(len(measurements))), [0.0] * len(measurements)
    stack = np.vstack([m.smoothed_db - _level_offset(m) for m in measurements])
    reference = np.median(stack, axis=0)
    freq = measurements[0].frequencies
    mask = (freq >= 100.0) & (freq <= 10000.0)
    devs = [float(np.sqrt(np.mean(((row - reference)[mask]) ** 2))) for row in stack]
    ordered = sorted(devs)
    limit = max(OUTLIER_FACTOR * ordered[len(ordered) // 2], OUTLIER_FLOOR_DB)
    candidates = sorted([i for i, d in enumerate(devs) if d > limit], key=lambda i: -devs[i])
    candidates = candidates[:max(0, len(devs) - 2)]
    return [i for i in range(len(measurements)) if i not in candidates], devs


def _spatial_average(measurements: list):
    """`SpatialAverager.average`: power mean per grid point, re-normalised to 0 dB at 300 Hz-3 kHz."""
    if len(measurements) == 1:
        return measurements[0]
    first = measurements[0]
    n = first.frequencies.size

    def power_mean(pick) -> np.ndarray:
        p = np.mean([10.0 ** (pick(m) / 10.0) for m in measurements], axis=0)
        return dsp.power_to_db(p)

    smoothed = power_mean(lambda m: m.smoothed_db)
    norm = dsp.band_mean(first.frequencies, smoothed, 300.0, 3000.0)
    def normed(a: np.ndarray) -> np.ndarray:
        return a - norm

    snr = np.minimum.reduce([m.snr_db for m in measurements])
    return dsp.MeasurementResult(
        sample_rate=first.sample_rate,
        frequencies=first.frequencies.copy(),
        raw_db=normed(power_mean(lambda m: m.raw_db)),
        smoothed_db=normed(smoothed),
        third_octave_db=normed(power_mean(lambda m: m.third_octave_db)),
        sweep_smoothed_db=normed(power_mean(lambda m: m.sweep_smoothed_db)),
        pink_smoothed_db=normed(power_mean(lambda m: m.pink_smoothed_db)),
        snr_db=snr,
        latency_ms=float(np.mean([m.latency_ms for m in measurements])),
        input_peak=float(np.max([m.input_peak for m in measurements])),
        median_snr_db=float(np.median(np.sort(snr)[snr.size // 2])),
        warnings=sorted({w for m in measurements for w in m.warnings}),
        normalization_db=float(np.mean([m.normalization_db for m in measurements]) + norm),
    )


def _apply_gain_db(x: np.ndarray, db: float) -> np.ndarray:
    return x * (10.0 ** (db / 20.0))


# ------------------------------------------------------------------------ probe --

def run_case(
    scenario: str,
    speaker_class: str,
    environment: str,
    target: str,
    noise: str,
    music: np.ndarray,
    music_power: np.ndarray,
    *,
    band_count: int = 10,
    mic_error_db: float = 0.0,
    positions: int = 1,
    truth_positions: int = 0,
    rt60_s: float = 0.0,
    distortion_drive: float = 0.0,
    reverb_gate: bool = True,
    verify_run: bool = False,
) -> CaseResult:
    model = rooms.SCENARIOS[scenario]
    if rt60_s > 0.0 or distortion_drive > 0.0:
        model = rooms.stressed(model, rt60_s=rt60_s, drive=distortion_drive)
    noise_cfg = rooms.NOISE_PROFILES[noise]
    stim = dsp.stimulus()
    fs = audio.SAMPLE_RATE

    # The room's own modes exist at every position; outdoors there are none (free field).
    # The truth is *where the listener sits*, which is not necessarily where the phone measured: with
    # `truth_positions > positions` a one-position calibration is judged against what three seats hear.
    truth_count = max(positions, truth_positions) if truth_positions > 0 else positions
    truth_variants = rooms.position_variants(model, truth_count, room=(environment == "INDOOR"))
    # A single measurement is taken at the nominal spot - the same one the truth calls position 0.
    variants = truth_variants[:1] if positions <= 1 else rooms.position_variants(
        model, positions, room=(environment == "INDOOR"))
    mic_f, mic_db = mic_curve()

    # --- 2. the app: analyze exactly like the wizard --------------------------------------
    env_tuning = eqfit.environment_tuning(environment)
    cls = eqfit.SPEAKER_CLASS[speaker_class]
    smoothing = eqfit.smoothing_for(dsp.DYNAMIC, env_tuning, cls)
    cfg = dsp.AnalyzerConfig(smoothing=smoothing, sweep_weight=env_tuning.sweep_weight)
    # A wrong mic profile is never a constant offset - a constant would cancel in the 300 Hz-3 kHz
    # normalisation and hide itself. It is a smooth, frequency-dependent error (the classic shape: a
    # sensitivity bump in the mids plus a treble tilt).
    def mic_correction(f: float) -> float:
        bump = math.exp(-((math.log2(max(f, 1.0) / 1800.0) / 1.4) ** 2))
        tilt = math.log2(max(f, 1.0) / 1000.0) / 3.0
        return dsp.interpolate_log(mic_f, mic_db, f) + mic_error_db * (bump - 0.3 * tilt)

    # --- 1. the "device": one real sweep per position ---------------------------------------
    def capture(clean: np.ndarray, index: int):
        """The microphone side of a measurement: noise, the mic's own response, the analyzer."""
        # Independent noise per position: that is what makes a position repeat meaningful.
        white, rumble = _noise_signal(clean.size, noise_cfg["snr_db"], noise_cfg["lf_rumble_db"], seed=7 + index)
        ref_rms = float(np.sqrt(np.mean(clean[stim.plan.pink_start: stim.plan.sweep_start] ** 2)))
        rec = clean + white * ref_rms * 10.0 ** (-noise_cfg["snr_db"] / 20.0)
        rec += rumble * ref_rms * 10.0 ** (-noise_cfg["lf_rumble_db"] / 20.0) * 0.05
        mic_resp = np.array([dsp.interpolate_log(mic_f, mic_db, f)
                             for f in np.maximum(np.fft.rfftfreq(rec.size, 1 / fs), 1e-3)])
        rec = np.fft.irfft(np.fft.rfft(rec) * 10.0 ** (mic_resp / 20.0), rec.size)
        return dsp.analyze(rec, stim, mic_compensation_db=mic_correction, config=cfg)

    def sweep_through(index: int, bands=()) -> np.ndarray:
        """What the microphone receives: the stimulus (optionally through the EQ) through speaker + room."""
        ir = rooms.impulse_response(variants[index], dsp.next_power_of_two(stim.signal.size + 8192))
        signal = rooms.apply_distortion(variants[index], stim.signal)
        if bands:
            signal = dsp.filter_signal(signal, list(bands), fs)
        clean = rooms.convolve(signal, ir)
        return clean * 0.25  # a comfortable measuring level, -12 dBFS for the -6 dBFS stimulus

    def measure_at(index: int):
        return capture(sweep_through(index), index)

    per_position = [measure_at(i) for i in range(len(variants))]
    kept, position_deviations = _reject_outliers(per_position)
    measurement = _spatial_average([per_position[i] for i in kept])
    position_spread = float(np.max(_spread_between([per_position[i].smoothed_db for i in kept])))

    settings = eqfit.auto_eq_settings(band_count, env_tuning, cls)
    if reverb_gate and measurement.room_rt60_s is not None:
        settings.room_rt60_s = measurement.room_rt60_s
    eq_env, eq_cls = env_tuning, cls
    correction = eqfit.render(
        measurement.frequencies,
        measurement.smoothed_db,
        eqfit.TargetCurve(target),
        settings,
        roll_off_db=measurement.third_octave_db,
        snr_db=measurement.snr_db,
    )

    # --- 2b. the verification pass (W6) ------------------------------------------------------
    # A second measurement, taken through the correction exactly like the app's "Check" run: same
    # microphone, same noise, same analyzer - only the stimulus now carries the EQ and the pre-gain.
    verification = None
    if verify_run:
        verified = capture(sweep_through(0, correction.bands), 0)
        # The pre-gain is a level change and both curves are normalised in 300 Hz - 3 kHz, so it is
        # deliberately *not* applied here: the verification judges shape, and a level difference between
        # the two runs must not be able to break it (that is what `aLevelChangeBetweenTheRunsDoesNotMatter`
        # pins in the JVM test).
        verification = verify.compare(measurement, verified, correction)

    # --- 3. the truth: what the speaker actually radiates ---------------------------------
    audit_f = np.maximum(np.fft.rfftfreq(1 << 19, 1 / fs), 1e-3)
    # Several positions: the truth is their power average (you cannot sit at three places at once),
    # and the *spread* between them tells which regions an EQ must leave alone.
    per_position_truth = [20.0 * np.log10(np.maximum(np.abs(v.response(audit_f, fs)), 1e-9)) for v in truth_variants]
    dev_db = 10.0 * np.log10(np.mean([10.0 ** (c / 10.0) for c in per_position_truth], axis=0))
    truth_spread_db = _spread_between(per_position_truth)
    corr_db = dsp.Grid(audit_f, fs).response_many(correction.bands)
    # the plan expands to the audit resolution (it is defined on the 48/oct grid)
    desired_full = dsp.resample(measurement.frequencies, correction.desired_correction_db, audit_f)
    tgt_db = eqfit.TargetCurve(target).sample(audit_f)

    grid = dsp.log_grid()
    def on_grid(values: np.ndarray) -> np.ndarray:
        return dsp.resample(audit_f, values, grid)

    # An EQ must not chase a null: where the positions disagree by more than NULL_SPREAD_DB the local
    # dip belongs to *that spot*, not to the speaker - a boost there costs excursion everywhere and
    # makes the other seats worse. Only *narrow* boost bands (Q >= NARROW_Q) are dip-filling by design;
    # a broad bass shelf crossing such a region is not, and must not be flagged (that is why the metric
    # looks at each band's own response instead of the summed curve).
    spread_on_grid = on_grid(truth_spread_db)
    null_mask = spread_on_grid > NULL_SPREAD_DB
    null_boost = 0.0
    if np.any(null_mask):
        response_grid = dsp.Grid(audit_f, fs)
        for band in correction.bands:
            if band.gain_db <= 0.0 or band.q < NARROW_Q:
                continue
            # `Grid.response` already returns dB.
            null_boost = max(null_boost, float(np.max(on_grid(response_grid.response(band))[null_mask])), 0.0)

    # The analyzer normalises every curve to 0 dB in 300 Hz - 3 kHz and `render()` normalises the target
    # the same way, so the truth must be normalised too before the two can be compared as shapes.
    dev_norm = dev_db - _band_mean(dev_db, 300.0, 3000.0)
    # Judge at the resolution the correction works at: the app fits to a *smoothed* measurement
    # (1/1 octave in the bass, 1/3 octave from 320 Hz), so narrow comb ripple is intentionally not
    # corrected - comparing raw truth against a smoothed model would blame the app for physics.
    width = smoothing.width_octaves_at
    in_room_before = _smooth_to_grid(dev_norm, audit_f, grid, width)
    in_room_after = _smooth_to_grid(dev_norm + corr_db, audit_f, grid, width)
    target_grid = on_grid(tgt_db)
    weighted = audio.weighted_tonal_error

    err_before = in_room_before - target_grid
    err_after = in_room_after - target_grid

    # Judged where the app is in charge: the correction range. Below the speaker's roll-off and above
    # the (mic-trustworthy) high limit no EQ can or should do anything - that is physics, not quality.
    #
    # The lower edge is the *full-authority* edge, not the nominal limit: `AutoEqRenderer.rangeWeight`
    # fades the whole correction in over the half octave above `lowLimit` (0 dB at the limit, 100 %
    # at limit * 1.41) and `excursionGuard` tapers boosts even further. Scoring inside that fade
    # penalises the documented design: measured on the mini box it contributed 9.7 dB at 73 Hz - the
    # limit itself - although no filter in the plan is allowed to act there.
    lo, hi = correction.low_limit_hz * LOW_FADE_OCTAVES_SCALE, correction.high_limit_hz

    # A constant level difference is free (it is just volume) - remove it energy-weighted. It has to be
    # removed over *the same band that is judged*: with the offset taken down to the nominal limit but
    # the error scored from the fade edge, the huge roll-off error below the edge pulled the offset
    # down and inflated the residual (party box on a bass-heavy track: 1.29 dB reported instead of
    # 0.91 dB, i.e. a correction that improved the tonal balance looked like it made it worse).
    def free_offset(err: np.ndarray) -> float:
        mask = (grid >= lo) & (grid <= hi)
        w = music_power[mask]
        return float(np.sum(w * err[mask]) / max(float(np.sum(w)), 1e-12))

    before_stats = weighted(err_before - free_offset(err_before), music_power, grid, lo=lo, hi=hi)
    after_stats = weighted(err_after - free_offset(err_after), music_power, grid, lo=lo, hi=hi)
    full_before = weighted(err_before - free_offset(err_before), music_power, grid)
    full_after = weighted(err_after - free_offset(err_after), music_power, grid)

    # The region below the full-authority edge must not get *worse*: there the app may do less, not
    # damage. (A correction that eats bass - the round-8 RESCUE defect - shows up here.)
    bass_lo, bass_hi = correction.low_limit_hz, lo
    bass_before = weighted(err_before - free_offset(err_before), music_power, grid, lo=bass_lo, hi=bass_hi)
    bass_after = weighted(err_after - free_offset(err_after), music_power, grid, lo=bass_lo, hi=bass_hi)
    bass_regression_db = bass_after["rms_weighted_db"] - bass_before["rms_weighted_db"]

    # --- 4. the music itself: real samples, real peaks ------------------------------------
    rendered = dsp.filter_signal(music, correction.bands, fs)
    rendered = _apply_gain_db(rendered, correction.preamp_db)
    raw_peak = float(np.max(np.abs(rendered)))
    limiter_armed = eqfit.force_limiter(env_tuning, cls)
    rendered_without_limiter = rendered
    if limiter_armed:
        rendered = audio.limiter(rendered, fs)
    peak = float(np.max(np.abs(rendered)))
    peak_tp = audio.true_peak_db(rendered)
    clipped = int(np.sum(np.abs(rendered) >= 0.999))
    if limiter_armed:
        gr = audio.limiter_demand_db(rendered_without_limiter, threshold_db=-2.0, ratio=20.0)
        mean_gr = -float(np.mean(gr))
        max_gr = -float(np.min(gr))
        stress = 100.0 * float(np.mean(gr < -LIMITER_STRESS_GR_DB))
    else:
        mean_gr, max_gr, stress = 0.0, 0.0, 0.0

    # Perceived level: the same recording, in the room, with and without the calibration.
    # The *rendered* signal already contains the correction, so only the speaker/room response may be
    # added on top - adding `in_room_after` as well applied the EQ twice and doubled every level change
    # (measured: the device case looked like -5.4 dB although the pre-gain planner's own model - which
    # this check exists to validate - predicted -2.1 dB).
    # Measured, not modelled: both signals are pushed through the room and then judged with BS.1770
    # (K-weighted, gated integrated LUFS) - the standard the promise "as loud as before" is written in.
    # The A-weighted spectrum proxy is kept alongside, because the two disagreeing would be a finding of
    # its own (it was the only evidence this check had until round 13).
    room_ir = _room_filter(in_room_before, grid, fs)
    loud_before = loudness.integrated_lufs(_convolve(music, room_ir), fs)
    loud_after = loudness.integrated_lufs(_convolve(rendered, room_ir), fs)
    loudness_delta = 0.0 if (loud_before is None or loud_after is None) else loud_after - loud_before
    before_music = audio.stats(music, fs)
    after_music = audio.stats(rendered, fs)
    loud_proxy_before = _a_weighted_level(before_music.spectrum_db + in_room_before, grid)
    loud_proxy_after = _a_weighted_level(after_music.spectrum_db + in_room_before, grid)
    loudness_proxy_delta = loud_proxy_after - loud_proxy_before

    # --- 4b. did the filters actually realise the requested correction? -------------------
    judge = (grid >= correction.low_limit_hz) & (grid <= correction.high_limit_hz)
    fidelity = on_grid(corr_db) - on_grid(desired_full)
    fit_rms = float(np.sqrt(np.mean(fidelity[judge] ** 2))) if judge.any() else 0.0
    fit_max = float(np.max(np.abs(fidelity[judge]))) if judge.any() else 0.0
    if os.environ.get("AVZ_PROBE_DEBUG"):
        idx = np.argmax(np.where(judge, np.abs(fidelity), 0.0))
        print(f"    [debug] fit max {fit_max:.2f} dB at {grid[idx]:.0f} Hz; desired {on_grid(desired_full)[idx]:.2f} filter {on_grid(corr_db)[idx]:.2f}")
        np.savez(f"/tmp/avz_{scenario}_{target}_{noise}.npz", grid=grid, dev=in_room_before,
                 err_before=err_before, err_after=err_after, tgt=target_grid,
                 desired=on_grid(desired_full), actual=on_grid(corr_db),
                 power=music_power, lo=lo, hi=hi, preamp=correction.preamp_db,
                 meas=dsp.resample(measurement.frequencies, measurement.smoothed_db, grid))

    # --- 5. protection -------------------------------------------------------------------
    below = grid < correction.low_limit_hz / 2.0
    max_boost_below = float(np.max(on_grid(corr_db)[below])) if below.any() else 0.0
    above = grid > correction.high_limit_hz * 1.1
    max_above = float(np.max(np.abs(on_grid(corr_db)[above]))) if above.any() else 0.0

    failures: list[str] = []
    if fit_rms > THRESHOLDS["fit_rms_db"]:
        failures.append(f"fit RMS {fit_rms:.2f} dB > {THRESHOLDS['fit_rms_db']}")
    if fit_max > THRESHOLDS["fit_max_db"]:
        failures.append(f"fit peak {fit_max:.2f} dB > {THRESHOLDS['fit_max_db']}")
    after = after_stats["rms_weighted_db"]
    before = before_stats["rms_weighted_db"]
    if after > THRESHOLDS["good_enough_db"] and after > before - THRESHOLDS["min_improvement_db"]:
        failures.append(f"tonal {after:.2f} dB (before {before:.2f}, needs <='{THRESHOLDS['good_enough_db']}' or {THRESHOLDS['min_improvement_db']} dB better)")
    if after_stats["p95_db"] > THRESHOLDS["tonal_p95_db"]:
        failures.append(f"tonal p95 {after_stats['p95_db']:.2f} dB > {THRESHOLDS['tonal_p95_db']}")
    # Loudness neutrality is a promise of the limiter-armed classes only (RESCUE / DEVICE / outdoor):
    # everywhere else the static clipping-safe pre-gain deliberately wins over loudness.
    if verification is not None:
        v = verification
        if v["verdict"] != verify.AS_PREDICTED:
            failures.append(f"verification says {v['verdict']} (prediction error {v['prediction_error_db']:.2f} dB)")
        if abs(v["measured_improvement_db"] - v["predicted_improvement_db"]) > THRESHOLDS["verification_gap_db"]:
            failures.append(f"verification measured {v['measured_improvement_db']:.2f} dB, predicted "
                            f"{v['predicted_improvement_db']:.2f} dB")
    if limiter_armed and abs(loudness_delta) > THRESHOLDS["loudness_delta_db"]:
        failures.append(f"loudness {loudness_delta:+.2f} LU")
    # The A-weighted proxy was the only evidence for this check until round 13. Where it and the standard
    # disagree, the difference is reported - a proxy that quietly drifts away from BS.1770 would make every
    # "as loud as before" statement in the docs unverifiable.
    if abs(loudness_delta - loudness_proxy_delta) > THRESHOLDS["loudness_proxy_gap_db"]:
        failures.append(f"A-weighted proxy {loudness_proxy_delta:+.2f} dB vs BS.1770 {loudness_delta:+.2f} LU")
    if 20.0 * math.log10(max(peak, 1e-9)) > THRESHOLDS["peak_dbfs"]:
        failures.append(f"peak {20.0 * math.log10(peak):+.2f} dBFS")
    if clipped > THRESHOLDS["clipped_samples"]:
        failures.append(f"{clipped} clipped samples")
    if peak_tp > THRESHOLDS["peak_dbtp"]:
        failures.append(f"true peak {peak_tp:+.2f} dBTP")
    if max_boost_below > THRESHOLDS["protect_boost_db"]:
        failures.append(f"boost below half the bass limit {max_boost_below:+.1f} dB")
    if limiter_armed and mean_gr > THRESHOLDS["limiter_mean_gr_db"]:
        failures.append(f"limiter works hard: {mean_gr:.2f} dB mean reduction")
    if limiter_armed and stress > THRESHOLDS["limiter_stress_pct"]:
        failures.append(f"limiter clamps {stress:.1f} % of the programme by > {LIMITER_STRESS_GR_DB:.0f} dB")
    if positions > 1 and null_boost > THRESHOLDS["null_boost_db"]:
        failures.append(f"boosts {null_boost:+.1f} dB inside a position-dependent null")
    if bass_regression_db > THRESHOLDS["bass_regression_db"]:
        failures.append(f"bass degraded below the fade {bass_regression_db:+.2f} dB")
    if max_above > THRESHOLDS["above_range_db"]:
        failures.append(f"large change above the correction range {max_above:.1f} dB")

    return CaseResult(
        scenario=scenario,
        speaker_class=speaker_class,
        environment=environment,
        target=target,
        noise=noise,
        measured_snr_db=measurement.median_snr_db,
        warnings=measurement.warnings,
        bands=[asdict(b) for b in correction.bands],
        preamp_db=correction.preamp_db,
        low_limit_hz=correction.low_limit_hz,
        high_limit_hz=correction.high_limit_hz,
        high_pass_hz=correction.high_pass_hz,
        rms_before_db=correction.rms_error_before_db,
        rms_after_db=correction.rms_error_after_db,
        tonal_rms_before_db=before_stats["rms_weighted_db"],
        tonal_rms_after_db=after_stats["rms_weighted_db"],
        full_band_before_db=full_before["rms_weighted_db"],
        full_band_after_db=full_after["rms_weighted_db"],
        tonal_p95_after_db=after_stats["p95_db"],
        loudness_delta_db=loudness_delta,
        loudness_proxy_delta_db=loudness_proxy_delta,
        verification=verification,
        integrated_before_lufs=loud_before,
        integrated_after_lufs=loud_after,
        peak_dbfs=20.0 * math.log10(max(peak, 1e-9)),
        peak_dbtp=peak_tp,
        clipped_samples=clipped,
        max_boost_below_limit_db=max_boost_below,
        max_change_above_limit_db=max_above,
        fit_rms_db=fit_rms,
        fit_max_db=fit_max,
        raw_peak_dbfs=20.0 * math.log10(max(raw_peak, 1e-9)),
        limiter_armed=limiter_armed,
        bass_regression_db=bass_regression_db,
        limiter_mean_gr_db=mean_gr,
        limiter_stress_pct=stress,
        limiter_max_gr_db=max_gr,
        positions=positions,
        positions_used=len(kept),
        thd_pct=measurement.thd_pct,
        room_rt60_s=measurement.room_rt60_s,
        position_spread_db=position_spread,
        null_boost_db=null_boost,
        failures=failures,
    )


def _room_filter(deviation_db: np.ndarray, grid: np.ndarray, fs: int) -> np.ndarray:
    """Minimum-phase FIR of the room/speaker deviation curve, for listening to a signal "in the room"."""
    n = 1 << 15
    freqs = np.maximum(np.fft.rfftfreq(n, 1.0 / fs), 1e-3)
    magnitude = 10.0 ** (dsp.resample(grid, deviation_db, freqs) / 20.0)
    # `minimum_phase` returns the *spectrum*; the IR is its inverse transform.
    return np.fft.irfft(rooms.minimum_phase(magnitude), n)


def _convolve(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    n = dsp.next_power_of_two(x.size + ir.size)
    return np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)[: x.size]


def _a_weighted_level(spectrum_db: np.ndarray, grid: np.ndarray) -> float:
    mask = (grid >= 40.0) & (grid <= 16000.0)
    w = np.array([10.0 ** (eqfit.a_weighting_db(f) / 10.0) for f in grid[mask]])
    p = 10.0 ** (spectrum_db[mask] / 10.0)
    return 10.0 * math.log10(max(float(np.sum(w * p)), 1e-20))


CASES = [
    ("bt-minibox", "STANDARD", "INDOOR", "SMART", "quiet-room"),
    ("bt-minibox", "STANDARD", "INDOOR", "HARMAN", "street"),
    ("party-box", "RESCUE", "OUTDOOR", "HARMAN", "party"),
    ("phone-speaker", "DEVICE", "INDOOR", "HARMAN", "quiet-room"),
    ("laptop", "STANDARD", "INDOOR", "SMART", "quiet-room"),
    ("party-box", "STANDARD", "OUTDOOR", "BASS_BOOST", "street"),
]


# --------------------------------------------------------------- field failures (W3/W4) --
#
# The six standard cases are a *healthy* chain, and they must stay healthy: no distortion, no reverb, no
# new warning. What the new metrics exist for is the other end - a driver driven too hard and a room that
# rings. These three cases pin that end, so a metric that silently stops firing is caught.

STRESS_CASES = (
    # scenario, class, env, target, noise, rt60, drive, expected warnings, expectation text
    ("bt-minibox", "STANDARD", "INDOOR", "SMART", "quiet-room", 0.0, 0.05,
     {"SPEAKER_DISTORTION"}, "a driven mini box is heard as distortion"),
    ("bt-minibox", "STANDARD", "INDOOR", "SMART", "quiet-room", 1.0, 0.0,
     {"REVERBERANT_ROOM"}, "a 1 s room is heard as reverberant"),
    ("bt-minibox", "STANDARD", "INDOOR", "SMART", "quiet-room", 0.0, 0.0,
     set(), "a clean chain raises neither warning"),
)


def run_stress(music: np.ndarray, music_power: np.ndarray) -> int:
    print("\n=== field failures: does the app notice?")
    failures = 0
    for scenario, cls, env, target, noise, rt60, drive, expected, text in STRESS_CASES:
        res = run_case(scenario, cls, env, target, noise, music, music_power,
                       rt60_s=rt60, distortion_drive=drive)
        raised = {w for w in res.warnings if w in ("SPEAKER_DISTORTION", "DRIVER_RATTLE", "REVERBERANT_ROOM")}
        detail = []
        if res.thd_pct is not None:
            detail.append(f"thd {res.thd_pct:.2f} %")
        if res.room_rt60_s is not None:
            detail.append(f"rt60 {res.room_rt60_s:.2f} s")
        detail.append(f"tonal {res.tonal_rms_before_db:.2f} -> {res.tonal_rms_after_db:.2f} dB")
        problems = []
        if raised != expected:
            problems.append(f"warnings {sorted(raised)} != {sorted(expected)}")
        if rt60 > 0.0 and res.room_rt60_s is not None and abs(res.room_rt60_s - rt60) > 0.2:
            problems.append(f"rt60 off by {abs(res.room_rt60_s - rt60):.2f} s")
        if rt60 > 0.0 and res.room_rt60_s is None:
            problems.append("rt60 not measured")
        if drive > 0.0 and (res.thd_pct is None or res.thd_pct < 3.0):
            problems.append("driven speaker not measured as distorted")
        # A correction must still help in a ringing room: reporting is not an excuse to give up.
        if res.tonal_rms_after_db > res.tonal_rms_before_db - 0.25 and res.tonal_rms_after_db > 3.0:
            problems.append(f"correction did not help ({res.tonal_rms_before_db:.2f} -> {res.tonal_rms_after_db:.2f} dB)")
        print(f"{'ok  ' if not problems else 'FAIL'} {text:46s} " + "  ".join(detail)
              + ("" if not problems else "  <- " + "; ".join(problems)))
        failures += bool(problems)
    print(f"\n{len(STRESS_CASES) - failures}/{len(STRESS_CASES)} field-failure cases behave")
    return failures


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--music", action="append", default=[], help="real recording(s) to audition (any ffmpeg format)")
    ap.add_argument("--json", help="write the raw results as JSON")
    ap.add_argument("--markdown", help="write a readable report")
    ap.add_argument("--mic-error", type=float, default=0.0, help="profile estimation error added to the compensation, dB")
    ap.add_argument("--truth-positions", type=int, default=0,
                    help="positions the result is judged at (default: same as --positions). "
                         "--positions 1 --truth-positions 3 answers \"is one measurement enough?\"")
    ap.add_argument("--rt60", type=float, default=0.0,
                    help="add a diffuse room tail with this reverberation time (s) to every case (W4)")
    ap.add_argument("--drive", type=float, default=0.0,
                    help="drive the modelled speaker into soft saturation by this fraction (W3)")
    ap.add_argument("--verify", action="store_true",
                    help="also run the verification pass (W6): measure again through the fitted EQ and "
                         "compare what improved with what the plan predicted")
    ap.add_argument("--stress", action="store_true",
                    help="also run the three field-failure cases (distortion, reverberation, clean chain)")
    ap.add_argument("--positions", type=int, default=1,
                    help="listening positions per case (1 = the single-position truth the probe always used; "
                         "3 = room modes and comb notches move between positions, so a dip can be judged as "
                         "position-dependent instead of being flattered)")
    args = ap.parse_args(argv)

    music_paths = args.music or [os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "branding", "intro", "Intro1.mp4")]
    results: list[CaseResult] = []
    for path in music_paths:
        music = load_music(path)
        grid = dsp.log_grid()
        music_power = audio.stats(music).spectrum_db
        music_power = 10.0 ** (music_power / 10.0)
        print(f"  positions per case: {max(1, args.positions)}"
              + ("" if args.positions > 1 else "  (single-position truth: dips are flattered)"))
        print(f"\n=== programme material: {os.path.basename(path)} "
              f"({music.size / audio.SAMPLE_RATE:.1f} s, {audio.stats(music).crest_db:.1f} dB crest)")
        for scenario, cls, env, target, noise in CASES:
            res = run_case(scenario, cls, env, target, noise, music, music_power,
                           mic_error_db=args.mic_error, positions=max(1, args.positions),
                           truth_positions=args.truth_positions, rt60_s=args.rt60,
                           distortion_drive=args.drive, verify_run=args.verify)
            results.append(res)
            status = "ok  " if res.ok else "FAIL"
            print(f"{status} {scenario:13s} {cls:8s} {env:7s} {target:10s} {noise:10s} "
                  f"tonal {res.tonal_rms_before_db:5.2f} -> {res.tonal_rms_after_db:5.2f} dB "
                  f"(p95 {res.tonal_p95_after_db:4.2f})  loud {res.loudness_delta_db:+5.2f} LU  "
                  f"peak {res.peak_dbfs:+5.2f} dBFS / {res.peak_dbtp:+5.2f} dBTP  preamp {res.preamp_db:+5.1f}  "
                  f"bands {len(res.bands):2d}"
                  + (f"  null-boost {res.null_boost_db:4.2f} dB  spread {res.position_spread_db:4.1f} dB"
                     if res.positions > 1 else "")
                  + (f"  used {res.positions_used}/{res.positions}" if res.positions_used < res.positions else "")
                  + (f"  verify {res.verification['verdict']}: {res.verification['measured_improvement_db']:.2f}"
                     f"/{res.verification['predicted_improvement_db']:.2f} dB (err {res.verification['prediction_error_db']:.2f})"
                     if res.verification is not None else "")
                  + (f"  thd {res.thd_pct:4.2f} %" if res.thd_pct is not None else "")
                  + (f"  rt60 {res.room_rt60_s:4.2f} s" if res.room_rt60_s is not None else "")
                  + (f"  limiter mean {res.limiter_mean_gr_db:4.2f} max {res.limiter_max_gr_db:5.2f} dB / "
                     f"{res.limiter_stress_pct:4.1f} %" if res.limiter_armed else "")
                  + ("" if res.ok else "  <- " + "; ".join(res.failures)))

    failed = [r for r in results if not r.ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} cases within every threshold")
    stress_failures = 0
    if args.stress and music_paths:
        last = load_music(music_paths[-1])
        stress_failures = run_stress(last, 10.0 ** (audio.stats(last).spectrum_db / 10.0))

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"thresholds": THRESHOLDS, "cases": [asdict(r) for r in results]}, fh, indent=1)
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as fh:
            fh.write(_markdown(results))
    return 0 if not failed and not stress_failures else 1


def _markdown(results: list[CaseResult]) -> str:
    lines = ["| Scenario | Class | Env | Target | Noise | Tonal error before | after | p95 | Loudness | Peak (sample / true) | Limiter (mean / max GR / stress) | Null boost | Preamp | Bands |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        lines.append(
            f"| {r.scenario} | {r.speaker_class} | {r.environment} | {r.target} | {r.noise} | "
            f"{r.tonal_rms_before_db:.2f} dB | **{r.tonal_rms_after_db:.2f} dB** | {r.tonal_p95_after_db:.2f} dB | "
            f"{r.loudness_delta_db:+.2f} LU | {r.peak_dbfs:+.2f} dBFS / {r.peak_dbtp:+.2f} dBTP | "
            f"{(f'{r.limiter_mean_gr_db:.2f} / {r.limiter_max_gr_db:.2f} dB / {r.limiter_stress_pct:.1f} %' if r.limiter_armed else '-')} | "
            f"{(f'{r.null_boost_db:.2f} dB (spread {r.position_spread_db:.1f})' if r.positions > 1 else '-')} | "
            f"{r.preamp_db:+.1f} dB | {len(r.bands)} |"
        )
    bad = [r for r in results if not r.ok]
    lines.append("")
    lines.append(f"{len(results) - len(bad)}/{len(results)} cases within every threshold.")
    for r in bad:
        lines.append(f"* **{r.scenario} / {r.speaker_class} / {r.environment} / {r.target}**: " + "; ".join(r.failures))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    raise SystemExit(main())
