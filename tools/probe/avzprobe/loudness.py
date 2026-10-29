"""ITU-R BS.1770-4 loudness: K-weighting, gated integrated LUFS, true peak.

The app promises "better, not quieter", and until now both the probe and the app justified that with a
proxy: an A-weighted long-term spectrum (`weighted_tonal_error`, `_a_weighted_level`). A-weighting is a
*rough* stand-in for the K-weighting of BS.1770 (it has no +4 dB head shelf, and its low-frequency roll-off
is 12 dB/octave rather than the RLB filter's gentler one), so it cannot answer the question the promise
actually makes: does the corrected programme come out at the same *integrated loudness*?

This module implements the standard properly, at any sample rate:

* **K-weighting** as two biquads - the head-shelf pre-filter (f0 = 1681.97 Hz, +4 dB, Q = 0.707) and the
  RLB high-pass (f0 = 38.14 Hz, Q = 0.5003) - both derived from the analog prototypes of the standard with
  the bilinear transform (prewarped with `tan(pi f0 / fs)`), which reproduces the coefficients BS.1770 prints
  for 48 kHz to the last digit (checked in `check_parity.py`, pinned by `IntegratedLoudnessTest`).
* **Gating** exactly as specified: 400 ms blocks with 75 % overlap, absolute gate at -70 LUFS, relative
  gate at -10 LU below the gated mean.
* **True peak** (BS.1770 Annex 2) through the 4x oversampler the app already has.

Mono only: the probe measures one microphone. For a multi-channel programme the standard sums the channel
powers with weights (1.0 for L/R, 1.41 for C, 0 for LFE); that sum is documented here but not implemented,
because nothing in this repository has two channels to sum.
"""

from __future__ import annotations

import math

import numpy as np

from . import audio


# The standard's offset: an otherwise perfect meter reads 0 dBFS 1 kHz as -0.691 LUFS.
LOUDNESS_OFFSET_DB = -0.691


# ------------------------------------------------------------------ K-weighting --

# The BS.1770 filters are *analog prototypes* mapped with the bilinear transform, not RBJ cookbook
# biquads: using RBJ here gets the 1 kHz gain wrong by 0.25 dB (0.44 instead of 0.69 dB), and with it every
# LUFS number. These are the parameters of the standard, evaluated with `tan(pi f0 / fs)` prewarping - the
# same derivation libebur128 uses, and it reproduces the coefficient table BS.1770 prints for 48 kHz.
PRE_F0_HZ = 1681.974450955533
PRE_GAIN_DB = 3.999843853973347
PRE_Q = 0.7071752369554196
RLB_F0_HZ = 38.13547087602444
RLB_Q = 0.5003270373238773


def shelf(sample_rate: float) -> tuple[float, ...]:
    """BS.1770 pre-filter (head shelf), normalised to a0 = 1."""
    k = math.tan(math.pi * PRE_F0_HZ / sample_rate)
    vh = 10.0 ** (PRE_GAIN_DB / 20.0)
    vb = vh ** 0.4996667741545416
    a0 = 1.0 + k / PRE_Q + k * k
    b0 = (vh + vb * k / PRE_Q + k * k) / a0
    b1 = 2.0 * (k * k - vh) / a0
    b2 = (vh - vb * k / PRE_Q + k * k) / a0
    a1 = 2.0 * (k * k - 1.0) / a0
    a2 = (1.0 - k / PRE_Q + k * k) / a0
    return b0, b1, b2, a1, a2


def rlb(sample_rate: float) -> tuple[float, ...]:
    """BS.1770 RLB filter (high pass), normalised to a0 = 1."""
    k = math.tan(math.pi * RLB_F0_HZ / sample_rate)
    a0 = 1.0 + k / RLB_Q + k * k
    return 1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / RLB_Q + k * k) / a0


def k_weighting_gain_db(frequency_hz: float, sample_rate: float = audio.SAMPLE_RATE) -> float:
    """Magnitude of the K-weighting at one frequency (dB) - the number the promise is judged with."""
    z = np.exp(-1j * 2.0 * math.pi * frequency_hz / sample_rate)
    b0, b1, b2, a1, a2 = shelf(sample_rate)
    head = (b0 + b1 * z + b2 * z * z) / (1.0 + a1 * z + a2 * z * z)
    b0, b1, b2, a1, a2 = rlb(sample_rate)
    high = (b0 + b1 * z + b2 * z * z) / (1.0 + a1 * z + a2 * z * z)
    return 20.0 * math.log10(max(float(np.abs(head * high)), 1e-12))


