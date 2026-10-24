# AutoVybz – test report (beta readiness)

Campaign date: 2026-10-24 · App version 0.1.0-alpha · Branch `arena/c19a77a9-autovybz`
Final commit: `@@COMMIT@@` · Workflows: CI `@@CI_RUN@@`, Beta test campaign `@@E2E_RUN@@`

This report collects the results of every test layer in [README.md](README.md) (L1–L16), the expert walkthrough of
the temporary screenshot matrix (END_USER_TEST Part A) and the history of five automated campaigns. The
screenshot matrix itself is **temporary**: it is part of the campaign's results bundle and of
`tmp_screenshot_matrix/` (git-ignored). It is never committed.

## 1. Verdict

@@VERDICT@@

## 2. Results per layer

@@LAYERS@@

## 3. Device campaign (emulators, API 28 / 30 / 35)

@@DEVICE@@

Cold start = `am start -W` TotalTime on a CI emulator (software rendering, no KVM-level GPU). It is an upper bound for
real devices and is used to spot regressions between campaigns, not as an absolute value.

## 4. Accessibility audit

`AccessibilityE2ETest` walks every screen state (wizard, safety, countdown, measuring, move phone, result, discard
dialog, saved, errors, blocked routes, Pro with all cards expanded, help, about, donate dialog) and checks per
screen:

* every clickable has a label;
* touch targets: at least 48 × 48 dp laid-out size, **or** at least 24 dp with a free 48 dp box around the centre
  (WCAG 2.5.8 spacing exception; Material chips and buttons reserve their 48 dp through
  `minimumInteractiveComponentSize`). Text fields are skipped because their semantics node is only the inner text
  line. Controls that are only partly scrolled into view are judged when fully visible. With a dialog open only the
  dialog can be operated, so only the dialog is checked;
* at least one heading (except dialogs);
* no text that exceeds its max lines or is ellipsised;
* no word broken across lines (tokens > 20 characters such as crypto addresses and URLs excepted).

Findings per configuration over the campaigns:

@@A11Y@@

The audit's own false positives were fixed first, with evidence from the bundle: `hasVisualOverflow` from the
`GetTextLayoutResult` action reported overflow for every text, even the tab label "Pro". Raw semantics bounds
under-report Material touch areas, and bounds clipped by the scroll viewport produced "110 × 12 dp". Only then
were the real findings fixed: headings on all step titles, the brand word mark read as one word, buttons stacked
at large font scales, and chips that wrap instead of splitting words.

## 5. Screenshot-matrix walkthrough (END_USER_TEST Part A)

Matrix: 7 configurations × 44 screens (en/de/es phone 1080 × 2400, de at 130 % font, es on a 720 × 1280 phone at
130 % font, en tablet 1600 × 2560, de landscape), all on API 35. Every cell was reviewed against the 13-point
checklist in END_USER_TEST A.3. Severity as in BETA_TEST_PLAN §6 (S1 blocker … S4 cosmetic).

| ID | Cell(s) | Finding | Sev. | Fix | Verified |
|---|---|---|---|---|---|
| W1 | all sheets | Contact-sheet title shows a tofu box (bitmap font has no en dash) | S4 (tool) | `make_contact_sheets.py`: ASCII separator | campaign 4 |
| W2 | `*/idle_targets`, `idle_bottom` | Software keyboard left open from the name field covers the following shots | S4 (tour) | tour closes the keyboard | campaign 4 |
| W3 | `en-phone/result_outdoor_top` | Shot shows "Analyzing your room…": `AnimatedContent` keeps both steps in the tree during the cross-fade | S4 (tour) | wait until `avz:busy` is gone | campaign 4 |
| W4 | `move_ok`, `error_route_changed_resumable` | "Finish with 1": unclear what 1 means | S3 | plural "Finish with 1 position / 2 positions" in en/de/es (es incl. `many`) | campaign 4 |
| W5 | `result_name_invalid` | Keyboard covers the preset-name field and its error text (edge-to-edge: `adjustResize` no longer resizes) | S2 | `imePadding` + bring the whole field into view whenever the keyboard height changes | campaign 5 |
| W6 | `en-*/saved`, `pro_delete_dialog` | German „…“ quotes in English texts | S3 | “…” in English; audit `everyLanguageUsesItsOwnQuotationMarks` (mutation-checked) | campaign 4 |
| W7 | `move_*`, `result_*`, `pro_*` | Inherited developer toast "DynamicsProcessing Start" covers the navigation | S3 | headless engine starts from AutoVybz pass `EXTRA_SILENT_TOAST`; AutoVybz confirms in its own UI | campaign 4 |
| W8 | `pro_expanded_*`, `help_expanded_*` | Four identical frames: swipes after `expandAll()` were already at the list end | S4 (tour) | one shot per expanded card, walking bottom-up | campaign 4 |
| W9 | **all** API 35 cells | Status-bar clock overlaps logo and word mark: Android 15 forces edge-to-edge for targetSdk ≥ 35 | **S2** | `enableEdgeToEdge()` on every API level, header with `safeDrawing` insets | campaign 4 |
| W10 | `de-phone-font130/move_*`, `es-small-font130` | "Abbreche\|n", "posicio\|nes": words split in two-button rows and chip rows | S2 (a11y) | `AdaptiveButtonRow` stacks buttons above 115 % font or below 360 dp; chip rows are `FlowRow`; new audit rule "word split across lines" | campaigns 4–5 |
| W11 | `es-small-font130/*` | Two-line tagline makes the header tall on small phones | S4 | compact header below 700 dp height | campaign 5 |
| W12 | `de-landscape/*` | Header + bottom bar take half of the height; countdown circle cut off | S2 | navigation rail + compact header below 480 dp height | campaign 5 |
| W13 | `de-landscape` (campaign 4) | Configuration came out portrait: `settings put system user_rotation` silently ignored | S3 (tool) | rotation frozen through `UiAutomation.setRotation` before the activity starts; the shot fails if a landscape config is portrait | campaign 5 |

