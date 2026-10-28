# Speaker classes: rescue mode & device speakers

Next to the target curve (taste) and the listening environment (room physics, see
`docs/OUTDOOR.md`), the wizard asks **what kind of loudspeaker** is being calibrated.
The optimal correction strategy differs fundamentally:

| | External speaker (`STANDARD`) | Weak / cheap speaker (`RESCUE`) | This phone / tablet (`DEVICE`) |
|---|---|---|---|
| Goal | precise correction | mask the flaws, accept the limits | calibrate the built-in speakers with the built-in mic |
| Smoothing (auto) | dynamic | strong (⅔–1½ oct) | **near-field** (`SmoothingProfile.DEVICE`: 1 oct ≤ 400 Hz, 1/3 oct ≥ 700 Hz) |
| Max boost | user (6 dB default) | **4 dB cap** (min of user 6 dB and class 4 dB) | **4 dB cap** |
| Max cut | 12 dB | **16 dB** | 12 dB |
| Cut trust | 100 % | 100 % | **75 %** (near field overstates presence peaks) |
| Broad boosts only | outdoors only | **always (1 oct median)** | **always** |
| Excursion ramp | 1 oct (1.5 outdoors) | ≥ 1.5 oct | **2 oct** |
| Correction top | 16 kHz | **14 kHz** (steep roll-off region masked) | **12 kHz** |
| HF boost cap | – | **2 dB above 6 kHz** (phantom-boost guard) | **2 dB above 6 kHz** |
| Roll-off scan | from 200 Hz, clamp ≤ 160 Hz | from 400 Hz, ≤ 350 Hz | from 1.2 kHz, ≤ 900 Hz |
| Protective high pass | – | **yes** (sub-bass only, fixed 150 Hz) | **yes** (sub-bass only, fixed 60 Hz) |
| Limiter forced | outdoors only | **yes** | **yes** |
| Loudness make-up | – | **up to +6 dB** | **up to +6 dB** |
| Pre-selected target | user default | Harman | Harman |
| Measurement | 70 % volume, 1–3 positions | 70 %, 1–3 positions | **50 %, 1 position, phone mic** |

All of this lives in `SpeakerClassTuning` (nullable overrides, `null` = inherit) and is
merged with the environment tuning by `CorrectionPlanner` – `SpeakerClassTuningTest`
proves that `STANDARD` reproduces the classic settings exactly.

## The protective high pass

Both new strategies add a 2nd-order high pass (12 dB/oct, Q = 0.707). It replaces one
fitted EQ band, so presets stay within the promised 5/10 filters. The fitter is told
about the pass: its slope *inside* the correction band is subtracted from the desired
curve, so the fitted bands + pass jointly reach the target at the low edge instead of
cutting it twice (−1.5 dB at the low limit). Outside the band nothing is compensated –
and since the excursion guard was already applied to the desired curve, the compensation
can never push the electrical signal beyond the guard's budget.

The corner differs by class (`AutoEqSettings.protectiveHighPassHz`):

* **RESCUE** (external weak speaker): a fixed **150 Hz** corner. The auto-detected
  pass would sit at ~280 Hz (350 / 1.25), which cuts bass the driver actually plays
  (150–280 Hz) and makes the rescue sound thin. At 150 Hz the pass removes only the
  sub-bass the driver cannot reproduce cleanly (rattle, excursion) and costs ≤ 1 dB
  at 200 Hz. The correction can still shape the bass above 150 Hz, including the
  boomy enclosure resonance (150–400 Hz).
* **DEVICE** (phone / tablet self-measurement): a fixed **60 Hz** corner. A tiny driver's
  −6 dB point sits in the *midrange* (phone speakers roll off at 400–900 Hz), and a
  protective pass there would eat the bass the driver *does* play – the calibration
  would sound thin and quiet (the model measures −9…−18 dB at 100–200 Hz with the
  derived corner). At 60 Hz the pass removes only the sub-bass the driver cannot
  reproduce cleanly (rattle, excursion) and costs ≤ 1 dB at 100 Hz.

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
   Above ~14 kHz the driver rolls off steeply and the measurement is unreliable –
   correction is masked there (like DEVICE masks above 12 kHz). Above 6 kHz the
   self-measurement is dominated by mic profile and driver hash: boosts are capped
   at 2 dB to prevent phantom treble on cheap boxes.
4. Boosts are capped at 4 dB (class cap, min of the user's 6 dB limit and the class's
   4 dB): on a weak amp/driver, boost headroom is paid for with distortion and total
   loudness (pre-gain). The Harman bass shelf (+4 dB) is the most a cheap driver should
   ever be asked to boost. Cutting the boom lets the user turn the speaker up instead –
   the better trade.

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
  4 dB with a 2-octave excursion ramp, near-field smoothing (`SmoothingProfile.DEVICE`:
  one octave up to 400 Hz - pressure zone and chassis vibration - and 1/3 octave from
  700 Hz up, so a phone's genuinely narrow midrange/treble features stay visible instead
  of being averaged into a gentle tilt like the 1/2 octave of a boomy room), and
  cuts limited to 75 % of the measured excess (`cutTrust`): the near field overstates the
  presence region, and every dB cut there costs perceived loudness.
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
  the raw curve. The 1/3-octave near-field smoothing averages that ripple out, and the (stereo-linked)
  EQ corrects the shared tonal shape – the dominant defects (LF distortion load,
  midrange shout) are common to both drivers anyway.
