# Outdoor & party mode – failure-mode analysis

AutoVybz was designed for rooms. Outdoors (garden, terrace, beach, park, a party with a Bluetooth speaker)
the acoustics are fundamentally different. A room EQ that is simply run outdoors makes the sound **worse
than no EQ at all** (see the simulation below). This document lists each failure mode systematically with
its cause, its effect, the countermeasure in the app and the test that guards it.

In the wizard the user chooses **Indoors** or **Outdoors · Party**. The choice is stored
(`AutoVybzSettings.environment`), travels with the `MeasurementRequest` and is written to the preset
(`MeasurementSnapshot.environment`, `positions`) so exports and the Pro area know the context.

## 1. Physics: what is different outdoors

| Indoors | Outdoors |
|---|---|
| Walls reflect energy back → **room gain** lifts the bass by roughly 4–8 dB | Free field (half-space over the ground): no room gain, the bass sounds thin |
| Room modes: narrow, position-dependent peaks → a good EQ target | No modes; the dominant reflection is the **ground**, which creates a comb filter (notches) |
| Background noise usually low and steady | Wind, traffic, voices, birds, cicadas, generators – loud, low-frequency and **unsteady** |
| Phone 1–2 m from the speaker | Larger distances, lower SNR; Bluetooth has no walls to bounce off → **dropouts** |
| Normal listening level | Party level: speakers run near their limits, built-in DSP compresses the bass |
| Screen readable | Direct sunlight |

Example ground reflection: speaker at 0.5 m, phone at 1.2 m height, 3 m away → path difference 0.37 m →
first notch at ≈ 470 Hz. 1 m further back (4 m) it moves to ≈ 600 Hz. One measurement position therefore
sees notches that do not exist 1 m away – and no EQ can fill a cancellation.

## 2. FMEA

S = severity (1–5), O = occurrence outdoors (1–5) before the countermeasure.

| # | Failure mode | Cause | Effect without countermeasure | S | O | Countermeasure (code) | Test |
|---|---|---|---|---|---|---|---|
| 1 | Missing bass "corrected" with large boosts | No room gain | +6…+12 dB bass boost → preamp −7 dB, loudness lost, woofer over-excursion, limiter pumping | 5 | 5 | `EnvironmentTuning.OUTDOOR`: max boost **3 dB** (`OUTDOOR_MAX_BOOST_DB`), excursion guard extended to **1.5 octaves** above the detected roll-off (`AutoEqRenderer.excursionGuard`) | `OutdoorScenarioTest`, `AutoEqRendererTest.widerExcursionGuard` |
| 2 | Ground-reflection notches boosted | Comb filter of the ground reflection | Narrow, high-Q boosts at frequencies that are only missing at that exact spot → worse everywhere else | 4 | 5 | **Broad-boost rule**: a boost may never exceed the target minus the 1-octave **running median** of the measurement (`medianLog`), so dips narrower than ≈ ½ octave cannot cause a boost; stronger smoothing (`STRONG` instead of `DYNAMIC`) | `AutoEqRendererTest.broadBoost*`, `medianLog` notch test |
| 3 | Spot-specific result | Comb filter + near-field changes | EQ only fits the phone position | 4 | 4 | **3 positions recommended** outdoors (`recommendedPositions`); spatial averaging; the wizard raises the count to 3 when "Outdoors" is selected; result card hints when only 1 position was used | `OutdoorScenarioTest` (3 spread spots) |
| 4 | Wind on the microphone | Turbulence at the mic port | Huge LF "energy" → EQ cuts the bass or (indoors) boosts noise | 5 | 4 | **SNR gating** per frequency: reliability = clamp((SNR − 10 dB)/6 dB, 0, 1) – below 10 dB SNR the target follows the measurement (no correction), fully trusted from 16 dB; **`LOW_FREQ_NOISE` warning** (noise 40–150 Hz ≥ 15 dB above 1–4 kHz *and* SNR 60–150 Hz < 15 dB); safety card: turn the mic away from the wind, use a thin cloth | `OutdoorScenarioTest` (wind), `AutoEqRendererTest.snrGating` |
| 5 | Unsteady noise (gusts, cars, voices) | Noise during the sweep differs from the noise snapshot | Wrong SNR estimate, random EQ errors | 3 | 4 | Noise captured **before and after** the stimulus, SNR uses the louder one (`max(head, tail)`); **`NOISE_UNSTEADY` warning** (mean \|head − tail\| 50 Hz–5 kHz > 6 dB at median SNR < 35 dB); sweep weighted higher (0.7) than pink noise because it rejects uncorrelated noise better | `OutdoorScenarioTest` (burst) |
| 6 | Birds, cicadas, music next door | Narrow-band HF noise | HF "peaks" cut, HF dips boosted | 3 | 3 | Same SNR gating (frequency-local); gated ranges are listed in the result ("Not corrected because of background noise: …") | `OutdoorScenarioTest` (birds) |
| 7 | Bluetooth dropouts | Range without reflecting walls | Gaps in the recording → deconvolution garbage | 4 | 3 | **`DROPOUT` detection**: ≥ 2 consecutive 10 ms frames ≥ 20 dB below the median level within the pink-noise segment; treated as severe → the wizard offers "Repeat measurement N" | `OutdoorScenarioTest` (dropout) |
| 8 | A disturbed measurement silently ends up in the average | Multi-position flow finishes automatically | One windy position spoils the average | 3 | 3 | After a disturbed position (any warning except high latency) the wizard stops on the "move" step with the warning list and a gold **repeat** button (`WizardViewModel.onRepeatLastPosition` drops the last partial result); finishing is still possible | UI flow (CI build) |
| 9 | Clipping at party level | EQ + loud music + speaker near its limit | Distortion, damaged tweeters | 5 | 4 | Outdoor result is always saved and activated with the **limiter forced on** (`EnvironmentTuning.forceLimiter`, also in the Pro area for outdoor presets); auto pre-gain | `ListeningEnvironment` unit coverage, CI build |
| 10 | Speaker's own bass compression at max volume | Built-in DSP of party speakers | Measured response depends on the volume knob | 3 | 4 | Safety card: "as loud as for the party, but **not maximum**"; help text explains why | – (user guidance) |
| 11 | Phone on the ground / wrong height | Habit | Strong boundary effect, unrepresentative curve | 3 | 3 | Outdoor check card and move-step tip: ear height, chair/table/backpack, 2–4 m away | – (user guidance) |
| 12 | Screen unreadable in sunlight | Direct sun | User misses warnings / buttons | 2 | 4 | **Sunlight mode**: window brightness 100 % during the outdoor calibration steps, released afterwards (`AutoVybzActivity`, window-local – system setting untouched) | – |
| 13 | Late echoes (house wall 10–30 m away) | Single strong reflection | Comb filter at fine spacing | 2 | 2 | Dynamic/strong fractional-octave smoothing averages fine combs; median broad-boost rule | `medianLog` test |
| 14 | Air absorption | Humidity, distance | < 1 dB at 10 kHz over 10 m – negligible | 1 | 5 | none needed (deliberately not corrected; would boost HF for everybody close to the speaker) | – |
| 15 | Two speakers far apart (party mode) | Interference between sources | Deep, position-dependent cancellations | 3 | 3 | Help text: calibrate each speaker alone or place them close together | – |

