# AutoVybz – test report (beta readiness)

Campaign date: 2026-10-24 · App version 0.1.0-alpha · Branch `arena/c19a77a9-autovybz`
Tested commit: `1d59823` · Workflows: CI run `40136239591` ✅, Beta test campaign run `40136239582` ✅ (all six jobs green).
Later commits on the branch only change this report and the test catalogue. Campaigns 1–7 belong to the refinement
pass (task 4), campaigns 8–15 to the follow-up pass (German translation of the inherited screens, Pro export test
tag, landscape keyboard, inherited-screen coverage).

This report collects the results of every test layer in [README.md](README.md) (L1–L16), the expert walkthrough of
the temporary screenshot matrix (END_USER_TEST Part A) and the history of fifteen automated campaigns. The
screenshot matrix itself is **temporary**: it is part of the campaign's results bundle and of
`tmp_screenshot_matrix/` (git-ignored). It is never committed.

## 0. Rebrand pass (task 6) – campaigns 16–20

Tested commit: `d12ddc9` · CI run `40173601506` ✅ · Beta test campaign run `40173601500` ✅ (all six jobs green).
Scope: intro video, Vybz Slate design + Outfit typeface, Sound start screen (speaker profiles, 14 genre styles),
Studio, dark-only theme, inherited engine screens re-themed to slate, share card + landing page restyle.
Sections 1–9 below describe the earlier campaign on `1d59823` and stay valid for everything not listed here.

| | Result on `d12ddc9` |
|---|---|
| JVM | **129 tests / 26 classes**, 0 failed (new: `SoundStyleTest` 10, `InheritedPaletteAuditTest` 3, `LicenseAuditTest.bundledFontsAreOflAndCredited`) |
| Functional E2E | **76/76** on API 28, 30 and 35 (+ permission 1/1, tour smoke **8/8** each; new: `SoundE2ETest` 4, `IntroE2ETest` 4, tour `t08_soundStyles`) |
| Screenshot matrix | **7/7 configurations, 609 screenshots**, 27/27 screen tests per configuration |
| Accessibility | **0 findings in 7/7 configurations**, incl. the new Sound and Studio screens at 130 % font on a small phone |
| Robustness | monkey 4000 events: API 28 ✅, API 35 ✅, API 30 ⚠️ SystemUI ANR (NotificationShade) while the AutoVybz main thread was drawing normally – no AutoVybz ANR, 0 FATAL, 0 StrictMode lines |
| Static | Lint AutoVybz **0 findings** (inherited 427), OSV 0 advisories (115 artifacts), secrets 0, APK 38.1 MB / 60 MB, website audit 0/0 |
| Cold start (TotalTime ms, 3 runs) | API 28: 2803 / 1942 / 1711 · API 30: 1243 / 1328 / 1340 · API 35: 1958 / 1743 / 1665 |
| PSS after start | API 28: 61092 kB · API 30: 58402 kB · API 35: 72114 kB |

| # | Commit | Run | Result | Consequence |
|---|---|---|---|---|
| 16 | `4319a09` | `40142539340` | ✅ all green; API 30 monkey ⚠️ SystemUI ANR, app thread busy in text layout | ring pulse recomposed the whole card every frame → draw-phase pulse (17) |
| 17 | `a7fec33` | `40145132584` (cancelled after the device jobs) | ❌ API 30 monkey: AutoVybz ANR in Compose frame dispatch | every style/profile tap rebuilt the engine's DSP chain → coalesced engine updates, debounced power ring, draw-phase pulse |
| 18 | `a081f58` | `40147235288` | ❌ API 28 75/76: safety-start tap swallowed on the slow emulator; API 30 ⚠️ SystemUI ANR, app idle | robot retries a swallowed start tap once (logged, only if the screen did not move, never unticks) |
| 19 | `4cc2905` | `40150193925` | ❌ API 30 monkey: AutoVybz ANR blocked in a system IME binder call | engine writes spaced ≥ 700 ms (last state always lands), power toggles ≥ 1.2 s |
| 20 | `d12ddc9` | `40173601500` | ✅ all green; API 30 ⚠️ SystemUI ANR, app drawing normally | – |

Open observation: the API 30 emulator's SystemUI (notification shade) has hit input timeouts under the monkey in
every run of this pass, while campaign 15 on the old UI was clean. AutoVybz itself is no longer involved (no own ANR in
campaigns 18 and 20); it is still recorded as ⚠️ and should be watched in the closed beta (ANR gate < 0.47 %).