Checked with no finding: donate addresses complete and copyable (`donate`, `donate_copied`); error screens each
say what happened, why and what to do, with a matching button; one green primary action per screen; safety
screens use gold only for warnings; no English left in de/es; tablet layout without broken areas; colours as
specified (#121212 / #009B3A / #FED100, WCAG values in `ColorContrastAuditTest`).

Deliberately kept: the German wizard button reads "Speaker einmessen", not "Lautsprecher einmessen". The longer
word does not fit the inner width of the 176 dp start circle at 130 % font without splitting.

@@MATRIX_FINAL@@

## 6. Campaign history

| # | Commit | What it found | Consequence |
|---|---|---|---|
| 1 | `232e16f` | Lint error in the new code; the failed audit step skipped device and screenshot jobs | lint fixes, audit no longer blocks the device jobs |
| 2 | `760a7f7` | **Crash on API 24–27** (inherited `DynamicsProcessing` / `AudioPlaybackCallback` need API 28); "No compose hierarchies" after *Open 10-band EQ*; accessibility audit threading; flaky waits; monkey ANR on API 30 | **minSdk 28** (Android 9+) everywhere incl. README badge and website, with `ManifestAuditTest` guarding consistency; robust waits; test fixes |
| 3 | `132e10f` | Functional 50/51 on all three API levels; accessibility 242–391 findings per configuration (mostly audit false positives); ANR in `MbcActivity` on API 30 without a stack | headings, audit rules fixed, ANR trace capture added |
| 4 | `4e8ff64` | Matrix walkthrough W1–W12 fixed; accessibility down to 4–8 findings; monkey ANR this time in `com.android.systemui` (notification shade, load 6.6), not AutoVybz | rule refinements (laid-out size, dialog root), FlowRow chips, W5 re-fix, Visualizer off the UI thread, system-process ANRs reported separately |
| 5 | `@@COMMIT@@` | @@C5@@ | — |

## 7. Robustness change from the ANR analysis

The ANR in `MbcActivity` (campaign 3) did not reproduce with the same monkey seed in campaign 4. That points to a
timing-dependent block. The process used no CPU, so the main thread was waiting, not busy. The inherited
`VisualizerHelper` created and released `Visualizer(0)` (global session) on the UI thread. These are synchronous
binder calls into audioserver, which at the same moment reconfigures the EQ engine on session 0, and monkey
toggles the spectrum button rapidly. These calls now run serially on a dedicated thread. A generation counter
makes sure a fast on/off can never leave a visualizer running after `stop()`. The runner now saves
`/data/anr` traces (with `adb root` on google_apis images) and puts the Java frames of the main thread into the
summary.

## 8. What automation cannot prove (→ end-user test Part B and beta)

Real acoustic improvement (blind A/B), real microphones and their tolerances, Bluetooth latency of real speakers,
loudness comfort, outdoor wind and sun, and OEM audio stacks (Samsung, Xiaomi, Pixel, older Android 9–10
devices). These have tasks, participants and gates in [END_USER_TEST.md](END_USER_TEST.md) Part B and
[BETA_TEST_PLAN.md](BETA_TEST_PLAN.md) (crash-free ≥ 99.5 %, first-try success ≥ 85 %, median time to first
preset < 2 min, blind A/B preference ≥ 70 % indoor / ≥ 60 % outdoor, SUS-lite ≥ 75, 0 open S1/S2).

## 9. Reproduce

See [README.md §2](README.md#2-running-everything). The GitHub workflow **Beta test campaign** runs everything and
publishes the bundle (summaries, instrument output, JUnit JSON, monkey and ANR traces, lint XML, screenshot
matrix with contact sheets) as a git blob. Its SHA is in the `results-blob` notice of the `report` job:

```bash
gh api -H "Accept: application/vnd.github.raw" repos/AndrexTheDev/AutoVybz/git/blobs/<sha> > bundle.tgz
tar xzf bundle.tgz && cp -r bundle/matrix tmp_screenshot_matrix   # open tmp_screenshot_matrix/index.html
```
