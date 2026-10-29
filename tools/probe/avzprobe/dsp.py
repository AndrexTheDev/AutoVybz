"""1:1 port of the AutoVybz measurement DSP (Kotlin) for the offline live-music probe.

Every function here mirrors a Kotlin counterpart in
`app/src/main/java/com/andrexthedev/autovybz/measurement/`; the file names and the
constants are kept identical so a change on one side is easy to find on the other.
The probe is *not* a second implementation with its own ideas - it is a
translation used to exercise the real algorithms on a laptop without a device.

Parity is checked against the Kotlin unit tests in `tools/probe/check_parity.py`
(golden values copied from the JVM tests where they are numeric).
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Callable, Sequence

import numpy as np

# ---------------------------------------------------------------- frequency grid --

MIN_HZ, MAX_HZ = 20.0, 20000.0


def log_grid(f_min: float = MIN_HZ, f_max: float = MAX_HZ, points_per_octave: int = 48) -> np.ndarray:
    """`FrequencyGrid.log`: fMin * 2^(i/ppo), clamped to fMax (Kotlin uses ceil + 1 points)."""
    assert f_min > 0 and f_max > f_min and points_per_octave > 0
    octaves = math.log(f_max / f_min, 2.0)
    count = math.ceil(octaves * points_per_octave) + 1
    return np.minimum(f_min * 2.0 ** (np.arange(count) / points_per_octave), f_max)


def interpolate_log(xs: np.ndarray, ys: np.ndarray, x: float) -> float:
    """`FrequencyGrid.interpolateLog`: linear in log10(f), clamped outside the table."""
    xs = np.asarray(xs, dtype=float)
    ys = np.asarray(ys, dtype=float)
    if x <= xs[0]:
        return float(ys[0])
    if x >= xs[-1]:
        return float(ys[-1])
    lo, hi = 0, xs.size - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if xs[mid] <= x:
            lo = mid
        else:
            hi = mid
    t = (math.log10(x) - math.log10(xs[lo])) / (math.log10(xs[hi]) - math.log10(xs[lo]))
    return float(ys[lo] + t * (ys[hi] - ys[lo]))


def resample(xs: np.ndarray, ys: np.ndarray, grid: np.ndarray) -> np.ndarray:
    return np.array([interpolate_log(xs, ys, f) for f in grid])


def band_mean(grid: np.ndarray, values: np.ndarray, lo: float, hi: float) -> float:
    mask = (grid >= lo) & (grid <= hi)
    return float(values[mask].mean()) if mask.any() else 0.0


@dataclass(frozen=True)
class SmoothingProfile:
    """`SmoothingProfile`: 1/1 octave in the bass, 1/3 octave from 320 Hz on (default)."""

    bass_octaves: float = 1.0
    bass_corner_hz: float = 80.0
    detail_octaves: float = 1.0 / 3.0
    detail_start_hz: float = 320.0

    def width_octaves_at(self, f: float) -> float:
        if f <= self.bass_corner_hz:
            return self.bass_octaves
        if f >= self.detail_start_hz:
            return self.detail_octaves
        t = math.log(f / self.bass_corner_hz) / math.log(self.detail_start_hz / self.bass_corner_hz)
        return self.bass_octaves + t * (self.detail_octaves - self.bass_octaves)


DYNAMIC = SmoothingProfile()
FINE = SmoothingProfile(bass_octaves=0.5, detail_octaves=1.0 / 6.0)
STRONG = SmoothingProfile(bass_octaves=1.5, detail_octaves=0.5)
DEVICE = SmoothingProfile(bass_octaves=1.0, bass_corner_hz=400.0, detail_octaves=1.0 / 6.0, detail_start_hz=700.0)
THIRD_OCTAVE = SmoothingProfile(1.0 / 3, 20.0, 1.0 / 3, 21.0)
RAW = SmoothingProfile(1.0 / 48, 20.0, 1.0 / 48, 21.0)


def spectrum_to_grid(
    power: np.ndarray,
    bin_hz: float,
    grid: np.ndarray,
    profile: SmoothingProfile | None = None,
    width_fn: Callable[[float], float] | None = None,
    valid_low_hz: float = 0.0,
    valid_high_hz: float = float("inf"),
) -> np.ndarray:
    """`SpectrumBinning.toGrid`: mean power inside a fractional-octave window per grid point."""
    assert power.size >= 3 and bin_hz > 0
    width = width_fn or (lambda f: (profile or DYNAMIC).width_octaves_at(f))
    prefix = np.concatenate(([0.0], np.cumsum(power)))
    first_valid = min(max(1, math.ceil(valid_low_hz / bin_hz)), power.size - 2)
    last_valid = max(min(power.size - 1, math.floor(min(valid_high_hz, 1e12) / bin_hz)), first_valid + 1)
    out = np.empty(grid.size)
    two_pow = 2.0
    for gi, f in enumerate(grid):
        half = width(f) / 2.0
        lo = f * two_pow ** (-half)
        hi = f * two_pow ** half
        k_lo = max(first_valid, math.ceil(lo / bin_hz))
        k_hi = min(last_valid, math.floor(hi / bin_hz))
        if k_hi >= k_lo:
            out[gi] = (prefix[k_hi + 1] - prefix[k_lo]) / (k_hi - k_lo + 1)
        else:
            pos = min(max(f / bin_hz, float(first_valid)), float(last_valid))
            k0 = min(math.floor(pos), last_valid - 1)
            t = pos - k0
            out[gi] = power[k0] * (1 - t) + power[k0 + 1] * t
    return out


def power_to_db(p):
    return 10.0 * np.log10(np.maximum(p, 1e-20))


# ------------------------------------------------------------------------ biquads --

PEAK, LOW_SHELF, HIGH_SHELF, HIGH_PASS = "PK", "LSC", "HSC", "HPQ"
DEFAULT_SAMPLE_RATE = 48000.0


@dataclass(frozen=True)
class PeqBand:
    type: str
    frequency: float
    gain_db: float
    q: float


def coefficients(band: PeqBand, sample_rate: float = DEFAULT_SAMPLE_RATE) -> np.ndarray:
    """`BiquadMath.coefficients` - RBJ cookbook, identical to the Equalizer314 engine."""
    w = 2.0 * math.pi * band.frequency / sample_rate
    cw, sw = math.cos(w), math.sin(w)
    a = 10.0 ** (band.gain_db / 40.0)
    if band.type == PEAK:
        if abs(band.gain_db) < 0.01:
            return np.array([1.0, 0.0, 0.0, 1.0, 0.0, 0.0])
        alpha = sw / (2.0 * band.q)
        return np.array([1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a])
    if band.type == LOW_SHELF:
        beta = math.sqrt(a) / band.q
        return np.array([
            a * ((a + 1) - (a - 1) * cw + beta * sw),
            2 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - beta * sw),
            (a + 1) + (a - 1) * cw + beta * sw,
            -2 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - beta * sw,
        ])
    if band.type == HIGH_SHELF:
        beta = math.sqrt(a) / band.q
        return np.array([
            a * ((a + 1) + (a - 1) * cw + beta * sw),
            -2 * a * ((a - 1) + (a + 1) * cw),
            a * ((a + 1) + (a - 1) * cw - beta * sw),
            (a + 1) - (a - 1) * cw + beta * sw,
            2 * ((a - 1) - (a + 1) * cw),
            (a + 1) - (a - 1) * cw - beta * sw,
        ])
    if band.type == HIGH_PASS:
        alpha = sw / (2.0 * band.q)
        return np.array([(1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0, 1.0 + alpha, -2.0 * cw, 1.0 - alpha])
    raise ValueError(band.type)


def phi(frequency: float, sample_rate: float = DEFAULT_SAMPLE_RATE) -> float:
    s = math.sin(math.pi * frequency / sample_rate)
    return 4.0 * s * s


def magnitude_db(coeffs: np.ndarray, phi_value: float) -> float:
    b0, b1, b2, a0, a1, a2 = coeffs
    bs = b0 + b1 + b2
    asum = a0 + a1 + a2
    num = bs * bs + (b0 * b2 * phi_value - (b1 * (b0 + b2) + 4 * b0 * b2)) * phi_value
    den = asum * asum + (a0 * a2 * phi_value - (a1 * (a0 + a2) + 4 * a0 * a2)) * phi_value
    return 10.0 * math.log10(max(num, 1e-30) / max(den, 1e-30))


class Grid:
    """`BiquadMath.Grid`: precomputed phi per grid frequency."""

    def __init__(self, frequencies: np.ndarray, sample_rate: float = DEFAULT_SAMPLE_RATE):
        self.frequencies = np.asarray(frequencies, dtype=float)
        self.sample_rate = sample_rate
        self.phi = np.array([phi(f, sample_rate) for f in self.frequencies])

    def response(self, band: PeqBand, out: np.ndarray | None = None, accumulate: bool = False) -> np.ndarray:
        c = coefficients(band, self.sample_rate)
        vals = np.array([magnitude_db(c, p) for p in self.phi])
        if out is None:
            return vals
        out += vals if accumulate else (vals - out)
        return out

    def response_many(self, bands: Sequence[PeqBand]) -> np.ndarray:
        out = np.zeros(self.frequencies.size)
        for b in bands:
            self.response(b, out, accumulate=True)
        return out


def filter_signal(x: np.ndarray, bands: Sequence[PeqBand], sample_rate: float = DEFAULT_SAMPLE_RATE) -> np.ndarray:
    """Cascade the bands as direct-form II transposed biquads (what the engine plays).

    Float64 throughout; the caller decides where to quantise. A HIGH_PASS band ignores
    `gain_db` exactly like the engine.
    """
    y = np.asarray(x, dtype=np.float64)
    for band in bands:
        b0, b1, b2, a0, a1, a2 = coefficients(band, sample_rate)
        b0, b1, b2 = b0 / a0, b1 / a0, b2 / a0
        a1, a2 = a1 / a0, a2 / a0
        out = np.empty_like(y)
        z1 = z2 = 0.0
        for i, xv in enumerate(y):
            yv = b0 * xv + z1
            z1 = b1 * xv - a1 * yv + z2
            z2 = b2 * xv - a2 * yv
            out[i] = yv
        y = out
    return y


# ----------------------------------------------------------------------- stimulus --

DEFAULT_STIMULUS_SAMPLE_RATE = 48000
SWEEP_START_HZ, SWEEP_END_HZ = 20.0, 20000.0
PEAK_LEVEL = 0.5
LEAD_SILENCE_S, PINK_S, GAP_S, SWEEP_S, TAIL_S = 0.75, 2.75, 0.5, 5.0, 1.0
CREST_CLIP_RATIO, CREST_ITERATIONS = 0.45, 20
NOISE_WINDOW_MARGIN_S = 0.05


@dataclass
class StimulusPlan:
    sample_rate: int
    lead_silence: int
    pink_start: int
    pink_length: int
    pink_period: int
    sweep_start: int
    sweep_length: int
    total_length: int
    sweep_start_hz: float
    sweep_end_hz: float
    fade_in_samples: int


@dataclass
class Stimulus:
    plan: StimulusPlan
    signal: np.ndarray
    sweep: np.ndarray
    pink_period: np.ndarray
    pink_period_power: np.ndarray


def stimulus_plan(sample_rate: int = DEFAULT_STIMULUS_SAMPLE_RATE) -> StimulusPlan:
    def s(seconds: float) -> int:
        return int(round(seconds * sample_rate))

    lead, pink, gap, sweep, tail = s(LEAD_SILENCE_S), s(PINK_S), s(GAP_S), s(SWEEP_S), s(TAIL_S)
    available = lead - s(NOISE_WINDOW_MARGIN_S)
    period = 1
    while period * 2 <= available:
        period *= 2
    assert period >= 4096
    return StimulusPlan(
        sample_rate=sample_rate,
        lead_silence=lead,
        pink_start=lead,
        pink_length=pink,
        pink_period=period,
        sweep_start=lead + pink + gap,
        sweep_length=sweep,
        total_length=lead + pink + gap + sweep + tail,
        sweep_start_hz=SWEEP_START_HZ,
        sweep_end_hz=min(SWEEP_END_HZ, sample_rate * 0.45),
        fade_in_samples=s(0.05),
    )


def raised_cosine(x: float) -> float:
    return 0.5 - 0.5 * math.cos(math.pi * min(max(x, 0.0), 1.0))


def log_sweep(plan: StimulusPlan) -> np.ndarray:
    n, fs = plan.sweep_length, float(plan.sample_rate)
    f1, f2 = plan.sweep_start_hz, plan.sweep_end_hz
    duration = n / fs
    rate = math.log(f2 / f1)
    k = 2.0 * math.pi * f1 * duration / rate
    fade_in, fade_out = plan.fade_in_samples, int(round(0.005 * fs))
    i = np.arange(n)
    t = i / fs
    g = np.ones(n)
    ramp = np.array([raised_cosine(v) for v in (i[:fade_in] / fade_in)])
    g[:fade_in] = ramp
    from_end = n - 1 - i
    tail_ramp = np.array([raised_cosine(v) for v in (from_end[-fade_out:] / fade_out)])
    g[-fade_out:] = np.minimum(g[-fade_out:], tail_ramp)
    return (PEAK_LEVEL * g * np.sin(k * (np.exp(t * rate / duration) - 1.0))).astype(np.float64)


def periodic_pink_noise(period: int, sample_rate: int, seed: int) -> np.ndarray:
    """|X(f)|^2 ~ 1/f with random phases + iterative crest-factor reduction (see the Kotlin doc)."""
    assert period > 0 and (period & (period - 1)) == 0
    rng = np.random.default_rng(seed)
    bin_hz = sample_rate / period
    re = np.zeros(period)
    im = np.zeros(period)
    for k in range(1, period // 2):
        f = k * bin_hz
        if f < SWEEP_START_HZ or f > min(SWEEP_END_HZ, sample_rate * 0.45):
            continue
        mag = 1.0 / math.sqrt(f)
        phase = rng.random() * 2.0 * math.pi
        re[k] = mag * math.cos(phase)
        im[k] = mag * math.sin(phase)
        re[period - k] = re[k]
        im[period - k] = -im[k]
    magnitudes = np.abs(re + 1j * im)
    # numpy's ifft scales by 1/n, exactly like KotlinFft.transform(inverse = true)
    re = np.fft.ifft(re + 1j * im).real
    initial_peak = float(np.max(np.abs(re)))
    if initial_peak <= 0:
        return re
    clip = initial_peak * CREST_CLIP_RATIO
    for _ in range(CREST_ITERATIONS):
        re = np.clip(re, -clip, clip)
        spec = np.fft.fft(re)
        cur = np.abs(spec)
        scale = np.where(cur > 0, magnitudes / np.where(cur > 0, cur, 1.0), 0.0)
        spec = np.where(cur > 0, spec * scale, magnitudes)
        re = np.fft.ifft(spec).real
    peak = float(np.max(np.abs(re)))
    return re * (PEAK_LEVEL / peak if peak > 0 else 0.0)


def stimulus(sample_rate: int = DEFAULT_STIMULUS_SAMPLE_RATE, seed: int = 0x5EED) -> Stimulus:
    plan = stimulus_plan(sample_rate)
    sweep = log_sweep(plan)
    pink = periodic_pink_noise(plan.pink_period, sample_rate, seed)
    signal = np.zeros(plan.total_length)
    fade = plan.fade_in_samples
    for i in range(plan.pink_length):
        g = 1.0
        if i < fade:
            g = raised_cosine(i / fade)
        from_end = plan.pink_length - 1 - i
        if from_end < fade:
            g = min(g, raised_cosine(from_end / fade))
        signal[plan.pink_start + i] = pink[i % plan.pink_period] * g
    signal[plan.sweep_start:plan.sweep_start + sweep.size] = sweep
    ref = power_spectrum(pink, 0, pink.size, plan.pink_period)
    return Stimulus(plan, signal, sweep, pink, ref)


def power_spectrum(signal: np.ndarray, start: int, length: int, n: int) -> np.ndarray:
    re = np.zeros(n)
    count = max(0, min(length, n, signal.size - start))
    re[:count] = signal[start:start + count]
    spec = np.fft.fft(re)
    return (spec.real ** 2 + spec.imag ** 2)[: n // 2 + 1]


# ----------------------------------------------------------------------- analyzer --

@dataclass
class AnalyzerConfig:
    smoothing: SmoothingProfile = DYNAMIC
    points_per_octave: int = 48
    max_latency_seconds: float = 0.8
    ir_pre_seconds: float = 0.004
    ir_post_seconds: float = 0.30
    sweep_weight: float = 0.5


@dataclass
class MeasurementResult:
    sample_rate: int
    frequencies: np.ndarray
    raw_db: np.ndarray
    smoothed_db: np.ndarray
    third_octave_db: np.ndarray
    sweep_smoothed_db: np.ndarray
    pink_smoothed_db: np.ndarray
    snr_db: np.ndarray
    latency_ms: float
    input_peak: float
    median_snr_db: float
    warnings: list[str] = field(default_factory=list)
    normalization_db: float = 0.0
    # W3/W4: measured on the same deconvolution, once per position.
    thd_pct: float | None = None
    harmonics_db: dict = field(default_factory=dict)
    h3_minus_h2_db: float | None = None
    room_rt60_s: float | None = None


DC_REMOVAL_THRESHOLD = 1e-5
BLEND_START_SNR_DB, BLEND_FULL_SNR_DB = 3.0, 12.0
LATENCY_EDGE_SECONDS = 0.002


def next_power_of_two(n: int) -> int:
    p = 1
    while p < n:
        p <<= 1
    return p


def blend_weight(snr_db: float, sweep_weight: float) -> float:
    w = min(max(sweep_weight, 0.0), 1.0)
    if snr_db <= BLEND_START_SNR_DB:
        return 1.0
    if snr_db >= BLEND_FULL_SNR_DB:
        return w
    t = (snr_db - BLEND_START_SNR_DB) / (BLEND_FULL_SNR_DB - BLEND_START_SNR_DB)
    return 1.0 + t * (w - 1.0)


def deconvolve_sweep(recording: np.ndarray, stim: Stimulus) -> np.ndarray:
    plan = stim.plan
    seg_len = recording.size - plan.sweep_start
    n = next_power_of_two(seg_len + plan.sweep_length)
    yr = np.zeros(n, dtype=complex)
    yr[:seg_len] = recording[plan.sweep_start:plan.sweep_start + seg_len]
    xr = np.zeros(n, dtype=complex)
    xr[: stim.sweep.size] = stim.sweep
    Y = np.fft.fft(yr)
    X = np.fft.fft(xr)
    max_pow = float(np.max(np.abs(X) ** 2))
    bin_hz = plan.sample_rate / n
    k = np.arange(n)
    f = np.minimum(k, n - k) * bin_hz
    in_band = (f >= plan.sweep_start_hz) & (f <= plan.sweep_end_hz)
    eps = np.where(in_band, max_pow * 1e-6, max_pow * 1e-1)
    h = Y * np.conj(X) / (np.abs(X) ** 2 + eps)
    return np.fft.ifft(h).real


def locate_ir_peak(ir: np.ndarray, max_lag: int, fs: int) -> int:
    if ir.size == 0:
        return 0
    win = max(1, int(round(0.001 * fs)))
    hi = min(max_lag, ir.size - 1)
    limit = min(ir.size, hi + win + 1)
    prefix = np.concatenate(([0.0], np.cumsum(ir[:limit] ** 2)))
    best_start, best_energy = 0, -1.0
    for s in range(hi + 1):
        e = prefix[min(s + win, limit)] - prefix[s]
        if e > best_energy:
            best_energy, best_start = e, s
    lo = max(0, best_start - win)
    hi2 = min(ir.size - 1, best_start + win)
    idx = lo + int(np.argmax(np.abs(ir[lo:hi2 + 1])))
    return idx


def windowed_ir_power(ir: np.ndarray, peak: int, fs: int, config: AnalyzerConfig) -> np.ndarray:
    pre = max(int(round(config.ir_pre_seconds * fs)), 1)
    post = max(int(round(config.ir_post_seconds * fs)), 16)
    n = next_power_of_two(max(pre + post, 32768))
    re = np.zeros(n)
    taper = max(int(round(post * 0.3)), 1)
    for j in range(-pre, post):
        src = peak + j
        if src < 0 or src >= ir.size:
            continue
        if j < 0:
            w = 0.5 - 0.5 * math.cos(math.pi * (j + pre) / pre)
        elif j >= post - taper:
            w = 0.5 + 0.5 * math.cos(math.pi * (j - (post - taper)) / taper)
        else:
            w = 1.0
        re[j + pre] = ir[src] * w
    spec = np.fft.fft(re)
    return (spec.real ** 2 + spec.imag ** 2)[: n // 2 + 1]


# ------------------------------------------------------- harmonic distortion (W3) --
#
# Farina's ESS trick: a memoryless nonlinearity of order n puts its distortion product for the *frequency
# that is currently being swept* exactly `T * ln(n) / ln(f2/f1)` before the linear impulse response. So a
# 5 s sweep that already contains the room's answer also contains H2..H5 at fixed lags - no extra
# measurement, no extra second of playback. The windows are ±1 ms, which is why the 4 ms pre-window of
# `AnalyzerConfig` never sees them: the magnitude measurement stays distortion-free.

HARMONIC_ORDERS = (2, 3, 4, 5)
HARMONIC_WINDOW_S = 0.001
# The linear peak must sit this far above the local (non-harmonic) floor, otherwise the deconvolution is
# noise and there is nothing to subtract from.
MIN_PEAK_ABOVE_FLOOR_DB = 12.0
# A harmonic window must clear that floor by this much to count as *observed*; anything closer is noise.
HARMONIC_FLOOR_MARGIN_DB = 6.0
# Warning thresholds (docs/AUDIO_ROADMAP.md, W3).
DISTORTION_WARN_PCT = 3.0
# The roadmap proposed "H3 - H2 > 6 dB" as the rattle signature. Measured against a symmetric soft clip -
# which is what a driven driver actually does - that rule fires on everything: symmetric nonlinearity
# produces *only* odd orders, so H3 sits 46 dB above H2 while nothing rattles. A mechanical buzz is
# impulsive, i.e. broadband: its harmonics do not decay with order. The rule is therefore "rich high-order
# content": H5 within this many dB of H3.
RATTLE_H5_MINUS_H3_DB = -6.0
# Control points in ln(order) units, deliberately *between* the harmonics: whatever sits there is the
# room's own decay (or noise), not a distortion product.
_FLOOR_LN_OFFSETS = (0.55, 0.90, 1.25, 1.50, 1.85)


def _window_peak(x: np.ndarray, index: int, half: int) -> float:
    """Largest |x| within ±`half` samples of `index`, wrapped (the deconvolution is circular)."""
    idx = (index + np.arange(-half, half + 1)) % x.size
    return float(np.max(np.abs(x[idx])))


def harmonic_distortion(ir: np.ndarray, peak: int, sweep_seconds: float,
                        f1: float, f2: float, fs: int) -> dict | None:
    """H2..H5 relative to the linear peak and THD in percent, or None when the floor is too close.

    `thd_pct` is the standard definition, `sqrt(sum_n a_n^2) * 100` with `a_n` the amplitude ratio of
    harmonic n - i.e. every observed harmonic contributes, and a harmonic that does not clear the floor
    is left out instead of being reported as noise.
    """
    half = max(1, int(round(HARMONIC_WINDOW_S * fs)))
    span = math.log(f2 / f1)
    if span <= 0.0 or sweep_seconds <= 0.0:
        return None
    def lag_index(ln_order: float) -> int:
        return int(round(peak - sweep_seconds * ln_order / span * fs))
    linear = _window_peak(ir, peak, half)
    if linear <= 0.0:
        return None
    floor = max(_window_peak(ir, lag_index(u), half) for u in _FLOOR_LN_OFFSETS)
    floor = max(floor, 1e-20)
    peak_above_floor = 20.0 * math.log10(linear / floor)
    if peak_above_floor < MIN_PEAK_ABOVE_FLOOR_DB:
        return None
    harmonics: dict[int, float] = {}
    for order in HARMONIC_ORDERS:
        level = _window_peak(ir, lag_index(math.log(order)), half)
        if 20.0 * math.log10(max(level, 1e-20) / floor) < HARMONIC_FLOOR_MARGIN_DB:
            continue
        harmonics[order] = 20.0 * math.log10(max(level, 1e-20) / linear)
    if not harmonics:
        return None
    thd_pct = 100.0 * math.sqrt(sum(10.0 ** (db / 10.0) for db in harmonics.values()))
    h3_minus_h2 = (harmonics[3] - harmonics[2]) if (2 in harmonics and 3 in harmonics) else None
    return {
        "harmonics_db": harmonics,
        "thd_pct": thd_pct,
        "h3_minus_h2_db": h3_minus_h2,
        "peak_above_floor_db": peak_above_floor,
    }


# ------------------------------------------------------------ room decay (W4) --

REVERB_WARN_S = 0.8
# How far below the end of the fit region (-25 dB) the noise plateau must sit for the fit to be believable.
NOISE_CLEARANCE_DB = 10.0
# The direct sound is not room decay: it is a few milliseconds of energy that would otherwise occupy the
# first 10-15 dB of the Schroeder curve and make every room look ~40 % drier than it is (measured on
# synthetic rooms: 0.50 s read as 0.27 s with the direct sound included, 0.50 s with this skip).
_DIRECT_SKIP_S = 0.010


def estimate_t30(ir: np.ndarray, peak: int, fs: int, max_seconds: float = 2.0) -> float | None:
    """Reverberation time from the *unwindowed* response after the direct arrival (Schroeder).

    The decay curve is integrated from the end of the window backwards, the direct sound is skipped (see
    [_DIRECT_SKIP_S]) and the slope is a least-squares fit over the -5 .. -25 dB region, extrapolated to
    60 dB (`T20 * 3`). The fit region, not a fixed -35 dB point, is what makes this robust: a recording's
    noise floor ends the decay in a plateau, and a plateau has no slope - a threshold-based T30 would
    happily extrapolate the plateau and report a church for a dry room.

    Returns None ("unknown") when the curve never decays 25 dB within the window, when the -5 dB point is
    not reached at all, or when the fitted region is not straight (R² < 0.98) - three ways of saying "this
    recording cannot answer the question".
    """
    start = max(0, peak) + int(round(_DIRECT_SKIP_S * fs))
    end = min(ir.size, peak + int(round(max_seconds * fs)))
    if end - start < int(0.05 * fs):
        return None
    energy = ir[start:end] ** 2
    total = np.cumsum(energy[::-1])[::-1]
    if total[0] <= 0.0:
        return None
    decay_db = 10.0 * np.log10(np.maximum(total / total[0], 1e-30))
    times = np.arange(decay_db.size) / fs

    # A decay curve ends in the noise plateau of the deconvolution. If that plateau already sits inside
    # the fit region, the "slope" is the noise, and the extrapolation turns a traffic jam into a cathedral
    # (measured: 5.3-5.5 s on a 0.9 s room at 20 dB SNR). The fit region must therefore be well clear of it.
    plateau = float(np.median(decay_db[int(0.75 * decay_db.size):]))
    if plateau > -25.0 - NOISE_CLEARANCE_DB:
        return None
    fit = (decay_db <= -5.0) & (decay_db >= -25.0)
    if fit.sum() < 8:
        return None
    t, y = times[fit], decay_db[fit]
    if t[-1] - t[0] < 0.02:
        return None
    slope, intercept = np.polyfit(t, y, 1)
    if slope >= 0.0:
        return None
    r2 = 1.0 - float(np.sum((y - (slope * t + intercept)) ** 2)) / max(float(np.sum((y - np.mean(y)) ** 2)), 1e-30)
    if r2 < 0.98:
        return None
    return float(-60.0 / slope)
def analyze(
    recording: np.ndarray,
    stim: Stimulus,
    mic_compensation_db: Callable[[float], float] = lambda f: 0.0,
    config: AnalyzerConfig | None = None,
) -> MeasurementResult:
    """`MeasurementAnalyzer.analyze` (sweep + pink noise, dynamic smoothing, normalisation, warnings)."""
    config = config or AnalyzerConfig()
    plan = stim.plan
    fs = plan.sample_rate
    if recording.size < plan.sweep_start + plan.sweep_length:
        raise ValueError("RECORDING_TOO_SHORT")

    dc = float(np.mean(recording))
    signal = recording - dc if abs(dc) > DC_REMOVAL_THRESHOLD else recording
    peak = float(np.max(np.abs(signal)))
    if peak < 1e-4:
        raise ValueError("NO_SIGNAL")

    grid = log_grid(points_per_octave=config.points_per_octave)
    ir = deconvolve_sweep(signal, stim)
    max_lag = min(int(round(config.max_latency_seconds * fs)), ir.size - 1)
    latency = locate_ir_peak(ir, max_lag, fs)
    sweep_power = windowed_ir_power(ir, latency, fs, config)
    sweep_bin_hz = fs / ((sweep_power.size - 1) * 2)

    period = plan.pink_period
    pink_bin_hz = fs / period
    analysis_start = plan.pink_start + latency + period // 2
    analysis_end = min(plan.pink_start + plan.pink_length - plan.fade_in_samples + latency, recording.size)
    periods = max((analysis_end - analysis_start) // period, 1)
    signal_power = np.zeros(period // 2 + 1)
    for p in range(periods):
        signal_power += power_spectrum(signal, analysis_start + p * period, period, period) / periods

    noise_start = int(round(0.05 * fs))
    noise_head = power_spectrum(signal, noise_start, period, period)
    tail_start = recording.size - period
    tail_earliest = plan.sweep_start + plan.sweep_length + latency + int(round(config.ir_post_seconds * fs))
    noise_tail = power_spectrum(signal, tail_start, period, period) if tail_start >= tail_earliest else None
    noise_power = noise_head if noise_tail is None else (noise_head + noise_tail) / 2

    k = np.arange(signal_power.size)
    f = k * pink_bin_hz
    ref = stim.pink_period_power
    pink_transfer = np.where(
        (ref > 0) & (f >= plan.sweep_start_hz) & (f <= plan.sweep_end_hz),
        np.maximum(signal_power - noise_power, signal_power * 0.01) / np.where(ref > 0, ref, 1.0),
        0.0,
    )

    f_lo, f_hi = plan.sweep_start_hz, plan.sweep_end_hz

    def sweep_grid(profile: SmoothingProfile) -> np.ndarray:
        return power_to_db(spectrum_to_grid(sweep_power, sweep_bin_hz, grid, profile, valid_low_hz=f_lo, valid_high_hz=f_hi))

    def pink_grid(profile: SmoothingProfile) -> np.ndarray:
        return power_to_db(spectrum_to_grid(pink_transfer, pink_bin_hz, grid, profile, valid_low_hz=f_lo, valid_high_hz=f_hi))

    sweep_raw_db, sweep_smooth_db = sweep_grid(RAW), sweep_grid(config.smoothing)
    sweep_third_db = sweep_grid(THIRD_OCTAVE)
    pink_raw_db, pink_smooth_db = pink_grid(RAW), pink_grid(config.smoothing)
    pink_third_db = pink_grid(THIRD_OCTAVE)
    sig_third = spectrum_to_grid(signal_power, pink_bin_hz, grid, THIRD_OCTAVE, valid_low_hz=f_lo, valid_high_hz=f_hi)
    noise_head_db = power_to_db(spectrum_to_grid(noise_head, pink_bin_hz, grid, THIRD_OCTAVE, valid_low_hz=f_lo, valid_high_hz=f_hi))
    if noise_tail is not None:
        noise_tail_db = power_to_db(spectrum_to_grid(noise_tail, pink_bin_hz, grid, THIRD_OCTAVE, valid_low_hz=f_lo, valid_high_hz=f_hi))
        noise_db = np.maximum(noise_tail_db, noise_head_db)
    else:
        noise_db = noise_head_db
    snr = power_to_db(sig_third) - noise_db

    def combine(sweep: np.ndarray, pink: np.ndarray) -> np.ndarray:
        w = np.array([blend_weight(s, config.sweep_weight) for s in snr])
        return np.where(w >= 1.0, sweep, w * sweep + (1.0 - w) * pink)

    raw_db = combine(sweep_raw_db, pink_raw_db)
    smooth_db = combine(sweep_smooth_db, pink_smooth_db)
    third_db = combine(sweep_third_db, pink_third_db)

    mic = np.array([mic_compensation_db(fq) for fq in grid])
    for arr in (raw_db, smooth_db, third_db, sweep_smooth_db, pink_smooth_db):
        arr -= mic
    norm = band_mean(grid, smooth_db, 300.0, 3000.0)
    for arr in (raw_db, smooth_db, third_db, sweep_smooth_db, pink_smooth_db):
        arr -= norm

    warnings = []
    if peak >= 0.98:
        warnings.append("CLIPPING")
    band_idx = (grid >= 100.0) & (grid <= 10000.0)
    snr_band = np.sort(snr[band_idx])
    median_snr = float(snr_band[snr_band.size // 2]) if snr_band.size else 0.0
    if median_snr < 15.0:
        warnings.append("LOW_SNR")
    mismatch_mask = (grid >= 200.0) & (grid <= 5000.0)
    if float(np.mean(np.abs(sweep_smooth_db[mismatch_mask] - pink_smooth_db[mismatch_mask]))) > 3.0:
        warnings.append("SWEEP_NOISE_MISMATCH")
    latency_ms = latency * 1000.0 / fs
    if latency_ms > 400.0:
        warnings.append("HIGH_LATENCY")
    if latency >= max_lag - int(round(LATENCY_EDGE_SECONDS * fs)):
        warnings.append("LATENCY_OUT_OF_RANGE")

    # W3/W4: both come out of the same deconvolution - no extra playback, no extra capture time.
    distortion = harmonic_distortion(
        ir, latency, plan.sweep_length / fs, plan.sweep_start_hz, plan.sweep_end_hz, fs)
    if distortion is not None:
        if distortion["thd_pct"] >= DISTORTION_WARN_PCT:
            warnings.append("SPEAKER_DISTORTION")
        harmonics = distortion["harmonics_db"]
        if 3 in harmonics and 5 in harmonics and harmonics[5] - harmonics[3] > RATTLE_H5_MINUS_H3_DB:
            warnings.append("DRIVER_RATTLE")
    rt60 = estimate_t30(ir, latency, fs)
    if rt60 is not None and rt60 > REVERB_WARN_S:
        warnings.append("REVERBERANT_ROOM")

    return MeasurementResult(
        sample_rate=fs,
        frequencies=grid,
        raw_db=raw_db,
        smoothed_db=smooth_db,
        third_octave_db=third_db,
        sweep_smoothed_db=sweep_smooth_db,
        pink_smoothed_db=pink_smooth_db,
        snr_db=snr,
        latency_ms=latency_ms,
        input_peak=peak,
        median_snr_db=median_snr,
        warnings=warnings,
        normalization_db=norm,
        thd_pct=None if distortion is None else distortion["thd_pct"],
        harmonics_db={} if distortion is None else distortion["harmonics_db"],
        h3_minus_h2_db=None if distortion is None else distortion["h3_minus_h2_db"],
        room_rt60_s=rt60,
    )