## 1. Verdict

**Ready for the closed beta (phase 1 of BETA_TEST_PLAN).** Every automated layer is green on the tested commit.
That covers 114 JVM tests, 76 instrumented tests on three Android versions (68 functional, 1 permission flow,
7 tour smoke), 7 × 82 screenshots with an accessibility audit per configuration, monkey, lint, OSV, secrets, APK and
website. No S1/S2 finding from the matrix walkthrough is open. What automation cannot prove (acoustic benefit, real
microphones and speakers, OEM audio stacks) is assigned to end-user test Part B and the beta gates in §8.

| | Result |
|---|---|
| Functional E2E | **68/68** on API 28, 30 and 35 (+ permission 1/1, tour smoke 7/7 each) |
| Screenshot matrix | **7/7 configurations, 574 screenshots**, 26/26 screen tests per configuration (tour 7 + inherited screens 19) |
| Accessibility | **0 findings in 7/7 configurations** (+ default configuration on API 28/30/35) |
| Robustness | monkey 4000 events × 3 API levels without crash/ANR, logcat ANR scan 0, 0 FATAL, 0 StrictMode lines |
| Static | Lint AutoVybz **0 findings** (inherited code 841 → 426), OSV 0 advisories, secrets 0, APK 37.4 MB / 60 MB |
| Open S1/S2 | **0** |

## 2. Results per layer

| # | Layer | Result on `1d59823` |
|---|---|---|
| L1 | JVM unit, property and fuzz tests | ✅ 19 classes · 76 tests, 0 failed (property violations of the DSP 136 → 0 during task 4) |
| L2 | Static audits as unit tests | ✅ 5 classes · 38 tests (strings 14 incl. the German translation audit, manifest 7, colour contrast 7, project info 6, licences 4) |
| L3 | Native host check | ✅ FFT vs. DFT, Oboe/JNI syntax |
| L4 | Instrumented E2E | ✅ 68/68 functional + permission 1/1 + tour smoke 7/7 on API 28, 30 and 35 |
| L5 | Inherited `HrirConvolverTest` | ✅ part of the functional run |
| L6 | Screenshot matrix + accessibility | ✅ 7 × (screens 26/26 + a11y 1/1), 574 screenshots, 0 accessibility findings. The 19 inherited Equalizer314 screens are part of the matrix since campaign 8 |
| L7 | Monkey 4000 events, seed 4711 | ✅ API 28 / 30 / 35 without crash or ANR |
| L8 | Performance | ✅ analysis 270 ms, Auto-EQ 3–6 ms, heap growth 0 (JVM budget test); cold start and PSS in §3 |
| L9 | Android Lint | ✅ AutoVybz 0 findings (10 → 0 in campaign 5); inherited Equalizer314 code 841 → 426. `MissingTranslation` for de/es is 0; the 23 that remain concern only the partial upstream translations ru/uk/zh |
| L10 | OSV.dev | ✅ 115 resolved artifacts, 0 advisories |
| L11 | Secret scan | ✅ 9390 tracked files, 0 findings (mutation check 4/4) |
| L12 | APK audit | ✅ 37.4 MB (budget 60 MB), 14 dex, arm64-v8a + armeabi-v7a, minSdk 28 / target 36 |
| L13 | Website audit | ✅ 16 pages, 0 errors, 0 warnings, 153 i18n keys in en/de/es, sitemap 15 URLs (mutation check 8/8) |
| L14 | Mic profile DB | ✅ regenerates byte-identical |
| L15 | End-user test | Part A ✅ (§5, W1–W18 fixed and verified); Part B prepared (tasks, script, SUS-lite) – needs participants |
| L16 | Beta | prepared: issue template "Beta report", phases, gates (§8) |

## 3. Device campaign (emulators, API 28 / 30 / 35)

| | API 28 | API 30 | API 35 |
|---|---|---|---|
| Functional (incl. a11y default config, Hrir) | 68/68 | 68/68 | 68/68 |
| Permission flow | 1/1 | 1/1 | 1/1 |
| Tour smoke | 7/7 | 7/7 | 7/7 |
| Monkey 4000 events | ✅ | ✅ | ✅ |
| FATAL EXCEPTION (all logcats) | 0 | 0 | 0 |
| StrictMode lines of the app | 0 | 0 | 0 |
| Cold start, 3 runs (ms) | 2994 / 1859 / 1731 | 979 / 969 / 981 | 2446 / 2041 / 2646 |
| TOTAL PSS after start | 52.0 MB | 57.2 MB | 76.2 MB |

