# AutoVybz – end-user test protocol

A moderated usability and listening test with real people, real speakers and real rooms. The automated
suites prove that the app *works*. This test checks that people *understand* it and *hear* the difference.

It has two parts:

- **Part A – expert walkthrough on the screenshot matrix.** Done before people are invited. It catches wording,
  layout and translation problems cheaply.
- **Part B – moderated sessions** with 5–8 participants, 30–40 minutes each.

---

## Part A – walkthrough on the temporary screenshot matrix

### A.1 Generating the matrix

The matrix is **temporary**. It is regenerated for each build and is never committed (`tmp_screenshot_matrix/`
is git-ignored).

```bash
tools/e2e/run_e2e.sh shots out/shots                     # emulator / device, API 33+
python3 tools/e2e/make_contact_sheets.py out/shots/shots tmp_screenshot_matrix
# open tmp_screenshot_matrix/index.html  (rows = screens, columns = configurations)
```

On GitHub the *Beta test campaign* workflow builds the matrix in the `shots` job. The report job puts the
contact sheets into the results bundle.

| Configuration | Locale | Screen | Density | Font | Orientation | Purpose |
|---|---|---|---|---|---|---|
| `en-phone` | en-US | 1080×2400 | 420 | 100 % | portrait | reference |
| `de-phone` | de-DE | 1080×2400 | 420 | 100 % | portrait | long German words |
| `es-phone` | es-ES | 1080×2400 | 420 | 100 % | portrait | Spanish |
| `de-phone-font130` | de-DE | 1080×2400 | 420 | 130 % | portrait | low vision, longest strings |
| `es-small-font130` | es-ES | 720×1280 | 320 | 130 % | portrait | small / old phone, worst case |
| `en-tablet` | en-US | 1600×2560 | 320 | 100 % | portrait | tablet layout |
| `de-landscape` | de-DE | 1080×2400 | 420 | 100 % | landscape | rotation |

### A.2 Screens in the matrix

File names are `NN_<screen>.jpg`, in tour order.

| Group | Screens |
|---|---|
| Wizard start | `idle_top`, `idle_outdoor`, `idle_targets`, `idle_bottom` |
| Safety | `safety_speaker`, `safety_unknown_outdoor`, `safety_checked` |
| Measuring | `countdown`, `measuring`, `move_ok`, `move_warning` |
| Result | `result_outdoor_top`, `result_top`, `result_targets`, `result_actions`, `export_menu`, `dialog_discard`, `result_name_invalid`, `saved` |
| Errors / blocking | `error_no_signal`, `error_route_changed_resumable`, `dialog_cancel_session`, `blocked_headphones`, `blocked_builtin` |
| Pro | `pro_top`, `pro_calibrations`, `pro_delete_dialog`, `pro_expanded_<n>` |
| Help / About | `help_top`, `help_expanded_<n>`, `about_top`, `about_bottom`, `donate`, `donate_copied` |

### A.3 Checklist per cell (screen × configuration)

Mark every finding with the cell ID (e.g. `de-phone-font130 / result_actions`) and a severity S1–S4
([BETA_TEST_PLAN.md §6](BETA_TEST_PLAN.md)).

1. **Text:** nothing cut off, overlapping or ellipsised where it matters (buttons, warnings, preset name).
   AccessibilityE2ETest already fails on clipped text; this check is for what a machine cannot judge.
2. **Translation:** correct language everywhere, no English left over in DE/ES, natural wording, consistent
   terms (Kalibrierung / calibración), correct quotation marks („…“ / «…»).
3. **Hierarchy:** one obvious primary action per screen (green), secondary actions visibly secondary.
4. **Safety screens:** the volume and speaker warnings are readable at a glance, and gold/red is used only
   for warnings.
5. **Errors:** each error says *what happened*, *why*, and *what to do*, with a button for that action.
6. **Contrast / theme:** #121212 background, green #009B3A / gold #FED100 accents, and no grey-on-grey.
   The computed WCAG values are in `ColorContrastAuditTest`.
7. **Layout:** no huge empty areas on the tablet, nothing unreachable in landscape, and the bottom navigation
   is never covered.
8. **Consistency:** the same thing has the same name and icon on every screen (Wizard / Pro / Help / About).
9. **Donate:** all three addresses are visible in full, and the copy confirmation is clear.
10. **Heuristics** (Nielsen): visibility of system status (countdown, progress, position x/y), match with the
    real world, user control (cancel/back everywhere), error prevention (name validation, discard dialogs),
    recognition rather than recall, minimalist design, help and documentation.

---

## Part B – moderated sessions

### B.1 Participants (5–8)

| Profile | Why |
|---|---|
| 2× tech-averse laypeople (one aged 60+) | core audience of the 1-click wizard |
| 1× party / outdoor user with a BT speaker | outdoor mode |
| 1× non-native speaker of the UI language | wording |
| 1× audio enthusiast (knows REW / APO) | Pro area, exports, credibility |
| 1× low-vision user (font 130 %+ or TalkBack) | accessibility |
| optional: 1–2 owners of unusual devices (Xiaomi, old Android 7–9) | audio stack coverage |