* **Gain staging** — see the section above: the calibration is loudness-neutral by design,
  otherwise a correct tonal correction reads as "much quieter and less detailed".
* **OEM speaker DSP is level-dependent** (dynamic bass/loudness processing). The
  calibration is exact at the measured volume and a good approximation around it;
  the help text says so.
* Fixed geometry ⇒ exactly one measurement position; multi-position averaging and
  the indoor/outdoor choice do not apply and are hidden.

The result is a normal AutoVybz profile (stored with `speakerClass = DEVICE`, tagged
in the Pro list). Like every calibration it applies until the user selects another
profile – e.g. when an external speaker is connected again.

## Gain staging: why a correction made the phone quieter

The classic anti-clipping rule is "pre-gain = −(largest boost + 0.5 dB)". It is correct for a
correction that *boosts*, but it silently punished the most common device-speaker correction:
the ear is most sensitive between 1 and 4 kHz, so a near-field measurement of a phone speaker
shows a presence excess there, and correcting it means cutting exactly the band that carries
perceived loudness. Measured with the model used by the tests, a typical phone correction
(−1.6 … −4.3 dB of A-weighted level) combined with the old pre-gain (−3.5 dB) made playback
**3–5 dB quieter**, and because quiet means less detail at both ends of the spectrum
(equal-loudness contours), it also sounded *duller* – "the calibration takes the details away".

`PreampPlanner` implements the policy that fixes this:

1. **Clipping-safe value** (unchanged, `Plan.clippingSafeDb`): `−(peak + 0.5 dB)` if the
   correction boosts anywhere, else 0 dB. `peak` comes from `AntiClipping.maxBoostDb`, i.e. the
   analytic maximum of the whole chain (24 points/octave over 20 Hz–20 kHz, protective high pass
   included), not just the render grid.
2. **Loudness estimate**: the perceived level change of the correction is estimated on the
   *predicted* response (measurement + correction, A-weighted pink programme spectrum on a
   1/3-octave grid 40 Hz–16 kHz).
3. **Two regimes** (`preampHeadroomAllowanceDb`):
   * *Limiter armed* (device, rescue, outdoor – 4 dB allowance): the pre-gain is set
     **loudness-neutral**: `pre-gain = clamp(−ΔL, clipping-safe, clipping-safe + allowance)`.
     The corrected chain comes out as loud as the original. The legacy rule made every
     correction that boosts anything `peak + 0.5 dB` quieter – e.g. the treble restoration
     of a phone speaker cost the whole output 4.5 dB.
     The allowance used to be 6 dB with the reasoning "a 10:1 limiter maps a +6 dB over-shoot to
     −0.4 dBFS". **That was wrong on real material**: the engine's limiter has no look-ahead, so a
     percussive transient passes almost unattenuated for the length of the attack. Round 8 measured
     the result on live-programme material through the modelled chain: +0.14 dBFS output peak with
     13 clipped samples. The allowance is therefore 4 dB *and* the limiter is now a peak limiter
     (−2 dB, 20:1, 0.01 ms – `AntiClipping.DEFAULT_LIMITER`), which every probed case kept at or
     below −1.42 dBFS with zero clipped samples.
   * The static bound itself (`−(peak + headroom)`) is always applied and now also *credits* a
     correction that only cuts – a 3 dB cut really leaves 3 dB of headroom, which is where a
     cut-heavy device correction gets its loudness back instead of borrowing it from the limiter.
   * *No limiter* (every other preset, allowance 0): the pre-gain is exactly the clipping-safe
     value – bit-identical to the old behaviour, so external speakers keep the classic rule.

`MAX_MAKE_UP_DB` (6 dB) bounds the compensation on top of the allowance, and a correction that
gets louder is never pulled back down (`makeUpDb >= 0`).

The result screen shows the value (signed, e.g. `+4.3 dB`) and adds a green line whenever the
make-up is at least 0.5 dB, so a positive pre-gain does not look like a bug. `PreampPlannerTest`
pins the invariants: without an allowance the legacy value is reproduced exactly, with the limiter
armed the corrected chain comes out loudness-neutral (bounded by the allowance), and the static
over-shoot never exceeds the allowance.
`SoundComposer` moves the same make-up through style layering, and `AutoVybzPresetStore` switches
the inherited DP *auto-gain* off for AutoVybz presets — it shifts the whole curve by the largest
boost, which would otherwise undo the make-up a second time (the wizard path also re-reads the
gain-staging and limiter settings when a profile is applied to a running engine).

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