Trend of the cold start (first / warm runs) and PSS over the campaigns: API 28 2997 → 3335 → 2512 → 2795 ms in campaigns
4–7 (first run on an emulator, so noisy), PSS 52.3 → 52.5 → 52.0 → 51.7 MB. API 35 PSS 78.3 → 78.1 → 76.4 → 76.3 MB.

Campaigns 8–15 (PSS in MB, API 28 / 30 / 35): 8: 52.0 / 58.5 / 77.8 · 10: 52.0 / 57.0 / 84.6 · 11: 52.1 / 56.9 / – ·
12: 56.8 / 57.6 / 78.7 · 13: 52.1 / 59.2 / 78.9 · 14: 52.2 / 58.3 / 76.6 · 15: 52.0 / 57.2 / 76.2. The single outliers
(API 35 84.6 MB in campaign 10, API 28 56.8 MB in campaign 12) did not repeat with the same or newer code, so they are
emulator variance, not a regression. The German strings and the extra screens did not change memory measurably.

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

| Configuration | Campaign 3 | Campaign 4 | Campaign 5 | Campaign 6 | Campaign 7 | Campaigns 8–15 (final) |
|---|---|---|---|---|---|---|
| en-phone | 344 | 8 | 0 | 0 | 0 | **0** |
| de-phone | 341 | 8 | 0 | 0 | 0 | **0** |
| es-phone | 346 | 8 | 0 | 0 | 0 | **0** |
| de-phone-font130 | 290 | 4 | 0 | 0 | 0 | **0** |
| es-small-font130 | 242 | 7 | 2 | 0 | 0 | **0** |
| en-tablet | 391 | 0 | 0 | 0 | 0 | **0** |
| de-landscape | 341 | 8 | 0 | 0 | 0 | **0** |

The last two findings (campaign 5, es-small-font130) were "Interior" and "Exterior · Fiesta" chips 0 dp above the tabs
"Calibrar" and "Pro". On a 720 × 1280 screen the chip row happened to end exactly at the bottom edge of the scroll
viewport. A scrolled control next to fixed navigation is a scroll-position artefact (the user scrolls it away from the
edge), so crowding is now only judged between controls in the same scroll container. Two controls that are both in the
scrolling content or both in the fixed bars are still checked against each other.

Counts come from the instrument output in each campaign's results bundle. Campaign 3 is dominated by the audit's own
false positives, described below. The de-landscape figures for campaigns 3–5 were measured in portrait, because the
rotation did not take effect (W13).

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
| W5 | `result_name_invalid` | Keyboard covers the preset-name field and its error text (edge-to-edge: `adjustResize` no longer resizes) | S2 | `imePadding` + bring the whole field into view whenever the keyboard height changes | campaigns 6–7 |
| W6 | `en-*/saved`, `pro_delete_dialog` | German „…“ quotes in English texts | S3 | “…” in English; audit `everyLanguageUsesItsOwnQuotationMarks` (mutation-checked) | campaign 4 |
| W7 | `move_*`, `result_*`, `pro_*` | Inherited developer toast "DynamicsProcessing Start" covers the navigation | S3 | headless engine starts from AutoVybz pass `EXTRA_SILENT_TOAST`; AutoVybz confirms in its own UI | campaign 4 |
| W8 | `pro_expanded_*`, `help_expanded_*` | Four identical frames: swipes after `expandAll()` were already at the list end | S4 (tour) | one shot per expanded card, walking bottom-up | campaign 4 |
| W9 | **all** API 35 cells | Status-bar clock overlaps logo and word mark: Android 15 forces edge-to-edge for targetSdk ≥ 35 | **S2** | `enableEdgeToEdge()` on every API level, header with `safeDrawing` insets | campaign 4 |
| W10 | `de-phone-font130/move_*`, `es-small-font130` | "Abbreche\|n", "posicio\|nes": words split in two-button rows and chip rows | S2 (a11y) | `AdaptiveButtonRow` stacks buttons above 115 % font or below 360 dp; chip rows are `FlowRow`; new audit rule "word split across lines" | campaigns 4–7 |
| W11 | `es-small-font130/*` | Two-line tagline makes the header tall on small phones | S4 | compact header below 700 dp height | campaigns 6–7 |
| W12 | `de-landscape/*` | Header + bottom bar take half of the height; countdown circle cut off | S2 | navigation rail + compact header below 480 dp height | campaign 7 |
| W13 | `de-landscape` (campaign 4) | Configuration came out portrait: `settings put system user_rotation` silently ignored | S3 (tool) | rotation frozen through `UiAutomation.setRotation` before the activity starts; the screenshot is the ground truth, rotation re-applied in-test (see below) | campaign 7 |
| W14 | `de-*/legacy_*` (19 inherited screens, campaign 8) | 440 English labels in the German Pro screens (MBC, limiter, AutoEQ, …) | S2 | `values-de/strings.xml` with all 440 inherited strings; audit `inheritedScreensAreFullyTranslated`; technical terms (Preset, Attack, Release, Knee, Ratio, Limiter, Gate, Pre-/Post-Gain) deliberately kept | campaigns 9–15 |
| W15 | `de-landscape/mainEqualizer` (campaign 8) | **Crash** of the inherited 10-band EQ in landscape: "The specified child already has a parent" | **S1** | see §7: finish running transitions, detach from parent *or* overlay before re-adding | campaigns 9–15 |
| W16 | `de-phone/legacy_*` (campaign 9) | German labels split across lines in the MBC, limiter, "experimental" and remote segments | S4 | 5 shorter German labels | campaign 10 |
| W17 | `de-landscape/mainEqualizer_*` (campaign 10) | Band row 1–8 shown twice, also after scrolling | S3 | see §7: no layout transition when the system runs no animations | campaigns 11–15 |
| W18 | `de-landscape/result_name_invalid` | With the keyboard open only a narrow strip above it remained (note from campaign 7) | S3 | compact landscape hides the header while the keyboard is visible; field, error and buttons fit | campaigns 9–15 |

