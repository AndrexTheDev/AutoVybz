# Speaker classes: rescue mode & device speakers

Next to the target curve (taste) and the listening environment (room physics, see
`docs/OUTDOOR.md`), the wizard asks **what kind of loudspeaker** is being calibrated.
The optimal correction strategy differs fundamentally:

| | External speaker (`STANDARD`) | Weak / cheap speaker (`RESCUE`) | This phone / tablet (`DEVICE`) |
|---|---|---|---|
| Goal | precise correction | mask the flaws, accept the limits | calibrate the built-in speakers with the built-in mic |
| Smoothing (auto) | dynamic | strong (⅔–1½ oct) | strong |
| Max boost | user (6 dB default) | user (unchanged) | **4 dB cap** |
| Max cut | 12 dB | **16 dB** | 12 dB |
| Broad boosts only | outdoors only | **always (1 oct median)** | **always** |
| Excursion ramp | 1 oct (1.5 outdoors) | ≥ 1.5 oct | **2 oct** |
| Correction top | 16 kHz | 16 kHz | **12 kHz** |
| Roll-off scan | from 200 Hz, clamp ≤ 160 Hz | from 400 Hz, ≤ 350 Hz | from 1.2 kHz, ≤ 900 Hz |
| Protective high pass | – | **yes** | **yes** |
| Limiter forced | outdoors only | **yes** | **yes** |
| Pre-selected target | user default | Harman | Harman |
| Measurement | 70 % volume, 1–3 positions | 70 %, 1–3 positions | **50 %, 1 position, phone mic** |

All of this lives in `SpeakerClassTuning` (nullable overrides, `null` = inherit) and is
merged with the environment tuning by `CorrectionPlanner` – `SpeakerClassTuningTest`
proves that `STANDARD` reproduces the classic settings exactly.

## The protective high pass

Both new strategies add a 2nd-order high pass (12 dB/oct, Q = 0.707) at the speaker's
measured −6 dB bass point (`AutoEqRenderer.detectLowLimit` result ÷ 1.25). It replaces
one fitted EQ band, so presets stay within the promised 5/10 filters, and it is skipped
below 35 Hz (a speaker that plays almost full-range does not need one). The fitter is
told about the pass: its slope *inside* the correction band is subtracted from the
desired curve, so the fitted bands + pass jointly reach the target at the low edge
instead of cutting it twice (−1.5 dB at the low limit). Outside the band nothing is
compensated – and since the excursion guard was already applied to the desired curve,
the compensation can never push the electrical signal beyond the guard's budget.

Why it matters more than any tonal correction on weak hardware: deep bass that a small
driver cannot reproduce still costs cone excursion. That excursion produces rattling,
port noise and intermodulation distortion **across the whole audible band** – the
signature "cheap" sound. Removing the inaudible signal electrically buys clean headroom
in the band the speaker *can* play (this is also what OEM speaker-tuning DSP does).

## Rescue: what makes cheap speakers sound cheap

Typical defects of a €15 Bluetooth box, and the countermeasure:

1. **Boomy one-note bass** (driver + plastic enclosure resonance 150–400 Hz) and
   **honky/shouting mids** (0.8–4 kHz), often 6–12 dB tall.
   → cuts allowed to −16 dB; strong smoothing so the *tonal shape* is corrected
   instead of every comb wiggle of a ragged measurement.
2. **Rattling on bass-heavy music** from signal far below its capability.
   → protective high pass; wider excursion ramp (1.5 octaves) so bass boosts fade in
   slowly above the roll-off; limiter always armed (weak speakers are played loud).
3. **Dull or harsh top end.** → broad-only boosts (1-octave median rule, same as
   outdoors) so missing treble can be lifted without chasing interference notches;
   the pre-selected Harman target adds a gentle downward tilt that forgives harshness.
4. Boosts keep the user's limit on purpose: on a weak amp/driver, boost headroom is
   paid for with distortion and total loudness (pre-gain). Cutting the boom lets the
   user turn the speaker up instead – the better trade.