### B.2 Set-up

- The participant's **own phone** if possible (fresh install of the beta APK), otherwise a test phone.
- A Bluetooth speaker **and** a wired speaker, a pair of headphones (for T6), quiet room plus an outdoor spot.
- 3 reference songs chosen by the participant, plus 1 fixed song (pink-noise-rich pop track) for comparisons.
- Observer sheet (B.5), stopwatch, screen recording (with consent), think-aloud instructions.
- Consent: participation is voluntary, recordings are used only for this test and deleted after the report,
  and no personal data is published.

### B.3 Session script

1. Welcome (2 min). "We are testing the app, not you. Please think aloud."
2. Pre-questions (2 min): Android experience 1–5, ever used an equalizer?, speaker model.
3. Tasks T1–T8 (25 min). Do not help unless the participant is stuck for > 60 s (counts as an *assist*).
4. Blind A/B (5 min, inside T2/T4).
5. Questionnaire Q1–Q10 (3 min) + open debrief (3 min).

### B.4 Tasks

Task wording is read aloud exactly as written and does not name UI elements.

| ID | Task (what the participant hears) | Success criterion | Observe | Matrix screens |
|---|---|---|---|---|
| **T1** | "Make your speaker sound as good as possible in this room." | preset saved **and** applied without assist | time to start, reads safety screen?, volume comfort, understands countdown, puts phone down still | idle_*, safety_*, countdown, measuring, result_*, saved |
| **T2** | "Measure again more precisely and decide which version you like more." | multi-position run done; chooses via blind A/B | understands "move the phone", position x/y, discard dialog, target choice | move_ok, move_warning, result_targets, dialog_discard |
| **T3** | "Turn the sound correction off and on again, and find where your saved settings are." | toggles in Pro, finds calibration list | finds Pro tab, understands limiter/pre-gain, delete confirmation | pro_top, pro_calibrations, pro_delete_dialog |
| **T4** | (outdoors) "You are at a garden party. Make the speaker sound good here." | chooses *Outdoor*; result saved | wind/noise warnings understood, repeats a disturbed position, brightness ok in sunlight | idle_outdoor, safety_unknown_outdoor, move_warning, result_outdoor_top |
| **T5** | "Send the result to a friend, and export the settings for your PC." | share card sent; APO or REW file created | export menu clarity, file name, share target | result_actions, export_menu |
| **T6** | "Try to measure with headphones." then "Support the developer with crypto." | understands the block and why; copies an address | wording of the block screen; donate dialog, copy feedback | blocked_headphones, blocked_builtin, donate, donate_copied |
| **T7** | (fresh install, deny microphone) "Calibrate." | finds the way to the app settings and back | error explanation, settings button | error_* (permission) |
| **T8** | (enthusiasts only) "Use your USB measurement microphone with its calibration file." | file loaded, offset set, measurement ok | Pro mic section, file picker, error messages for bad files | pro_expanded_* |

**Blind A/B** (T2, T4): the moderator flips the *Sound correction* switch in Pro (`avz:pro:eq`) out of the participant's sight. Each of the 3 songs
is played as A/B, then B/A (randomised), at matched loudness. The pre-gain keeps the levels comparable; if not,
the moderator matches them by ear. The participant picks the preferred version per pass and records "no
difference" honestly. Score = preferred-EQ passes / all passes.

### B.5 Observation sheet (per task)

| Field | Values |
|---|---|
| Completed | yes / with assist / no |
| Time | mm:ss (from end of task wording to success) |
| Errors | count + short note (wrong tap, misunderstanding, dead end) |
| Assists | count + what was said |
| Quotes | verbatim think-aloud |
| Severity of issues | S1–S4 + screen ID from the matrix |

### B.6 Questionnaire (1 = strongly disagree … 5 = strongly agree)

| ID | Statement |
|---|---|
| Q1 | I would use AutoVybz again. |
| Q2 | The app was easy to use. |
| Q3 | I needed help to get through it. *(reverse)* |
| Q4 | It was fast enough. |
| Q5 | The measurement was never uncomfortably loud, and I felt in control of the volume. |
| Q6 | The speaker sounds better with AutoVybz. |
| Q7 | Everything was easy to read (contrast, size, language). |
| Q8 | The error messages told me what to do. |
| Q9 | I understood what happens to my data (nothing leaves the phone). |
| Q10 | I would recommend AutoVybz to a friend (0–10, NPS). |

SUS-lite = mean of Q1, Q2, (6 − Q3), Q4 and Q7, rescaled to 0–100. Gate ≥ 75 (see the beta plan).

### B.7 Analysis

1. Success rate and median time per task, plus the number of assists.
2. Issue list grouped by screen (matrix cell IDs), deduplicated, with severity and frequency (n/N participants).
3. Blind A/B preference indoor / outdoor, SUS-lite, NPS.
4. Every S1/S2 becomes a GitHub issue with label `beta`, and a regression test is added where automatable.
5. Results go into [TEST_REPORT.md](TEST_REPORT.md) § "End-user test".