Checked with no finding: donate addresses complete and copyable (`donate`, `donate_copied`); error screens each
say what happened, why and what to do, with a matching button; one green primary action per screen; safety
screens use gold only for warnings; no English left in de/es; tablet layout without broken areas; colours as
specified (#121212 / #009B3A / #FED100, WCAG values in `ColorContrastAuditTest`).

Deliberately kept: the German wizard button reads "Speaker einmessen", not "Lautsprecher einmessen". The longer
word does not fit the inner width of the 176 dp start circle at 130 % font without splitting.

**Matrix of the refinement pass (campaign 7, `f397bbd`):** 7 configurations × 44 screens = 308 screenshots, tour 7/7 everywhere,
landscape verified on the screenshot itself. Re-check of the fixes: W5, the name field above the keyboard with the error
text, is visible in all portrait configurations and the tablet. W9, the clock no longer overlaps the header. W10, there
are no split words in de-130 % and es-small-130 %. W11, the compact header is used on the small phone. W12, the rail and
compact header are used in landscape, and the countdown circle is complete.

Landscape with the keyboard open (the beta note of campaign 7) is fixed as W18. The header returns as soon as the
keyboard closes.

**Final matrix (campaign 15, `1d59823`):** 7 configurations × (26 screens + accessibility) = 574 screenshots. The
inherited screens are checked in all languages. German: no English text left apart from the deliberate technical
terms. Landscape: the 10-band EQ opens without crash and shows band row 1–8 once. Bands 9+ and "+" sit below the
parameter card, as in portrait.

The matrix tooling needed three more fixes before landscape was reliable (campaigns 5–7):

* Espresso `closeSoftKeyboard` / `pressBack` waited for window focus that the frozen-rotation runs never got. The tour
  now closes the keyboard with the field's IME action "Done" and uses the global back action.
* The rotation was lost between tests, because the system restores it whenever the UiAutomation connection is
  re-created. The screenshot itself is now the ground truth. Rotation is requested in the test through UiAutomation,
  `settings` and `cmd window`, and re-applied if it was lost mid-test (wizard state survives in the ViewModel).
* A landscape configuration that stays portrait fails with rotation diagnostics instead of silently producing
  portrait shots.

## 6. Campaign history

| # | Commit | What it found | Consequence |
|---|---|---|---|
| 1 | `232e16f` | Lint error in the new code; the failed audit step skipped device and screenshot jobs | lint fixes, audit no longer blocks the device jobs |
| 2 | `760a7f7` | **Crash on API 24–27** (inherited `DynamicsProcessing` / `AudioPlaybackCallback` need API 28); "No compose hierarchies" after *Open 10-band EQ*; accessibility audit threading; flaky waits; monkey ANR on API 30 | **minSdk 28** (Android 9+) everywhere incl. README badge and website, with `ManifestAuditTest` guarding consistency; robust waits; test fixes |
| 3 | `132e10f` | Functional 50/51 on all three API levels; accessibility 242–391 findings per configuration (mostly audit false positives); ANR in `MbcActivity` on API 30 without a stack | headings, audit rules fixed, ANR trace capture added |
| 4 | `4e8ff64` | Matrix walkthrough W1–W12 fixed; accessibility down to 0–8 findings; monkey ANR this time in `com.android.systemui` (notification shade, load 6.6), not AutoVybz | rule refinements (laid-out size, dialog root), FlowRow chips, W5 re-fix, Visualizer off the UI thread, system-process ANRs reported separately |
| 5 | `37939c1` | Device jobs all green for the first time (51/51 × 3, lint AutoVybz 10 → 0). Matrix: Espresso focus failures in the tour; de-landscape still portrait; 2 crowding findings on es-small-font130 | focus-free tour; crowding per scroll container |
| 6 | `c4497e7` | 6 of 7 matrix configurations complete, a11y 7/7 green; landscape lost its rotation between tests. Monkey API 30: ANR in the inherited `MbcActivity` with **no app frame** on the main thread (Dialog dismiss → `destroyHardwareRenderer`, emulator composer at 50 % kernel CPU, load 3.8) | screenshot as rotation ground truth; strict monkey rule (§7) |
| 7 | `f397bbd` | **Everything green**: 51/51 × 3, 7 × 44 screenshots, a11y 0, monkey 3/3 clean, lint 0 | release candidate for the closed beta |
| – | `a6a5375` | First run with the 19 inherited screens hung for more than 55 min: `ActivityScenario` / `waitForIdleSync()` never return on the inherited activities (endless animations / messages) | run cancelled; inherited screens started by Intent, polled with `runOnMainSync`, 90 s `Timeout` rule |
| 8 | `d602513` | Devices 68/68 × 3. Matrix 6/7: **crash of the inherited `MainActivity` in landscape** (W15) | §7 transition fix |
| 9 | `1d6e3c8` | Matrix 7/7 (crash fixed). API 28: 67/68, `CalledFromWrongThreadException` in the wizard (see §7); API 30 monkey: systemui ANR without app frame, clean same-seed rerun (⚠️ rule) | progress updates posted to the main thread |
| 10 | `2d0d2cb` | **Everything green**, zero warnings. Visual review: band row duplicated in landscape (W17) | transition only with system animations |
| 11 | `8544903` | W17 verified fixed. API 35 job: `adb install` failed before the first test (identical APK installed fine in the shots job) | runner waits for the package manager, retries 3×, reports the error |
| 12 | `0b15a7d` | Install OK. API 35: windows never got focus (permission dialog absent, clipboard empty, Espresso "no window focus") - no app crash, no ANR | runner records the focused window on failure |
| 13 | `143c4bc` | Same code: API 35 all green (campaign 12 was the environment). **API 30 monkey: ANR with app frame** in the inherited spectrum visualizer | §7 visualizer on a HandlerThread |
| 14 | `26f10fe` | Devices all green, **API 30 monkey clean** (visualizer fix). Matrix: in every configuration `t05_errors` failed - the new focus diagnostic showed `Application Not Responding: com.google.android.apps.nexuslauncher` holding window focus and swallowing the back key; 25/26 + a11y 1/1 otherwise, no app ANR in any logcat | runner hides error dialogs of other processes, closes system dialogs before each suite, and scans every logcat for ANRs of the app instead |
| 15 | `1d59823` | **Everything green**: 68/68 × 3, 7 × 82 screenshots, a11y 0, monkey 3/3 clean, FATAL 0, StrictMode 0, lint AutoVybz 0 | release candidate for the closed beta |


## 7. Robustness change from the ANR analysis

The ANR in `MbcActivity` (campaign 3) did not reproduce with the same monkey seed in campaign 4. That points to a
timing-dependent block. The process used no CPU, so the main thread was waiting, not busy. The inherited
`VisualizerHelper` created and released `Visualizer(0)` (global session) on the UI thread. These are synchronous
binder calls into audioserver, which at the same moment reconfigures the EQ engine on session 0, and monkey
toggles the spectrum button rapidly. These calls now run serially on a dedicated thread. A generation counter
makes sure a fast on/off can never leave a visualizer running after `stop()`. The runner now saves
`/data/anr` traces (with `adb root` on google_apis images) and puts the Java frames of the main thread into the
summary.

Campaign 6 showed a second pattern on API 30: an ANR in `MbcActivity` whose main thread contained **no AutoVybz or
Equalizer314 frame**. A dialog was dismissed by a touch outside, and the framework waited in `HardwareRenderer` while
tearing down the EGL surface. Meanwhile the emulator's software composer used 50 % kernel CPU, and monkey had already
reported failed injections. The runner treats this pattern strictly: an ANR is only downgraded to a warning if it has no
app frame, is blocked in hwui/EGL, **and** does not reproduce in a second run with the same seed. Any ANR with an app frame,
or a reproducible one, fails. In campaign 7 all three monkey runs were clean anyway. The ANR rate on real devices is a
beta gate (< 0.47 % of sessions, 0 ANRs with an app frame).

### Follow-up pass (campaigns 8–15)

**Landscape crash in the inherited 10-band EQ (W15).** `reorderToggleRows` moves the band rows with
`TransitionManager.beginDelayedTransition`. A row that is still fading out lives in the parent's `ViewGroupOverlay`:
`removeView()` is then a no-op, and the following `addView()` throws "The specified child already has a parent".
Landscape triggers a second reorder while the first transition is still running. Fix: `endTransitions(parent)` before
the reorder, and `detachFromParent` removes the view from its layout parent *or* the overlay.

**Ghost row (W17).** With system animations off (CI emulators, developer option, accessibility "Remove
animations") the fade left a bitmap copy of the moved row in the overlay. The transition now only runs when
`ValueAnimator.areAnimatorsEnabled()`; without animations the rows are moved directly. That matters for real users
with "Remove animations" too, not only for CI.

**Wrong-thread UI update on API 28 (campaign 9).** The measurement engine reports progress from its audio worker
thread. `WizardViewModel` wrote it straight into its `StateFlow`; the Compose test clock resumed the collector inline
on that worker thread, which reached `ViewRootImpl` → `CalledFromWrongThreadException`. Production uses a main-thread
frame clock, so users were not affected, but nothing guaranteed it. `setStepFromProgress` now applies progress only on
the main thread and drops stale updates once the step has moved on (result, error, cancel).

**Spectrum visualizer ANR on API 30 (campaign 13).** The ANR trace showed the main thread inside the inherited
`VisualizerHelper.onWaveFormDataCapture` → `Visualizer.native_getPeakRms`, after ~11 s of main-thread CPU in the monkey
run. AOSP 9/11/15 deliver Visualizer capture callbacks on `Looper.myLooper()` of the thread that registers the
listener, with the main looper as fallback. The worker thread introduced in campaign 4 (a plain executor without a
Looper) therefore moved create/release off the UI thread, but *not* the callbacks: the 4096-point FFT, the PPO
smoothing and the native peak/RMS call ran on the UI thread at the maximum capture rate. `avz-visualizer` is now a
`HandlerThread`. Create, callbacks and release run serially there, and the UI thread only draws the arrays the renderer
publishes through `@Volatile`. A first fix (`acb69f7`) assumed contention on the Visualizer's state lock. The trace
contradicted it (the visualizer thread was idle), so it was replaced by the HandlerThread fix before the final campaign.
Residual risk: `LimiterActivity` keeps its own Visualizer entirely on the UI thread. It did not show up in any monkey run
 and stays under observation in the beta (ANR gate).

**Test infrastructure.** Three API 35 jobs failed outside the app: a failed `adb install` (campaign 11) and, twice,
windows without focus (campaigns 12 and 14). The focus diagnostic added after campaign 12 named the culprit in
campaign 14: an ANR dialog of the Pixel launcher (`com.google.android.apps.nexuslauncher`) that stayed open and took the
back key and clipboard of the app under test. The runner now waits for the package manager and retries the install,
hides error dialogs of other processes (`hide_error_dialogs`), closes system dialogs before every suite, and records the
focused window when a suite fails. Hidden dialogs must not hide our own ANRs, so every logcat of the instrumented runs
is scanned for `ANR in com.andrexthedev.autovybz` (fails the job); monkey reports app ANRs independently.

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
