"""Playback chain models: the loudspeaker, the room and the microphone.

A probe needs a *system under test*: something that turns the app's sweep into a
microphone signal that looks like a real measurement. The models here are deliberately
explicit (each term is a documented physical effect) and they are complex-valued, so
the impulse responses are real: minimum-phase-like driver behaviour, real comb filters
from a reflection, real high-frequency air loss.

None of this claims to be a measured speaker. It is a reproducible stand-in that
exercises the *app* with plausible physics - the real music, however, is not a model.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from . import dsp
from .dsp import HIGH_PASS, PEAK, PeqBand


def _biquad_h(band: PeqBand, f: np.ndarray, sample_rate: float) -> np.ndarray:
    """Complex H(e^{jw}) of one RBJ band on the frequency vector [f]."""
    b0, b1, b2, a0, a1, a2 = dsp.coefficients(band, sample_rate)
    z = np.exp(-1j * 2.0 * math.pi * f / sample_rate)
    return (b0 + b1 * z + b2 * z * z) / (a0 + a1 * z + a2 * z * z)


@dataclass
class SpeakerModel:
    """One playback scenario.

    f3_hz           - -6 dB point of the driver's bass roll-off (2nd order, Butterworth)
    box_hz/box_db   - ported-box tuning bump (the classic "one-note boom" of a small box)
    presence_hz/presence_db/presence_q - cone break-up / horn loading in the mids
    hf_roll_hz      - 2nd order low pass (cone mass and beaming, plus air loss outdoors)
    tilt_db_per_oct - overall voicing tilt
    reflections     - (delay_s, level) pairs: comb filtering from the nearest surface
    """

    name: str
    description: str
    f3_hz: float
    box_hz: float = 0.0
    box_db: float = 0.0
    presence_hz: float = 0.0
    presence_db: float = 0.0
    presence_q: float = 1.0
    hf_roll_hz: float = 20000.0
    tilt_db_per_oct: float = 0.0
    reflections: tuple[tuple[float, float], ...] = ()
    extra_bands: tuple[PeqBand, ...] = field(default_factory=tuple)

    def response(self, f: np.ndarray, sample_rate: float = dsp.DEFAULT_SAMPLE_RATE) -> np.ndarray:
        h = np.ones(f.size, dtype=complex)
        h *= _biquad_h(PeqBand(HIGH_PASS, self.f3_hz, 0.0, 0.707), f, sample_rate)
        if self.box_db:
            h *= _biquad_h(PeqBand(PEAK, self.box_hz, self.box_db, 2.2), f, sample_rate)
        if self.presence_db:
            h *= _biquad_h(PeqBand(PEAK, self.presence_hz, self.presence_db, self.presence_q), f, sample_rate)
        if self.hf_roll_hz < 20000.0:
            # 2nd order low pass = high pass coefficients with an inverted ... use a peak-free LP via
            # the standard RBJ low pass expressed as a high-shelf-free form.
            h *= _lowpass_h(self.hf_roll_hz, f, sample_rate)
        if self.tilt_db_per_oct:
            h *= 10.0 ** (self.tilt_db_per_oct * np.log2(f / 1000.0) / 20.0)
        for delay_s, level in self.reflections:
            h *= 1.0 + level * np.exp(-1j * 2.0 * math.pi * f * delay_s)
        for band in self.extra_bands:
            h *= _biquad_h(band, f, sample_rate)
        return h

    def magnitude_db(self, f: np.ndarray, sample_rate: float = dsp.DEFAULT_SAMPLE_RATE) -> np.ndarray:
        return 20.0 * np.log10(np.maximum(np.abs(self.response(f, sample_rate)), 1e-9))


def _lowpass_h(fc: float, f: np.ndarray, sample_rate: float) -> np.ndarray:
    w = 2.0 * math.pi * fc / sample_rate
    cw, sw = math.cos(w), math.sin(w)
    alpha = sw / (2.0 * 0.707)
    b0 = (1.0 - cw) / 2.0
    b1 = 1.0 - cw
    b2 = (1.0 - cw) / 2.0
    a0 = 1.0 + alpha
    a1 = -2.0 * cw
    a2 = 1.0 - alpha
    z = np.exp(-1j * 2.0 * math.pi * f / sample_rate)
    return (b0 + b1 * z + b2 * z * z) / (a0 + a1 * z + a2 * z * z)


# ---------------------------------------------------------------- positions --

# Room modes that any normal room has between 40 and 160 Hz. They are *not* part of a speaker model -
# they exist because the room exists, and their depth at the microphone depends on where the phone is.
ROOM_MODES_HZ = (43.0, 51.0, 62.0, 74.0, 88.0, 104.0, 122.0, 145.0)

# How far a "position" may be from the nominal one, in metres (30-50 cm is what the wizard asks for).
POSITION_SPREAD_M = 0.4
SPEED_OF_SOUND = 343.0


def position_variants(model: SpeakerModel, count: int, spread_m: float = POSITION_SPREAD_M,
                      seed: int = 0xA7, room: bool = True) -> list[SpeakerModel]:
    """`count` plausible listening positions around [model]'s nominal one.

    A single position is a lie that flatters every dip-filling correction: the deep notches an EQ must
    *not* chase are position-specific, so a probe with one truth cannot tell them from real defects
    (docs/AUDIO_ROADMAP.md, finding C8). Moving the phone by 30-50 cm changes two things:

    * **the room modes** (`room=True`, i.e. indoors) - each mode's coupling to the microphone changes, so
      its gain moves by several dB and can even invert. Modelled as [ROOM_MODES_HZ] with quality factor
      8-12: the "one-note boom" every small room has, and the main reason a notch must not be boosted;
    * **the comb filters** - a reflection arrives from a different path length, so its delay shifts by up
      to `spread_m / c` (0.4 m = 1.2 ms) and its level changes a little. Outdoors this is the *only*
      position dependence (ground reflection, no modes), hence `room=False` there.

    `count == 1` returns the plain model: the documented single-position truth, so every number the
    probe produced before this feature stays comparable. With `count > 1` the nominal spot also carries
    modes (a room has them at every position) - that is the point, not an inconsistency.
    """
    if count <= 1:
        return [model]
    rng = np.random.default_rng(seed)
    base_gains = 6.0 * np.array([-1.0, 1.0, -0.8, 1.2, -1.2, 0.9, -0.6, 0.7])
    # Offsets in the room, symmetric around the nominal spot (x = towards the speaker, y = sideways).
    offsets = [(0.0, 0.0)]
    for i in range(1, count):
        angle = 2.0 * math.pi * (i - 1) / max(count - 1, 1)
        radius = spread_m * (1.0 if i % 2 else 0.65)
        offsets.append((radius * math.cos(angle), radius * math.sin(angle)))

    variants = []
    for x, y in offsets:
        coupling = (0.6 * x + 0.4 * y) / max(spread_m, 1e-6)
        # A reflection path changes by up to `spread_m` for *any* movement that is not exactly radial to
        # the surface, so the comb notches move by up to spread_m/c (0.4 m = 1.2 ms) - the reason a
        # narrow dip at 300-600 Hz is a property of the spot, not of the speaker.
        reflections = tuple(
            (max(delay + rng.uniform(-spread_m, spread_m) / SPEED_OF_SOUND, 0.0002),
             level * (1.0 + 0.3 * rng.uniform(-1.0, 1.0)))
            for delay, level in model.reflections
        )
        modes: tuple = ()
        if room:
            # Coupling changes the mode gain *and* adds position-specific scatter: measured across 40 cm
            # a single mode easily moves by 10-15 dB, which is exactly why its nulls cannot be filled.
            gains = base_gains * (1.0 + 0.9 * coupling) + rng.normal(0.0, 4.0, ROOM_MODES_HZ.__len__())
            modes = tuple(
                PeqBand(PEAK, mode, float(np.clip(gain, -14.0, 14.0)), 8.0 + 3.0 * rng.random())
                for mode, gain in zip(ROOM_MODES_HZ, gains)
            )
        variants.append(
            SpeakerModel(
                name=f"{model.name}@{x:+.2f},{y:+.2f}",
                description=f"{model.description} (position {x:+.2f}/{y:+.2f} m)",
                f3_hz=model.f3_hz, box_hz=model.box_hz, box_db=model.box_db,
                presence_hz=model.presence_hz, presence_db=model.presence_db, presence_q=model.presence_q,
                hf_roll_hz=model.hf_roll_hz, tilt_db_per_oct=model.tilt_db_per_oct,
                reflections=reflections, extra_bands=tuple(model.extra_bands) + modes,
            )
        )
    return variants


# The scenarios. Levels are typical of the product categories AutoVybz is used with.
SCENARIOS: dict[str, SpeakerModel] = {
    "bt-minibox": SpeakerModel(
        name="bt-minibox",
        description="Small Bluetooth box on a shelf: rolls off at 95 Hz, boomy port at 70 Hz, "
                    "shouty horn-loaded mids, closed treble.",
        f3_hz=95.0, box_hz=70.0, box_db=5.0, presence_hz=1600.0, presence_db=4.5, presence_q=1.4,
        hf_roll_hz=13000.0, tilt_db_per_oct=-0.3,
        reflections=((0.0042, 0.35),),
    ),
    "party-box": SpeakerModel(
        name="party-box",
        description="Bigger outdoor party box: 55 Hz, strong 120 Hz thump, ragged 2.5 kHz horn, "
                    "air loss and a ground reflection at 3 ms.",
        f3_hz=55.0, box_hz=120.0, box_db=6.0, presence_hz=2500.0, presence_db=-3.0, presence_q=1.1,
        hf_roll_hz=11000.0, tilt_db_per_oct=-0.5,
        reflections=((0.0031, 0.45),),
    ),
    "phone-speaker": SpeakerModel(
        name="phone-speaker",
        description="A phone's own speaker measured with its own mic at 5 cm: no bass below 550 Hz, "
                    "chassis resonance at 900 Hz, harsh 3 kHz, nothing above 14 kHz.",
        f3_hz=550.0, box_hz=900.0, box_db=6.0, presence_hz=3000.0, presence_db=5.0, presence_q=1.2,
        hf_roll_hz=14000.0, tilt_db_per_oct=0.0,
    ),
    "laptop": SpeakerModel(
        name="laptop",
        description="Laptop speakers under the screen: 240 Hz, nasal 1.2 kHz, thin treble.",
        f3_hz=240.0, box_hz=300.0, box_db=3.0, presence_hz=1200.0, presence_db=3.5, presence_q=1.6,
        hf_roll_hz=16000.0, tilt_db_per_oct=-0.2,
    ),
}

# How noisy the listening situation is (SNR of the measurement, i.e. of the sweep at the mic).
NOISE_PROFILES: dict[str, dict] = {
    "quiet-room": {"snr_db": 42.0, "lf_rumble_db": 12.0},
    "street": {"snr_db": 24.0, "lf_rumble_db": 20.0},
    "party": {"snr_db": 18.0, "lf_rumble_db": 22.0},
}


def minimum_phase(magnitude: np.ndarray) -> np.ndarray:
    """Minimum-phase spectrum with the same magnitude: exp(cepstrum folded onto causal times).

    A loudspeaker is (very nearly) a minimum-phase system. Building the IR as `irfft(magnitude)`
    instead would produce a *zero-phase* response whose energy is split equally into negative and
    positive time; a linear convolution keeps only the causal half, so the played bass was up to
    +6.6 dB louder than the modelled response (20 Hz on a 60 Hz mini box) and every later
    before/after comparison was made against a transfer function the app never saw.
    """
    n = (magnitude.size - 1) * 2
    log_mag = np.log(np.maximum(np.abs(magnitude), 1e-12))
    cepstrum = np.fft.irfft(log_mag, n)
    fold = np.zeros(n)
    fold[0] = 1.0
    fold[1: n // 2] = 2.0
    fold[n // 2] = 1.0
    return np.exp(np.fft.rfft(cepstrum * fold, n)[: magnitude.size])


def impulse_response(model: SpeakerModel, n_fft: int, sample_rate: float = dsp.DEFAULT_SAMPLE_RATE) -> np.ndarray:
    """Causal (minimum-phase) impulse response of the model on an [n_fft] grid."""
    freqs = np.fft.rfftfreq(n_fft, 1.0 / sample_rate)
    freqs = np.maximum(freqs, 1e-3)
    h = minimum_phase(model.response(freqs, sample_rate))
    return np.fft.irfft(h, n_fft)


def convolve(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    """Linear convolution through the FFT (n_fft = next power of two above len(x)+len(ir))."""
    n = dsp.next_power_of_two(x.size + ir.size)
    y = np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n), n)
    return y[: x.size]


def playback(ir: np.ndarray, x: np.ndarray, gain: float = 1.0) -> np.ndarray:
    return gain * convolve(x, ir)