## 3. Parameters per environment

| Parameter (`EnvironmentTuning`) | Indoors | Outdoors |
|---|---|---|
| Smoothing | user setting | `STRONG` if the user setting is `DYNAMIC`, otherwise the user setting |
| Sweep weight vs. pink noise | 0.5 | 0.7 |
| Max boost | user setting | min(user setting, 3 dB) |
| Broad-boost rule (median window) | off | 1 octave |
| Excursion guard above roll-off | 1.0 octave | 1.5 octaves |
| Limiter | user setting | always on |
| Recommended positions | 1 | 3 |

SNR gating, the head/tail noise snapshots and the three new warnings are active in **both** modes – they
also help in noisy rooms.

## 4. Simulation results

`app/src/test/.../testutil/SpeakerSimulator.kt` + `OutdoorNoise.kt` simulate a typical party Bluetooth
speaker (peak +4 dB @ 120 Hz, dip −4 dB @ 2.8 kHz, HF shelf −3 dB @ 9 kHz, high-pass 55 Hz), a ground
reflection, 250 ms Bluetooth latency, plus wind, birds, noise bursts and dropouts. The error is the
remaining deviation of the *true* corrected response from the target, 150 Hz–8 kHz.

7 single measurement spots, calm:

| Strategy | Mean error | Worst spot | Mean preamp |
|---|---|---|---|
| No EQ | 1.53 dB | – | 0 |
| Room EQ used outdoors (before) | 1.62 dB – *worse than no EQ* | 2.41 dB | −4.5 dB (worst −7.1) |
| **Outdoor mode, 1 position** | **1.17 dB** | 1.97 dB | **−3.1 dB** |
| **Outdoor mode, 3 positions** | **0.58 dB** | – | −3.1 dB |

Wind: the room EQ boosted 30–90 Hz by +1.8…+2.8 dB (amplifying wind rumble that does not exist in the
music). Outdoor mode gates 41–320 Hz, boosts nothing there, fires `LOW_FREQ_NOISE` and still reaches
1.14 dB error from a single position. Birds: ~3–8 kHz gated automatically.

Run locally with `./gradlew testDebugUnitTest --tests "*Outdoor*"`.

## 5. Recommended outdoor procedure (what the wizard tells the user)

1. Choose **Outdoors · Party**. Speaker where it will stand during the party (ground = more bass for free).
2. Volume: party level, not maximum. Phone 2–4 m away at ear height, not on the ground, mic out of the wind.
3. Measure 3 positions spread 1–2 m apart over the dance/listening area. Repeat a position if the app
   reports wind, noise or a dropout.
4. Activate – the limiter is switched on automatically. Changed the placement? Measure again.
