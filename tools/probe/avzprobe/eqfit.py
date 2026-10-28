"""1:1 port of the AutoVybz Auto-EQ stack: target curve, band fitter, renderer, gain staging.

Mirrors `measurement/eq/{TargetCurve,PeqFitter,AutoEqRenderer,PreampPlanner,AntiClipping}.kt`
and the class/environment tuning in `measurement/{SpeakerClass,ListeningEnvironment}.kt`.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field, replace
from typing import Sequence

import numpy as np

from . import dsp
from .dsp import HIGH_PASS, HIGH_SHELF, LOW_SHELF, PEAK, PeqBand, Grid, band_mean

# ------------------------------------------------------------------ target curves --

SMART, FLAT, BASS_BOOST, HARMAN, CUSTOM = "SMART", "FLAT", "BASS_BOOST", "HARMAN", "CUSTOM"


@dataclass
class CustomTarget:
    bass_gain_db: float = 4.0
    bass_corner_hz: float = 105.0
    treble_gain_db: float = 0.0
    treble_corner_hz: float = 8000.0
    tilt_db_per_octave: float = 0.0
    points: Sequence[tuple[float, float]] = ()


def _shelf(kind: str, fc: float, gain: float, f: float) -> float:
    if gain == 0.0:
        return 0.0
    return dsp.magnitude_db(dsp.coefficients(PeqBand(kind, fc, gain, 0.707)), dsp.phi(f))


def _tilt(db_per_oct: float, f: float) -> float:
    return db_per_oct * math.log(f / 1000.0) / math.log(2.0)


class TargetCurve:
    def __init__(self, kind: str = SMART, custom: CustomTarget | None = None):
        self.kind = kind
        self.custom = custom or CustomTarget()

    def level_at(self, f: float) -> float:
        if self.kind == FLAT:
            return 0.0
        if self.kind == SMART:
            return _shelf(LOW_SHELF, 105.0, 3.5, f)
        if self.kind == BASS_BOOST:
            return _shelf(LOW_SHELF, 110.0, 6.5, f)
        if self.kind == HARMAN:
            return _shelf(LOW_SHELF, 105.0, 4.0, f) + _tilt(-0.8, f)
        if self.kind == CUSTOM:
            c = self.custom
            if len(c.points) >= 2:
                pts = sorted(c.points)
                return dsp.interpolate_log(np.array([p[0] for p in pts]), np.array([p[1] for p in pts]), f)
            return (_shelf(LOW_SHELF, c.bass_corner_hz, c.bass_gain_db, f)
                    + _shelf(HIGH_SHELF, c.treble_corner_hz, c.treble_gain_db, f)
                    + _tilt(c.tilt_db_per_octave, f))
        raise ValueError(self.kind)

    def sample(self, grid: np.ndarray) -> np.ndarray:
        v = np.array([self.level_at(f) for f in grid])
        return v - band_mean(grid, v, 300.0, 3000.0)


# ----------------------------------------------------------------------- fitter --

@dataclass
class FitConstraints:
    max_bands: int = 10
    max_boost_db: float = 6.0
    max_cut_db: float = 12.0
    min_frequency: float = 20.0
    max_frequency: float = 16000.0
    min_q: float = 0.4
    max_q: float = 4.0
    allow_shelves: bool = True
    no_boost_below_hz: float = 0.0


def nelder_mead(f, x0: np.ndarray, step: np.ndarray, max_iterations: int = 160) -> np.ndarray:
    """`NelderMead.minimize` - same simplex moves and the same 1e-7 stop rule."""
    dim = x0.size
    pts = [x0.copy() for _ in range(dim + 1)]
    for i in range(1, dim + 1):
        pts[i][i - 1] += step[i - 1]
    vals = [f(p) for p in pts]
    for _ in range(max_iterations):
        order = sorted(range(dim + 1), key=lambda i: vals[i])
        pts = [pts[i] for i in order]
        vals = [vals[i] for i in order]
        if abs(vals[dim] - vals[0]) < 1e-7:
            return pts[0]
        centroid = np.zeros(dim)
        for i in range(dim):
            centroid += pts[i] / dim

        def along(t: float) -> np.ndarray:
            return centroid + t * (pts[dim] - centroid)

        xr = along(-1.0)
        fr = f(xr)
        if fr < vals[0]:
            xe = along(-2.0)
            fe = f(xe)
            if fe < fr:
                pts[dim], vals[dim] = xe, fe
            else:
                pts[dim], vals[dim] = xr, fr
        elif fr < vals[dim - 1]:
            pts[dim], vals[dim] = xr, fr
        else:
            xc = along(-0.5) if fr < vals[dim] else along(0.5)
            fc = f(xc)
            if fc < min(fr, vals[dim]):
                pts[dim], vals[dim] = xc, fc
            else:
                for i in range(1, dim + 1):
                    pts[i] = pts[0] + 0.5 * (pts[i] - pts[0])
                    vals[i] = f(pts[i])
    best = min(range(dim + 1), key=lambda i: vals[i])
    return pts[best]


# Upper bound on the number of residual extrema offered to the optimiser per greedy step.
MAX_PEAK_CANDIDATES = 3
# A candidate must be at least this large to be worth a band (dB) ...
CANDIDATE_MIN_DB = 0.5
# ... and this large to be worth offering as an *alternative* to the worst residual point.
CANDIDATE_SECONDARY_MIN_DB = 1.0
# Bands closer than this (octaves) are duplicates and are merged after the refinement passes.
MERGE_DISTANCE_OCT = 0.28
# ... as long as dropping one does not increase the loss by more than this fraction ...
MERGE_LOSS_TOLERANCE = 0.001
# ... nor make the worst un-fitted gap more than this much bigger (dB).
MERGE_MAX_ERROR_GROWTH_DB = 0.05

# Greedy acceptance: a band has to buy this fraction of the *current* error, but at least ...
GREEDY_MIN_GAIN_RATIO = 0.015
GREEDY_MIN_GAIN_FLOOR_DB2 = 0.005


class PeqFitter:
    def __init__(self, grid: Grid, desired: np.ndarray, weights: np.ndarray, constraints: FitConstraints):
        self.grid = grid
        self.desired = np.asarray(desired, dtype=float)
        self.weights = np.asarray(weights, dtype=float)
        self.c = constraints
        self.n = grid.frequencies.size
        self.weight_sum = max(float(self.weights.sum()), 1e-9)
        assert self.desired.size == self.n and self.weights.size == self.n

    # -- loss ---------------------------------------------------------------
    def loss(self, fixed: np.ndarray, candidate: PeqBand | None) -> float:
        cand = self.grid.response(candidate) if candidate is not None else None
        total = fixed + (cand if cand is not None else 0.0)
        err = float(np.sum(self.weights * (self.desired - total) ** 2))
        boost_limit = self.c.max_boost_db + 0.25
        cut_limit = -(self.c.max_cut_db + 0.5)
        penalty = 0.0
        penalty += float(np.sum(np.clip(total - boost_limit, 0, None) ** 2))
        if self.c.no_boost_below_hz > 0:
            low = (self.grid.frequencies < self.c.no_boost_below_hz) & (total > 0.5)
            penalty += float(np.sum((total[low] - 0.5) ** 2))
        penalty += float(np.sum(np.clip(cut_limit - total, 0, None) ** 2))
        return err / self.weight_sum + 10.0 * penalty / self.n

    # -- helpers ------------------------------------------------------------
    def _weighted_mean(self, v: np.ndarray, lo: float, hi: float) -> float:
        mask = (self.grid.frequencies >= lo) & (self.grid.frequencies <= hi)
        if not mask.any():
            return 0.0
        w = self.weights[mask]
        s = float(np.sum(w))
        return float(np.sum(w * v[mask]) / s) if s > 0 else 0.0

    def _freq_bounds(self, kind: str) -> tuple[float, float]:
        c = self.c
        if kind == PEAK:
            return c.min_frequency, c.max_frequency
        if kind == LOW_SHELF:
            lo = max(c.min_frequency, 30.0)
            return lo, max(400.0, lo)
        if kind == HIGH_SHELF:
            return min(2000.0, c.max_frequency), max(2500.0, c.max_frequency)
        return c.min_frequency, max(1000.0, c.min_frequency)

    def _q_bounds(self, kind: str) -> tuple[float, float]:
        return (self.c.min_q, self.c.max_q) if kind == PEAK else (0.5, 1.0)

    def _clamp(self, b: PeqBand) -> PeqBand:
        f_lo, f_hi = self._freq_bounds(b.type)
        q_lo, q_hi = self._q_bounds(b.type)
        return PeqBand(
            b.type,
            min(max(b.frequency, f_lo), f_hi),
            min(max(b.gain_db, -self.c.max_cut_db), self.c.max_boost_db),
            min(max(b.q, q_lo), q_hi),
        )

    def _to_vector(self, b: PeqBand) -> np.ndarray:
        return np.array([math.log2(b.frequency), b.gain_db, math.log(b.q)])

    def _from_vector(self, kind: str, x: np.ndarray) -> PeqBand:
        return self._clamp(PeqBand(kind, 2.0 ** x[0], x[1], math.exp(x[2])))

    def _optimise(self, start: PeqBand, fixed: np.ndarray) -> PeqBand:
        x = nelder_mead(
            f=lambda v: self.loss(fixed, self._from_vector(start.type, v)),
            x0=self._to_vector(start),
            step=np.array([0.25, 1.5, 0.35]),
            max_iterations=160,
        )
        return self._from_vector(start.type, x)

    # -- candidates ---------------------------------------------------------
    def _candidates(self, residual: np.ndarray) -> list[PeqBand]:
        out: list[PeqBand] = []
        f = self.grid.frequencies
        # Up to MAX_PEAK_CANDIDATES *distinct* residual extrema (worst first, at least a third of an
        # octave apart). Offering only the single worst point made the greedy follow one lobe at a
        # time and stop with a broad 2.3 dB error still unfitted at 587 Hz while 6 of 10 bands were
        # unused; several candidates let the optimiser pick the lobe that actually pays off.
        picked: list[float] = []
        while len(picked) < MAX_PEAK_CANDIDATES:
            idx, best = -1, 0.0
            for i in range(self.n):
                if f[i] < self.c.min_frequency or f[i] > self.c.max_frequency:
                    continue
                if any(abs(math.log2(f[i] / pf)) < 1.0 / 3.0 for pf in picked):
                    continue
                v = self.weights[i] * abs(residual[i])
                if v > best:
                    best, idx = v, i
            if idx < 0:
                break
            # The first candidate only has to be audible; the alternatives must be worth the extra
            # Nelder-Mead run (two more candidates triple the CPU of a greedy step).
            gate = CANDIDATE_MIN_DB if not picked else CANDIDATE_SECONDARY_MIN_DB
            if abs(residual[idx]) <= gate:
                break
            picked.append(float(f[idx]))
            g0 = residual[idx]
            sign = 1.0 if g0 > 0 else -1.0
            l = idx
            while l > 0 and math.copysign(1.0, residual[l - 1]) == sign and abs(residual[l - 1]) > abs(g0) / 2:
                l -= 1
            r = idx
            while r < self.n - 1 and math.copysign(1.0, residual[r + 1]) == sign and abs(residual[r + 1]) > abs(g0) / 2:
                r += 1
            bw = max(math.log2(f[r] / f[l]), 1.0 / 6.0)
            q = math.sqrt(2.0 ** bw) / (2.0 ** bw - 1.0)
            out.append(self._clamp(PeqBand(PEAK, f[idx], g0, q)))
        if self.c.allow_shelves:
            low_lo = self.c.min_frequency
            low_hi = max(150.0, low_lo + 100.0)
            low = self._weighted_mean(residual, low_lo, low_hi)
            if abs(low) > 0.5:
                out.append(self._clamp(PeqBand(LOW_SHELF, 120.0, low, 0.7)))
            high = self._weighted_mean(residual, 5000.0, self.c.max_frequency)
            if abs(high) > 0.5:
                out.append(self._clamp(PeqBand(HIGH_SHELF, 6000.0, high, 0.7)))
        return out

    # -- main ---------------------------------------------------------------
    def fit(self) -> list[PeqBand]:
        bands: list[PeqBand] = []
        fixed = np.zeros(self.n)
        current_loss = self.loss(fixed, None)
        for _ in range(self.c.max_bands):
            residual = self.desired - fixed
            # The acceptance threshold follows the *remaining* error. Measured with it frozen at the
            # initial loss (1.5 % of 6.4 = 0.096 dB^2) the greedy stopped after 4 bands although the
            # next band still removed 0.060 dB^2 and 2.3 dB of the plan were unaccounted for.
            min_gain = max(current_loss * GREEDY_MIN_GAIN_RATIO, GREEDY_MIN_GAIN_FLOOR_DB2)
            best_band, best_loss = None, current_loss
            for cand in self._candidates(residual):
                opt = self._optimise(cand, fixed)
                l = self.loss(fixed, opt)
                if l < best_loss - min_gain:
                    best_loss, best_band = l, opt
            if best_band is None:
                break
            bands.append(best_band)
            self.grid.response(best_band, fixed, accumulate=True)
            current_loss = best_loss
        for _ in range(2):
            for i in range(len(bands)):
                others = np.zeros(self.n)
                for j, b in enumerate(bands):
                    if j != i:
                        self.grid.response(b, others, accumulate=True)
                opt = self._optimise(bands[i], others)
                if self.loss(others, opt) < self.loss(others, bands[i]):
                    bands[i] = opt
        bands = self._merge_duplicates(bands)
        return self._finish(bands)

    def _merge_duplicates(self, bands: list[PeqBand]) -> list[PeqBand]:
        """Drop the weaker of two bands closer than [MERGE_DISTANCE_OCT] that add nothing.

        The refinement moves adjacent bands towards each other (mini box: PK 123 Hz +3.7 and PK 133 Hz
        +0.9 - two biquads doing one biquad's work). Removing the weaker one only when the loss barely
        changes keeps the correction identical while freeing an engine band.
        """
        out = list(bands)
        # Guard against the original fit, not against the previous step: four harmless-looking 0.15 dB
        # merges add up (laptop: 0.11 -> 0.38 dB RMS while no single step looked bad).
        baseline_max = float(np.max(np.abs(self.desired - self.response_of(out))))
        changed = True
        while changed and len(out) > 1:
            changed = False
            order = sorted(range(len(out)), key=lambda j: out[j].frequency)
            for k in range(len(order) - 1):
                i = order[k]
                a, b = out[i], out[order[k + 1]]
                if abs(math.log2(b.frequency / a.frequency)) >= MERGE_DISTANCE_OCT:
                    continue
                weaker = i if abs(a.gain_db) < abs(b.gain_db) else order[k + 1]
                trial = [x for j, x in enumerate(out) if j != weaker]
                fixed = np.zeros(self.n)
                for x in trial:
                    self.grid.response(x, fixed, accumulate=True)
                # Both a relative and an absolute guard: on a nearly flat speaker the weighted loss is
                # tiny, so 0.5 % of it would allow a visibly worse fit (laptop: 0.11 -> 0.38 dB RMS).
                max_after = float(np.max(np.abs(self.desired - fixed)))
                if (self.loss(fixed, None) <= self.loss_of(out) * (1.0 + MERGE_LOSS_TOLERANCE)
                        and max_after <= baseline_max + MERGE_MAX_ERROR_GROWTH_DB):
                    out = trial
                    changed = True
                    break
        return out

    def response_of(self, bands: list[PeqBand]) -> np.ndarray:
        fixed = np.zeros(self.n)
        for x in bands:
            self.grid.response(x, fixed, accumulate=True)
        return fixed

    def loss_of(self, bands: list[PeqBand]) -> float:
        fixed = np.zeros(self.n)
        for x in bands:
            self.grid.response(x, fixed, accumulate=True)
        return self.loss(fixed, None)

    @staticmethod
    def _finish(bands: list[PeqBand]) -> list[PeqBand]:
        return sorted([b for b in bands if abs(b.gain_db) >= 0.2], key=lambda b: b.frequency)


# ---------------------------------------------------------------- settings / classes --

@dataclass
class AutoEqSettings:
    band_count: int = 10
    max_boost_db: float = 6.0
    max_cut_db: float = 12.0
    cut_trust: float = 1.0
    cut_trust_low_hz: float = 0.0
    cut_trust_low: float = 1.0
    high_limit_hz: float = 16000.0
    auto_low_limit: bool = True
    manual_low_limit_hz: float = 40.0
    headroom_db: float = 0.5
    loudness_make_up: bool = True
    max_make_up_db: float = 6.0
    preamp_headroom_allowance_db: float = 0.0
    sample_rate: float = dsp.DEFAULT_SAMPLE_RATE
    min_snr_db: float = 10.0
    full_snr_db: float = 16.0
    broad_boost_octaves: float | None = None
    excursion_guard_octaves: float = 1.0
    low_limit_scan_hz: float = 200.0
    low_limit_max_hz: float = 160.0
    protective_high_pass: bool = False
    protective_high_pass_hz: float | None = None
    high_boost_cap_db: float | None = None
    high_boost_cap_start_hz: float = 6000.0


@dataclass
class EnvironmentTuning:
    smoothing: dsp.SmoothingProfile
    sweep_weight: float
    max_boost_db: float
    broad_boost_octaves: float | None
    excursion_guard_octaves: float
    force_limiter: bool
    recommended_positions: int


INDOOR, OUTDOOR = "INDOOR", "OUTDOOR"

# Indoor excursion ramp (octaves above the detected bass limit before a boost is fully allowed).
# 1.0 was 2.5x the driver's -6 dB point and cost 6 dB at 104-122 Hz on the mini box (3 % of the
# programme energy) for protection the driver does not need above its own roll-off.
INDOOR_EXCURSION_GUARD_OCT = 0.5


def environment_tuning(env: str, user_smoothing: dsp.SmoothingProfile = dsp.DYNAMIC, user_max_boost_db: float = 6.0) -> EnvironmentTuning:
    if env == INDOOR:
        return EnvironmentTuning(user_smoothing, 0.5, user_max_boost_db, None, INDOOR_EXCURSION_GUARD_OCT, False, 1)
    return EnvironmentTuning(
        dsp.STRONG if user_smoothing is dsp.DYNAMIC else user_smoothing,
        0.7,
        min(user_max_boost_db, 3.0),
        1.0,
        1.5,
        True,
        3,
    )


@dataclass
class SpeakerClassTuning:
    smoothing: dsp.SmoothingProfile | None = None
    max_boost_cap_db: float | None = None
    max_cut_db: float | None = None
    high_limit_hz: float | None = None
    broad_boost_octaves: float | None = None
    excursion_guard_octaves: float | None = None
    low_limit_scan_hz: float | None = None
    low_limit_max_hz: float | None = None
    protective_high_pass: bool = False
    protective_high_pass_hz: float | None = None
    high_boost_cap_db: float | None = None
    high_boost_cap_start_hz: float | None = None
    cut_trust: float | None = None
    cut_trust_low_hz: float | None = None
    cut_trust_low: float | None = None
    force_limiter: bool = False
    suggested_target: str | None = None
    single_position: bool = False


STANDARD, RESCUE, DEVICE = "STANDARD", "RESCUE", "DEVICE"

SPEAKER_CLASS: dict[str, SpeakerClassTuning] = {
    # A full-size speaker is measured through the phone mic like everything else, but above ~10 kHz the
    # profile is an estimate and programme material carries 0.0-0.1 % of its energy there: a 6 dB treble
    # "correction" buys nothing audible and costs 6 dB of master level (PreampPlanner). Cap it at 3 dB.
    STANDARD: SpeakerClassTuning(high_boost_cap_db=3.0, high_boost_cap_start_hz=10000.0),
    RESCUE: SpeakerClassTuning(
        smoothing=dsp.STRONG, max_cut_db=16.0, max_boost_cap_db=4.0, broad_boost_octaves=1.0,
        excursion_guard_octaves=1.5, low_limit_scan_hz=400.0, low_limit_max_hz=350.0,
        protective_high_pass=True, protective_high_pass_hz=150.0, force_limiter=True,
        high_boost_cap_db=2.0, high_limit_hz=14000.0, suggested_target=HARMAN,
    ),
    DEVICE: SpeakerClassTuning(
        smoothing=dsp.DEVICE, max_boost_cap_db=4.0, high_limit_hz=12000.0, broad_boost_octaves=1.0,
        excursion_guard_octaves=2.0, low_limit_scan_hz=1200.0, low_limit_max_hz=900.0,
        protective_high_pass=True, protective_high_pass_hz=60.0, high_boost_cap_db=2.0,
        high_boost_cap_start_hz=6000.0, force_limiter=True, cut_trust=0.75, cut_trust_low_hz=400.0,
        cut_trust_low=0.5, suggested_target=HARMAN, single_position=True,
    ),
}


def smoothing_for(user: dsp.SmoothingProfile, env: EnvironmentTuning, cls: SpeakerClassTuning) -> dsp.SmoothingProfile:
    if user is dsp.DYNAMIC:
        return cls.smoothing or env.smoothing
    return env.smoothing


def force_limiter(env: EnvironmentTuning, cls: SpeakerClassTuning) -> bool:
    return env.force_limiter or cls.force_limiter


def auto_eq_settings(band_count: int, env: EnvironmentTuning, cls: SpeakerClassTuning) -> AutoEqSettings:
    """`CorrectionPlanner.autoEqSettings`."""
    return AutoEqSettings(
        band_count=band_count,
        max_boost_db=min(env.max_boost_db, cls.max_boost_cap_db) if cls.max_boost_cap_db is not None else env.max_boost_db,
        max_cut_db=cls.max_cut_db if cls.max_cut_db is not None else 12.0,
        high_limit_hz=cls.high_limit_hz if cls.high_limit_hz is not None else 16000.0,
        broad_boost_octaves=cls.broad_boost_octaves if cls.broad_boost_octaves is not None else env.broad_boost_octaves,
        excursion_guard_octaves=max(env.excursion_guard_octaves, cls.excursion_guard_octaves)
        if cls.excursion_guard_octaves is not None else env.excursion_guard_octaves,
        low_limit_scan_hz=cls.low_limit_scan_hz if cls.low_limit_scan_hz is not None else 200.0,
        low_limit_max_hz=cls.low_limit_max_hz if cls.low_limit_max_hz is not None else 160.0,
        protective_high_pass=cls.protective_high_pass,
        protective_high_pass_hz=cls.protective_high_pass_hz,
        high_boost_cap_db=cls.high_boost_cap_db,
        high_boost_cap_start_hz=cls.high_boost_cap_start_hz or 6000.0,
        cut_trust=min(max(cls.cut_trust if cls.cut_trust is not None else 1.0, 0.1), 1.0),
        cut_trust_low_hz=cls.cut_trust_low_hz or 0.0,
        cut_trust_low=min(max(cls.cut_trust_low if cls.cut_trust_low is not None else 1.0, 0.1), 1.0),
        loudness_make_up=True,
        max_make_up_db=6.0,
        preamp_headroom_allowance_db=LIMITER_HEADROOM_ALLOWANCE_DB if force_limiter(env, cls) else 0.0,
    )


# ------------------------------------------------------------------ gain staging --

MAX_MAKE_UP_DB = 6.0
LIMITER_HEADROOM_ALLOWANCE_DB = 4.0
MIN_PREAMP_DB = -24.0
_LOUDNESS_GRID = dsp.log_grid(40.0, 16000.0, 3)


def a_weighting_db(f: float) -> float:
    if f <= 0 or not math.isfinite(f):
        return -200.0
    f2 = f * f
    num = 12194.0 ** 2 * f2 * f2
    den = (f2 + 20.6 ** 2) * math.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194.0 ** 2)
    return 20.0 * math.log10(num / den) + 2.0


def _programme_tilt_db(f: float) -> float:
    return -3.0 * math.log(f / 1000.0) / math.log(2.0)


_LOUDNESS_WEIGHTS = np.array([10.0 ** ((a_weighting_db(f) + _programme_tilt_db(f)) / 10.0) for f in _LOUDNESS_GRID])


def loudness_db(frequencies: np.ndarray, response_db: np.ndarray) -> float:
    r = dsp.resample(frequencies, response_db, _LOUDNESS_GRID)
    num = float(np.sum(_LOUDNESS_WEIGHTS * 10.0 ** (r / 10.0)))
    den = float(np.sum(_LOUDNESS_WEIGHTS))
    return 10.0 * math.log10(num / den) if den > 0 else 0.0


def loudness_delta_db(frequencies: np.ndarray, measured_db: np.ndarray, correction_db: np.ndarray) -> float:
    predicted = measured_db + correction_db
    return loudness_db(frequencies, predicted) - loudness_db(frequencies, measured_db)


def _round_down(db: float) -> float:
    return 0.0 if not math.isfinite(db) else math.floor(db * 10.0 + 1e-9) / 10.0


@dataclass
class PreampPlan:
    preamp_db: float
    make_up_db: float
    loudness_delta_db: float
    peak_db: float
    clipping_safe_db: float


def preamp_plan(
    frequencies: np.ndarray,
    measured_db: np.ndarray,
    correction_db: np.ndarray,
    headroom_db: float = 0.5,
    max_make_up_db: float = MAX_MAKE_UP_DB,
    headroom_allowance_db: float = 0.0,
    loudness_make_up: bool = True,
    peak_db: float | None = None,
) -> PreampPlan:
    peak = float(np.max(correction_db)) if peak_db is None else peak_db
    clipping_safe = _round_down(-(peak + headroom_db) if peak > 0.0 else 0.0)
    # Hard static bound: with a full-scale input the chain may not leave this. It *credits* corrections
    # that only cut (peak < 0), because a 3 dB cut really does leave 3 dB of headroom - that is where a
    # cut-heavy device correction gets its loudness back. Everything above it is the limiter's job, and
    # `LIMITER_HEADROOM_ALLOWANCE_DB` is what the limiter was *measured* to absorb.
    ceiling = _round_down(-(peak + headroom_db))
    delta = loudness_delta_db(frequencies, measured_db, correction_db) if (loudness_make_up and frequencies.size > 8) else 0.0
    allowance = max(headroom_allowance_db, 0.0)
    if loudness_make_up and allowance > 0.0:
        upper = ceiling + min(allowance, max(max_make_up_db, 0.0))
        preamp = _round_down(min(max(-delta, clipping_safe), upper))
        preamp = max(preamp, MIN_PREAMP_DB)
    else:
        preamp = clipping_safe
    return PreampPlan(
        preamp_db=preamp,
        make_up_db=max(preamp - clipping_safe, 0.0),
        loudness_delta_db=delta,
        peak_db=peak,
        clipping_safe_db=clipping_safe,
    )


_DENSE_GRID = dsp.log_grid(points_per_octave=24)


def max_boost_db(bands: Sequence[PeqBand], sample_rate: float = dsp.DEFAULT_SAMPLE_RATE) -> float:
    if not bands:
        return 0.0
    return float(np.max(Grid(_DENSE_GRID, sample_rate).response_many(bands)))


# ------------------------------------------------------------------------ renderer --

LOW_LIMIT_SAFETY = 1.25
MIN_PROTECTIVE_HP_HZ = 35.0
MAX_PROTECTIVE_HP_HZ = 1000.0
PROTECTIVE_HP_Q = 0.707


HP_COMPENSATION_LIMIT_DB = 6.0


def protective_high_pass_hz(low_limit_hz: float) -> float | None:
    corner = low_limit_hz / LOW_LIMIT_SAFETY
    return min(corner, MAX_PROTECTIVE_HP_HZ) if corner >= MIN_PROTECTIVE_HP_HZ else None


def protective_pass_for(low_limit_hz: float, class_corner_hz: float | None) -> float | None:
    """`AutoEqRenderer.protectivePassFor`: the class corner is a cap, the measurement has the last word."""
    roll_off = low_limit_hz / LOW_LIMIT_SAFETY
    corner = protective_high_pass_hz(low_limit_hz) if class_corner_hz is None else min(class_corner_hz, roll_off)
    return None if corner is None or corner < MIN_PROTECTIVE_HP_HZ else min(corner, MAX_PROTECTIVE_HP_HZ)


def reliability(snr: float, minimum: float, full: float) -> float:
    return min(max((snr - minimum) / max(full - minimum, 1e-6), 0.0), 1.0)


def median_log(f: np.ndarray, v: np.ndarray, octaves: float) -> np.ndarray:
    factor = 2.0 ** (octaves / 2.0)
    lo = hi = 0
    out = np.empty(v.size)
    for i in range(v.size):
        while lo < f.size and f[lo] < f[i] / factor:
            lo += 1
        while hi < f.size and f[hi] <= f[i] * factor:
            hi += 1
        window = np.sort(v[lo:max(hi, lo + 1)])
        m = window.size
        out[i] = window[m // 2] if m % 2 == 1 else (window[m // 2 - 1] + window[m // 2]) / 2
    return out


def detect_low_limit(frequencies: np.ndarray, measured_db: np.ndarray, scan_from_hz: float = 200.0,
                     min_hz: float = 25.0, max_hz: float = 160.0) -> float:
    ref = band_mean(frequencies, measured_db, max(300.0, scan_from_hz * 0.75), 3000.0)
    threshold = ref - 6.0
    scan_idx = -1
    for i in range(frequencies.size):
        if frequencies[i] <= scan_from_hz:
            scan_idx = i
    if scan_idx < 0:
        return min_hz
    run_top = -1
    while run_top + 1 <= scan_idx and measured_db[run_top + 1] < threshold:
        run_top += 1
    if run_top >= 0:
        return min(max(frequencies[run_top] * LOW_LIMIT_SAFETY, min_hz), max_hz)
    i = scan_idx
    while i >= 0:
        if measured_db[i] < threshold:
            return min(max(frequencies[i] * LOW_LIMIT_SAFETY, min_hz), max_hz)
        i -= 1
    return min_hz


def excursion_guard(f: float, low_limit: float, octaves: float = 1.0) -> float:
    t = min(max(math.log(f / low_limit) / math.log(2.0) / max(octaves, 0.1), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def range_weight(f: float, lo: float, hi: float) -> float:
    fade_oct = 0.5
    o = max(math.log(lo / f) / math.log(2.0), math.log(f / hi) / math.log(2.0))
    if o <= 0:
        return 1.0
    if o >= fade_oct:
        return 0.0
    return 0.5 + 0.5 * math.cos(math.pi * o / fade_oct)


@dataclass
class AutoEqResult:
    bands: list[PeqBand]
    preamp_db: float
    low_limit_hz: float
    high_limit_hz: float
    frequencies: np.ndarray
    measured_db: np.ndarray
    target_db: np.ndarray
    desired_correction_db: np.ndarray
    filter_response_db: np.ndarray
    predicted_db: np.ndarray
    rms_error_before_db: float
    rms_error_after_db: float
    max_filter_boost_db: float
    high_pass_hz: float | None
    make_up_db: float
    loudness_delta_db: float
    clipping_safe_db: float
    noise_gated_ranges: list[tuple[float, float]] = field(default_factory=list)


def _normalise(f: np.ndarray, v: np.ndarray) -> np.ndarray:
    ref = band_mean(f, v, 300.0, 3000.0)
    return v if ref == 0.0 or not math.isfinite(ref) else v - ref


def _rms_deviation(f, a, b, lo, hi) -> float:
    mask = (f >= lo) & (f <= hi)
    if not mask.any():
        return 0.0
    d = a[mask] - b[mask]
    d = d - d.mean()
    return float(math.sqrt(float(np.mean(d * d))))


def render(
    frequencies: np.ndarray,
    measured_db: np.ndarray,
    target: TargetCurve,
    settings: AutoEqSettings | None = None,
    roll_off_db: np.ndarray | None = None,
    snr_db: np.ndarray | None = None,
) -> AutoEqResult:
    settings = settings or AutoEqSettings()
    roll_off_db = measured_db if roll_off_db is None else roll_off_db
    target_db = target.sample(frequencies)
    measured_db = _normalise(frequencies, measured_db)
    roll_off_db = _normalise(frequencies, roll_off_db)

    low_limit = (detect_low_limit(frequencies, roll_off_db, settings.low_limit_scan_hz, max_hz=settings.low_limit_max_hz)
                 if settings.auto_low_limit else min(max(settings.manual_low_limit_hz, 20.0), 500.0))
    high_limit = min(max(settings.high_limit_hz, 2000.0), 20000.0)
    high_pass_hz = protective_pass_for(low_limit, settings.protective_high_pass_hz) if settings.protective_high_pass else None

    in_range = np.array([range_weight(f, low_limit, high_limit) for f in frequencies])
    reliable = (np.array([reliability(s, settings.min_snr_db, settings.full_snr_db) for s in snr_db])
                if snr_db is not None else np.ones(frequencies.size))
    broad = median_log(frequencies, measured_db, settings.broad_boost_octaves) if settings.broad_boost_octaves else None
    cut_trust = min(max(settings.cut_trust, 0.1), 1.0)
    cut_trust_low = min(max(settings.cut_trust_low, 0.1), 1.0)

    desired = np.empty(frequencies.size)
    for i, f in enumerate(frequencies):
        region_cap = (min(settings.max_boost_db, settings.high_boost_cap_db if settings.high_boost_cap_db is not None else settings.max_boost_db)
                      if f >= settings.high_boost_cap_start_hz else settings.max_boost_db)
        boost_cap = min(settings.max_boost_db * excursion_guard(f, low_limit, settings.excursion_guard_octaves), region_cap)
        raw = float(target_db[i] - measured_db[i])
        if broad is not None and raw > 0:
            raw = min(raw, max(0.0, float(target_db[i] - broad[i])))
        ct = cut_trust_low if (settings.cut_trust_low_hz > 0.0 and f < settings.cut_trust_low_hz) else cut_trust
        scaled = raw * ct if raw < 0.0 else raw
        desired[i] = min(max(scaled, -settings.max_cut_db * ct), boost_cap) * in_range[i] * reliable[i]

    weights = 0.3 + 0.7 * in_range * reliable
    grid = Grid(frequencies, settings.sample_rate)
    constraints = FitConstraints(
        max_bands=max(settings.band_count - 1, 1) if high_pass_hz is not None else settings.band_count,
        max_boost_db=settings.max_boost_db,
        max_cut_db=settings.max_cut_db,
        min_frequency=max(low_limit * 0.8, 20.0),
        max_frequency=high_limit,
        min_q=0.4,
        max_q=2.5 if settings.band_count <= 5 else 4.0,
        allow_shelves=True,
        no_boost_below_hz=low_limit / 2,
    )
    high_pass = PeqBand(HIGH_PASS, high_pass_hz, 0.0, PROTECTIVE_HP_Q) if high_pass_hz is not None else None
    if high_pass is not None:
        hp = grid.response(high_pass)
        fit_desired = desired - np.clip(hp, -HP_COMPENSATION_LIMIT_DB, HP_COMPENSATION_LIMIT_DB) * in_range * reliable
    else:
        fit_desired = desired
    fitted = PeqFitter(grid, fit_desired, weights, constraints).fit()
    bands = sorted(fitted + ([high_pass] if high_pass is not None else []), key=lambda b: b.frequency)
    response = grid.response_many(bands)
    predicted = measured_db + response

    peak_boost = max_boost_db(bands, settings.sample_rate)
    plan = preamp_plan(
        frequencies=frequencies,
        measured_db=measured_db,
        correction_db=response,
        headroom_db=settings.headroom_db,
        max_make_up_db=settings.max_make_up_db,
        headroom_allowance_db=settings.preamp_headroom_allowance_db,
        loudness_make_up=settings.loudness_make_up,
        peak_db=peak_boost,
    )
    return AutoEqResult(
        bands=bands,
        preamp_db=plan.preamp_db,
        low_limit_hz=low_limit,
        high_limit_hz=high_limit,
        frequencies=frequencies,
        measured_db=measured_db,
        target_db=target_db,
        desired_correction_db=desired,
        filter_response_db=response,
        predicted_db=predicted,
        rms_error_before_db=_rms_deviation(frequencies, measured_db, target_db, low_limit, high_limit),
        rms_error_after_db=_rms_deviation(frequencies, predicted, target_db, low_limit, high_limit),
        max_filter_boost_db=peak_boost,
        high_pass_hz=high_pass_hz,
        make_up_db=plan.make_up_db,
        loudness_delta_db=plan.loudness_delta_db,
        clipping_safe_db=plan.clipping_safe_db,
    )
