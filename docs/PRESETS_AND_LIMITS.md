# Presets, profiles and limits – how the parts relate

AutoVybz stores sound settings in three different places. They look similar from the outside, which is why
two of them were mixed up in one place for a while (see §3). This document is the reference for the code in
`preset/`, `sound/` and `premium/`.

```
 calibration (AutoVybz)      files/autovybz/presets/<name>.avz.json   ← source of truth for "my profiles"
      │                                   │
      │ pool mirror                       │ export
      ▼                                   ▼
 Equalizer314 preset pool     APO / PEQ JSON / REW CSV  (Pro)
 ("custom_presets" prefs:     │
  preset_<name> + preset_names)
      │
      ▼
 running DSP chain (DynamicsProcessing via EqService)
```

## 1. The three stores

| Store | Where | What lives there | Who writes it |
|---|---|---|---|
| **Calibration** | `files/autovybz/presets/*.avz.json` | EQ bands, pre-amp, full measurement (curves, target, mic profile, warnings, environment, speaker class, positions) | the wizard (`AutoVybzPresetStore.save`) |
| **Engine pool** | shared prefs `custom_presets` (`preset_<name>`, `preset_names`) | the same EQ part in Equalizer314's own JSON shape | AutoVybz **and** the inherited studio screens (AutoEQ import, PEQ editor, …) |
| **Sound selection** | shared prefs `autovybz_sound` | style, style intensity, Studio bass/treble, "use calibration" flag | Sound screen, Studio |

A calibration is always written to both its file **and** the pool, so it appears in the engine's normal preset
list and can be activated there. The pool, however, also contains presets AutoVybz did **not** create. That
asymmetry is the whole reason for §3.

## 2. What a calibration is made of

* **Bands + pre-amp** – the fitted PEQ and the clipping-safe gain from
  `PreampPlanner`/`AntiClipping`.
* **Measurement snapshot** – `frequencies`, `rawDb`, `smoothedDb`, `targetDb`, `predictedDb`, plus the
  metadata the result screen, the share card, exports and re-fitting use: mic profile, input source, engine,
  latency, median SNR, warnings, **environment** (`INDOOR`/`OUTDOOR`), **positions**, **speaker class**
  (`STANDARD`/`RESCUE`/`DEVICE`).
* **Style + tone are *not* baked in.** They are layered on application time by `SoundComposer`, with the
  pre-amp recomputed for the new peak (and the calibration's own loudness make-up preserved), so switching a
  style never needs a re-measurement and never undoes the gain staging.

## 3. Calibrations vs. the engine pool (the bug this document is about)

The Free tier allows **one saved profile**. That check and the "first start" decision (wizard vs. Sound
screen) used to count `AutoVybzPresetStore.existingNames(...)`, i.e. the **shared pool**. Consequences:

* a user who had ever saved an AutoEQ import or a studio preset could **not save a calibration in Free** – and
  the message claimed a profile limit while the Sound screen showed no profiles at all;
* a fresh install that only imported an AutoEQ preset started on the **Sound screen** instead of the wizard,
  i.e. without the calibration that screen is about.

Now:

* `AutoVybzPresetStore.calibrationNames(context)` returns only the names of AutoVybz calibrations,
  `isFirstCalibration(context)` is the start-tab decision.
* The index is kept in prefs (`autovybz_state/calibration_names`, versioned by `calibration_index`) and
  maintained by `save()`/`delete()`, so the check that runs on **every keystroke** of the result screen never
  parses preset files.
* The one-time migration reads the first lines of each stored file (`PresetFiles.nameFromHead`) instead of
  parsing it: the measurement arrays sit on single lines of hundreds of kilobytes.
* `existingNames()` is still the right source for the *"this name already exists, it will be overwritten"*
  hint, because the pool entry is what gets replaced.

## 4. Free / Pro policy

The policy lives in `EntitlementPolicy` (pure, unit-tested) and is enforced in the UI:

| Feature | Free | Trial / Pro |
|---|---|---|
| saved profiles | 1 *(calibrations only, see §3)* | unlimited |
| measurement positions | 1 | 3 |
| expert measurement/mic settings (USB mic, calibration file, mic override, smoothing, custom target, band count, boost limit) | defaults only | yes |
| exports (APO / PEQ JSON / REW CSV) | — | yes |
| targets, speaker classes, environments, styles | all | all |

* **Trial**: 7 days, starts once, automatically after the first successful calibration (or manually from the
  paywall). `trialUsed` survives a reset of the paid period.
* **Paid**: crypto Alpha activation with a TXID; periods stack; a lifetime plan has no expiry.
* **Clock-rollback guard**: `effectiveNow()` never moves backwards, so changing the system clock cannot
  extend a trial. A reading more than a year ahead of the last recorded one (`MAX_TRUSTED_CLOCK_JUMP_MS`)
  is treated as a broken/manipulated clock: it still lets an interval expire (no forward-jump cheat), but it
  is neither stored nor used as the base of a new period – so one bogus reading (dead RTC, wrong timezone
  set to 2099) cannot permanently lock the user out, and cannot stretch a paid period either.
* **Re-entering the same TXID** while PRO is active is a no-op; after expiry it can be entered again – a
  documented Alpha limitation (payment reconciliation is manual), to be replaced by a signed licence.

## 5. Limiter rule (same everywhere)

A calibration is *always* applied with the Equalizer314 limiter armed when

* its environment is `OUTDOOR` (party level), **or**
* its speaker class is `RESCUE` or `DEVICE` (`SpeakerClassTuning.forceLimiter`),

regardless of the user's "protection" preference. `PresetSafety.requiresLimiter()` is the single
implementation; the wizard uses the same rule to decide the limiter state when saving. Previously only the
environment was checked when a stored calibration was re-applied from the Sound or Pro screen, so a
party/rescue/device calibration lost its safety net the first time a sound style was switched.

## 6. Failure handling around storage and engine writes

| Situation | Old behaviour | Now |
|---|---|---|
| measurement file cannot be written (no space, revoked access) | pool entry written, file missing → "saved" profile invisible in Sound/Pro, Free limit consumed | file is written **first**; failure propagates, the wizard stays on the result screen and shows `avz_save_failed` |
| engine write throws while applying | app crash | caught, logged, same toast; the saved calibration can still be applied later |
| deleted profile was the active one | active marker cleared, style-only until the next change | unchanged (intentional) |
| mic calibration file written (Studio) | `writeText` truncated first: a crash or full disk left half a file, which is then ignored as unparseable – the imported calibration silently vanished | written to a sibling `.tmp` file, `fsync`ed and renamed over the target; a failed parse is logged (`AutoVybzSettings`) instead of disappearing |
| unknown speaker class / environment in an old or hand-edited preset | – | `PresetSafety` falls back to the environment rule instead of crashing |