Physics stays physics: no real bass extension appears. What appears is balance and
clarity at higher usable volume.

## Device speakers: measuring a phone with itself

The phone plays the stimulus through its own speakers and records it with its own
(bottom) microphone. The wizard requires the built-in route
(`RouteClassifier.Decision.builtinSpeakerActive`) – any connected Bluetooth/USB/cable
output blocks with an explanation – and the classic "no external speaker" block offers
this mode as a shortcut.

Limitations, honestly stated, and how the strategy is built around them:

* **Near field ≠ listening position.** The mic sits 1–15 cm from the drivers, which
  exaggerates low frequencies and picks up structure-borne chassis vibration below
  ~300 Hz. Above ~12 kHz chassis diffraction dominates the reading.
  → Correction is band-limited to [measured roll-off … 12 kHz], boosts capped at
  4 dB with a 2-octave excursion ramp, strong smoothing (tonal shape only).
* **Tiny drivers have no bass to correct** (phones roll off ~400–900 Hz, tablets
  ~150–350 Hz – measured per device by the widened roll-off scan, never guessed).
  → The protective high pass at the measured −6 dB point is the main quality lever:
  it removes the excursion load that makes phone speakers distort on music, freeing
  headroom for the audible band. The limiter stays armed for max-volume playback.
* **The mic is centimetres from the speaker.** At the usual 70 % measurement volume
  the mic could clip and the chassis rattle. → 50 % volume (SNR is enormous at that
  distance anyway), device lying screen-up on a table (a hand muffles and shakes it),
  usual case left on (the profile should match everyday use). Loud phones can clip
  the mic even at 50 %: after a clipped run the wizard automatically starts the next
  one quieter (−30 % steps, floor 20 % – `VolumeMath.reduceAfterClipping`) instead of
  deterministically clipping again.
* **Stereo phones** (earpiece + bottom speaker): the bottom mic mostly hears the
  bottom speaker; the earpiece arrives ~0.4 ms later and quieter, which would comb
  the raw curve. The strong smoothing averages that ripple out, and the (stereo-linked)
  EQ corrects the shared tonal shape – the dominant defects (LF distortion load,
  midrange shout) are common to both drivers anyway.
* **OEM speaker DSP is level-dependent** (dynamic bass/loudness processing). The
  calibration is exact at the measured volume and a good approximation around it;
  the help text says so.
* Fixed geometry ⇒ exactly one measurement position; multi-position averaging and
  the indoor/outdoor choice do not apply and are hidden.

The result is a normal AutoVybz profile (stored with `speakerClass = DEVICE`, tagged
in the Pro list). Like every calibration it applies until the user selects another
profile – e.g. when an external speaker is connected again.

## Roll-off detection (shared improvement)

`detectLowLimit` now takes scan-start/clamp parameters (defaults preserve the external
behaviour) and finds the −6 dB crossing as the top of the below-threshold run that
reaches the bottom of the grid. An isolated room dip above the true roll-off is no
longer mistaken for it; the previous scan-down remains as fallback when measurement
noise lifts the deepest bins. Its reference band starts at
max(300 Hz, 0.75 × scan start): with the wide tiny-speaker scan the classic 300 Hz edge
would sit inside the roll-off it is supposed to find, depressing the reference and
biasing the result low (phone speaker: 519 Hz detected instead of the true 665 Hz;
scaled reference: 644 Hz – verified against a numerical model; a median pre-smoothing
of the detection curve was also evaluated and dropped for showing no benefit).
Guarded by `AutoEqRendererTest`
(`lowLimitIgnoresIsolatedDipAboveRollOff`, `lowLimitWideScanFindsPhoneSpeakerRollOff`,
`protectiveHighPass*`, `phoneSpeakerRenderIsBandLimitedAndProtected`) and end-to-end by
`WizardFlowE2ETest` (`rescueCalibration_addsProtectiveHighPass`,
`deviceSpeakerCalibration_usesBuiltinRoute`).