def amplitude_for(lufs: float, sample_rate: float = audio.SAMPLE_RATE) -> float:
    """Amplitude of a 1 kHz sine that reads [lufs] (inverse of the anchor the tests use)."""
    gain = k_weighting_gain_db(1000.0, sample_rate)
    return math.sqrt(2.0 * 10.0 ** ((lufs - LOUDNESS_OFFSET_DB - gain) / 10.0))


def k_weight(x: np.ndarray, sample_rate: float = audio.SAMPLE_RATE) -> np.ndarray:
    """K-weighted signal (both biquads, zero initial state)."""
    y = x
    for coefficients in (shelf(sample_rate), rlb(sample_rate)):
        b0, b1, b2, a1, a2 = coefficients
        out = np.zeros_like(y)
        # Direct form I, written out: the filter is short and this is 30 lines faster than lfiltering.
        x1 = x2 = y1 = y2 = 0.0
        for i in range(y.size):
            v = y[i]
            o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2, x1 = x1, v
            y2, y1 = y1, o
            out[i] = o
        y = out
    return y


# ------------------------------------------------------------------- integrated --

ABSOLUTE_GATE_LUFS = -70.0
RELATIVE_GATE_LU = -10.0
BLOCK_SECONDS = 0.400
BLOCK_OVERLAP = 0.75


def integrated_lufs(x: np.ndarray, sample_rate: float = audio.SAMPLE_RATE) -> float | None:
    """Gated integrated loudness of a mono signal (BS.1770-4), `None` when everything is gated out."""
    weighted = k_weight(x, sample_rate)
    block = int(round(BLOCK_SECONDS * sample_rate))
    hop = int(round(block * (1.0 - BLOCK_OVERLAP)))
    if weighted.size < block:
        return None
    starts = range(0, weighted.size - block + 1, hop)
    # Energy of each block, `y_i` in the standard's notation (mean square of the K-weighted signal).
    powers = np.array([float(np.mean(weighted[s: s + block] ** 2)) for s in starts])
    if powers.size == 0:
        return None
    # Loudness of each block. The -0.691 dB offset belongs to the *loudness* domain: averaging the
    # loudness values in the power domain (below) already carries it, so applying it again at the end would
    # shift every result by exactly 0.691 dB - the classic way to get "close but not the standard" numbers.
    block_lufs = np.array([LOUDNESS_OFFSET_DB + 10.0 * math.log10(max(p, 1e-20)) for p in powers])
    # Absolute gate, then the relative gate computed on what survived it - the second gate is the reason a
    # quiet intro cannot drag a loud chorus down.
    keep = block_lufs > ABSOLUTE_GATE_LUFS
    if not np.any(keep):
        return None
    gated_loudness = _mean_loudness(block_lufs[keep])
    keep &= block_lufs > gated_loudness + RELATIVE_GATE_LU
    if not np.any(keep):
        return None
    return _mean_loudness(block_lufs[keep])


def _mean_loudness(block_lufs: np.ndarray) -> float:
    """Energy mean of loudness values: the standard averages *powers*, not decibels."""
    return 10.0 * math.log10(max(float(np.mean(10.0 ** (block_lufs / 10.0))), 1e-20))


def loudness_difference_lu(before: np.ndarray, after: np.ndarray,
                           sample_rate: float = audio.SAMPLE_RATE) -> float | None:
    """`IntegratedLoudness.loudnessDifferenceLu`: how much louder [after] is than [before], in LU."""
    a = integrated_lufs(before, sample_rate)
    b = integrated_lufs(after, sample_rate)
    return None if a is None or b is None else b - a


def true_peak_dbtp(x: np.ndarray) -> float:
    """BS.1770 Annex 2 true peak through the app's own 4x oversampler (see `audio.true_peak_polyphase_db`)."""
    return audio.true_peak_polyphase_db(x)
