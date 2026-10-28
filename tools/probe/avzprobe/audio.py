"""Real audio in and out: decoding with ffmpeg, WAV I/O, and music-domain measurements.

The probe is about *programme material*: what a listener actually plays. Everything here
works on long-term spectra of real recordings, not on test tones.
"""

from __future__ import annotations

import math
import os
import shutil
import subprocess
import tempfile
import wave
from dataclasses import dataclass

import numpy as np

from . import dsp

SAMPLE_RATE = 48000
THIRD_OCTAVE_BANDS = [f * 2 ** (i / 3) for f in (25.0,) for i in range(0, 3 * 10)]  # 25 Hz .. 20 kHz


def ffmpeg_path() -> str | None:
    """ffmpeg from PATH, or from the optional `imageio-ffmpeg` wheel (what the sandbox uses)."""
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg  # type: ignore

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def decode(path: str, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Decode any format ffmpeg reads into mono float64 at [sample_rate], peak-normalised to -1 dBFS."""
    exe = ffmpeg_path()
    if exe is None:
        raise RuntimeError("ffmpeg not available (pip install imageio-ffmpeg)")
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav_path = tmp.name
    try:
        subprocess.run(
            [exe, "-hide_banner", "-loglevel", "error", "-y", "-i", path,
             "-ac", "1", "-ar", str(sample_rate), "-c:a", "pcm_s16le", wav_path],
            check=True,
        )
        return read_wav(wav_path)
    finally:
        os.unlink(wav_path)


def read_wav(path: str) -> np.ndarray:
    with wave.open(path, "rb") as w:
        channels = w.getnchannels()
        width = w.getsampwidth()
        frames = w.readframes(w.getnframes())
        rate = w.getframerate()
    if width == 4:
        data = np.frombuffer(frames, dtype="<f4").astype(np.float64)
    elif width == 2:
        data = np.frombuffer(frames, dtype="<i2").astype(np.float64) / 32768.0
    else:
        raise ValueError(f"unsupported sample width {width} ({rate} Hz)")
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return data


def write_wav(path: str, x: np.ndarray, sample_rate: int = SAMPLE_RATE) -> None:
    data = np.clip(x, -1.0, 1.0)
    pcm = (data * 32767.0).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm.tobytes())


# ------------------------------------------------------------------ true peak --

TRUE_PEAK_OVERSAMPLE = 4
_TRUE_PEAK_TAPS_PER_PHASE = 24
_TRUE_PEAK_KAISER_BETA = 8.0


def _kaiser_i0(x: float) -> float:
    total, term, k = 1.0, 1.0, 1
    while k < 64:
        term *= (x / (2 * k)) ** 2
        total += term
        if term < total * 1e-17:
            break
        k += 1
    return total


def _true_peak_kernel(taps_per_phase: int = _TRUE_PEAK_TAPS_PER_PHASE,
                      beta: float = _TRUE_PEAK_KAISER_BETA,
                      oversample: int = TRUE_PEAK_OVERSAMPLE) -> np.ndarray:
    """Windowed-sinc polyphase kernel, unity DC gain per phase - the mirror of `TruePeak.kernel`."""
    n = oversample * taps_per_phase
    t = np.arange(n) - (n - 1) / 2.0
    h = np.where(np.abs(t) < 1e-12, 1.0,
                 np.sin(np.pi * t / oversample) / (np.pi * t / oversample)) / oversample
    h = h * np.kaiser(n, beta)
    for phase in range(oversample):
        h[phase::oversample] /= h[phase::oversample].sum()
    return h


def true_peak_db(x: np.ndarray, oversample: int = TRUE_PEAK_OVERSAMPLE) -> float:
    """True peak in dBTP via band-limited FFT oversampling (the exact reference).

    The app computes the same quantity with a 96-tap polyphase filter
    (`TruePeak.peakDbtp`); `true_peak_polyphase_db` is the mirror of that, and
    `pipeline_findings.py` checks the two against each other.
    """
    x = np.asarray(x, dtype=float)
    if x.size == 0:
        return float("-inf")
    n = x.size
    spec = np.fft.rfft(x)
    padded = np.zeros(n * oversample // 2 + 1, dtype=complex)
    padded[:spec.size] = spec
    up = np.fft.irfft(padded, n * oversample) * oversample
    peak = float(np.max(np.abs(up)))
    return 20.0 * math.log10(peak) if peak > 1e-12 else float("-inf")


def true_peak_polyphase_db(x: np.ndarray, taps_per_phase: int = _TRUE_PEAK_TAPS_PER_PHASE) -> float:
    """`TruePeak.peakDbtp`: the same metric with the app's cheap FIR."""
    x = np.asarray(x, dtype=float)
    if x.size <= 2 * taps_per_phase:
        peak = float(np.max(np.abs(x))) if x.size else 0.0
        return 20.0 * math.log10(peak) if peak > 1e-12 else float("-inf")
    h = _true_peak_kernel(taps_per_phase)
    n = x.size
    best = 0.0
    for phase in range(TRUE_PEAK_OVERSAMPLE):
        g = h[phase::TRUE_PEAK_OVERSAMPLE]
        acc = np.zeros(n - 2 * taps_per_phase)
        for k in range(taps_per_phase):
            acc += x[taps_per_phase - k: n - taps_per_phase - k] * g[k]
        best = max(best, float(np.max(np.abs(acc))))
    return 20.0 * math.log10(best) if best > 1e-12 else float("-inf")


def sample_peak_db(x: np.ndarray) -> float:
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    return 20.0 * math.log10(peak) if peak > 1e-12 else float("-inf")


def resample(x: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    if src_rate == dst_rate:
        return x
    n_out = int(round(x.size * dst_rate / src_rate))
    spec = np.fft.rfft(x)
    # Truncate/pad in the frequency domain (band-limited, no aliasing for downsampling)
    m = min(spec.size, n_out // 2 + 1)
    out_spec = np.zeros(n_out // 2 + 1, dtype=complex)
    out_spec[:m] = spec[:m]
    return np.fft.irfft(out_spec, n_out) * (n_out / x.size)


def limiter(
    x: np.ndarray,
    sample_rate: int = SAMPLE_RATE,
    threshold_db: float = -2.0,
    ratio: float = 20.0,
    attack_ms: float = 0.01,
    release_ms: float = 60.0,
) -> np.ndarray:
    """The app's DynamicProcessing stage (`AntiClipping.DEFAULT_LIMITER`) as a peak limiter.

    Gain computer: above [threshold_db] a sample is scaled by 1/ratio (in dB), then the gain
    reduction is smoothed with the attack / release times. Equalizer314's stage has no
    look-ahead, so a fast transient can overshoot by a few dB for the length of the attack -
    which is why the probe measures the *output* peaks instead of trusting the threshold.
    """
    eps = 1e-12
    level_db = 20.0 * np.log10(np.maximum(np.abs(x), eps))
    over = np.maximum(level_db - threshold_db, 0.0)
    reduction = -over * (1.0 - 1.0 / ratio)
    attack = math.exp(-1.0 / (max(attack_ms, 0.01) * 1e-3 * sample_rate))
    release = math.exp(-1.0 / (max(release_ms, 0.01) * 1e-3 * sample_rate))
    g = 0.0
    out = np.empty_like(x)
    for i, sample in enumerate(x):
        target = reduction[i]
        coeff = attack if target < g else release
        g = coeff * g + (1.0 - coeff) * target
        out[i] = sample * 10.0 ** (g / 20.0)
    return out


def limiter_demand_db(
    x: np.ndarray,
    threshold_db: float = -2.0,
    ratio: float = 20.0,
) -> np.ndarray:
    """Instantaneous gain reduction the limiter *asks for* at each sample (<= 0 dB).

    Vectorised on purpose: the probe measures this on ten million samples per case, and the smoothed,
    attack-limited gain of [limiter] only differs from this demand for the length of the attack - a
    detail that matters per transient, not for the long-term statistics computed here.
    """
    level_db = 20.0 * np.log10(np.maximum(np.abs(x), 1e-12))
    return -np.maximum(level_db - threshold_db, 0.0) * (1.0 - 1.0 / ratio)


# ------------------------------------------------------------------- measures --

@dataclass
class MusicStats:
    rms_db: float
    peak_db: float
    crest_db: float
    clip_ratio: float
    spectrum_db: np.ndarray  # on dsp.log_grid(48/oct) - the grid the fitter uses
    band_energy_db: np.ndarray  # 1/3-octave band levels, unweighted


def stats(x: np.ndarray, sample_rate: int = SAMPLE_RATE) -> MusicStats:
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    rms = float(np.sqrt(np.mean(x * x))) if x.size else 0.0
    clipped = int(np.sum(np.abs(x) >= 0.999))
    grid = dsp.log_grid()
    power = _long_term_power(x, sample_rate, grid)
    return MusicStats(
        rms_db=_db(rms),
        peak_db=_db(peak),
        crest_db=_db(peak) - _db(rms),
        clip_ratio=clipped / max(x.size, 1),
        spectrum_db=dsp.power_to_db(power),
        band_energy_db=np.array([_db(float(np.mean(power[(grid >= b / 2 ** (1 / 6)) & (grid <= b * 2 ** (1 / 6))])))
                                 if np.any((grid >= b / 2 ** (1 / 6)) & (grid <= b * 2 ** (1 / 6))) else -200.0
                                 for b in THIRD_OCTAVE_BANDS]),
    )


def _db(v: float) -> float:
    return 10.0 * math.log10(max(v, 1e-20))


def _long_term_power(x: np.ndarray, sample_rate: int, grid: np.ndarray) -> np.ndarray:
    """Average power spectrum over 8192-sample Hann windows, binned onto [grid] (1/48 oct)."""
    n = 8192
    if x.size < n:
        x = np.pad(x, (0, n - x.size))
    hop = n // 2
    window = np.hanning(n)
    acc = np.zeros(n // 2 + 1)
    count = 0
    for start in range(0, x.size - n + 1, hop):
        seg = x[start:start + n] * window
        spec = np.fft.rfft(seg)
        acc += (spec.real ** 2 + spec.imag ** 2)
        count += 1
    acc /= max(count, 1)
    bin_hz = sample_rate / n
    return dsp.spectrum_to_grid(acc, bin_hz, grid, dsp.RAW)


def weighted_tonal_error(
    error_db: np.ndarray, music_power: np.ndarray, grid: np.ndarray, lo: float = 40.0, hi: float = 16000.0
) -> dict[str, float]:
    """RMS of a response error, weighted by how much programme energy actually sits at each frequency.

    A 6 dB error where the music has nothing (30 Hz on a phone speaker, 19 kHz on any pop mix) is
    inaudible; the same error at 300 Hz or 3 kHz is the whole difference. This is the number the
    probe judges: "does *this* music sound right afterwards".
    """
    mask = (grid >= lo) & (grid <= hi)
    w = music_power[mask]
    e = error_db[mask]
    if not mask.any() or float(w.sum()) <= 0:
        return {"rms_weighted_db": 0.0, "max_weighted_db": 0.0, "p95_db": 0.0}
    w_norm = w / w.sum()
    rms = float(math.sqrt(float(np.sum(w_norm * e * e))))
    # 95th percentile of |error| weighted by energy: "the worst thing the listener clearly hears"
    order = np.argsort(np.abs(e))
    cum = np.cumsum(w_norm[order])
    p95 = float(abs(e[order][min(int(np.searchsorted(cum, 0.95)), e.size - 1)]))
    return {"rms_weighted_db": rms, "max_weighted_db": float(np.max(np.abs(e))), "p95_db": p95}
