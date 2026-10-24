# AutoVybz – test catalogue

Every test that exists for AutoVybz, how to run it, and which requirement it protects.
Companion documents:

| Document | Purpose |
|---|---|
| [BETA_TEST_PLAN.md](BETA_TEST_PLAN.md) | phases, device matrix, entry/exit criteria, severity, release gates |
| [END_USER_TEST.md](END_USER_TEST.md) | moderated end-user test with real speakers (tasks, observation sheet, questionnaire) |
| [TEST_REPORT.md](TEST_REPORT.md) | results of the latest full campaign, including the screenshot-matrix review |
| [../OUTDOOR.md](../OUTDOOR.md) | outdoor / party FMEA and the measured outdoor accuracy |

## 1. Test layers

| # | Layer | Where | Runs in | Count |
|---|---|---|---|---|
| L1 | JVM unit + property + fuzz tests | `app/src/test` | `ci.yml`, `e2e.yml` build | 24 classes · 113 tests |
| L2 | Static audits as unit tests (strings, colours, manifest, licences, project info) | `app/src/test/.../audit` | same | 5 classes · 37 tests (part of L1) |
| L3 | Native host check (C++ FFT vs. DFT, Oboe/JNI syntax) | `tools/native/host_check.sh` | `ci.yml`, `e2e.yml` | 1 |
| L4 | Instrumented E2E (real Activity, simulated room engine) | `app/src/androidTest/.../e2e` | `e2e.yml` device, API 28/30/35 | 10 classes · 56 tests |
| L5 | Inherited Equalizer314 instrumented test | `HrirConvolverTest` | same | 3 |
| L6 | Screenshot matrix + accessibility per configuration (labels, touch targets incl. WCAG 2.5.8 spacing, headings, ellipsis/max-lines, words split mid-word) | `ScreenshotTourE2ETest`, `AccessibilityE2ETest` | `e2e.yml` shots, API 35 | 7 configurations × ~40 screens |
| L7 | Monkey (4000 random events, seed 4711) with ANR main-thread trace, crash scan, StrictMode count | `tools/e2e/run_e2e.sh` | `e2e.yml` device | 3 API levels |
| L8 | Performance: analysis budget (JVM), cold start, memory (device) | `PerformanceBudgetTest`, `run_e2e.sh` | both | — |
| L9 | Android Lint (AutoVybz must be error-free) | `tools/audit/lint_summary.py` | `e2e.yml` build | — |
| L10 | Dependency vulnerabilities (OSV.dev) | `tools/audit/osv_check.py` | `e2e.yml` build | all resolved artifacts |
| L11 | Secret scan over every tracked file | `tools/audit/secret_scan.py` | `e2e.yml` build | all tracked files |
| L12 | APK audit (size, ABIs, assets, manifest via aapt2) | `tools/audit/apk_audit.py` | `e2e.yml` build | — |
| L13 | Website audit (16 pages EN/DE/ES: links, anchors, hreflang, headings, alt, legal links, addresses, sitemap, manifest, i18n parity) | `tools/audit/site_audit.py` | `e2e.yml` build | — |
| L14 | Mic profile DB regenerates identically | `tools/micdb/generate_mic_profiles.py` | both | — |
| L15 | End-user test: Part A expert walkthrough of the screenshot matrix, Part B moderated sessions | [END_USER_TEST.md](END_USER_TEST.md) | humans | 8 tasks |
| L16 | Closed / open beta with field reports | [BETA_TEST_PLAN.md](BETA_TEST_PLAN.md), issue template "Beta report" | humans | — |

The audits are tested themselves (mutation check): injected faults such as a broken link, missing alt text, a
second `<h1>`, a wrong `lang`, `http://`, a missing anchor, an AWS key, a store password or a tracked `.jks` are
all reported.

## 2. Running everything

```bash
# L1/L2 – JVM
./gradlew testDebugUnitTest
# L3
bash tools/native/host_check.sh
# L4–L8 on an emulator or a USB device (adb):
./gradlew assembleDebug assembleDebugAndroidTest
tools/e2e/run_e2e.sh functional out/device        # permission pass, all suites, monkey, crash scan, cold start
tools/e2e/run_e2e.sh shots out/shots              # API 33+: screenshot matrix + accessibility per config
python3 tools/e2e/make_contact_sheets.py out/shots/shots tmp_screenshot_matrix   # temporary, git-ignored
# L9–L14
./gradlew lintDebug && python3 tools/audit/lint_summary.py app/build/reports/lint-results-debug.xml
./gradlew :app:dependencies --configuration debugRuntimeClasspath > deps.txt && python3 tools/audit/osv_check.py deps.txt
python3 tools/audit/secret_scan.py .
python3 tools/audit/apk_audit.py app/build/outputs/apk/debug/app-debug.apk "$ANDROID_HOME/build-tools/<ver>/aapt2"
python3 site/build.py --out site/_site --stars 0 && python3 tools/audit/site_audit.py site/_site
```

On GitHub all of it runs in the **Beta test campaign** workflow (`.github/workflows/e2e.yml`). Its
step summary is the merged report.

## 3. How the E2E tests work without a real room

`SimulatedRoomEngine` replaces the Oboe engine through `AutoVybzServices`. It plays nothing and "records" the
stimulus filtered through a virtual speaker in a virtual room (`SpeakerSimulator`). It can also inject every
failure mode the real world produces: silence, a driver error, an output route change, audio-focus loss, a
glitch, wind or a noise burst, and a hold to observe intermediate screens. The rest of the app is the shipping
code: wizard, analysis, Auto-EQ, preset store, exports, share card and the Equalizer314 engine screens.
Output routes (headphones, built-in speaker, unknown) are faked through the route-classifier hook.

Things a simulator cannot prove are covered by the end-user test and the beta: real acoustic improvement,
real Bluetooth latency, real microphones, loudness comfort, and whether the EQ is audible in other apps.

## 4. Traceability: requirement → test

| Requirement | Automated test(s) | Human test |
|---|---|---|
| 1-click wizard for laypeople | `WizardFlowE2ETest.quickIndoorCalibration_saveAndApply_showsUpInPro` | EUT T1 |
| External speakers only | `RouteClassifierTest.*`, `WizardErrorsE2ETest.headphones_areBlocked…`, `.builtinSpeaker_isBlocked`, `.unknownOutput_requiresExplicitSpeakerConfirmation`, `.resume_rechecksTheRoute…` | EUT T6 |
| Log sweep + pink noise, exactly 10 s | `StimulusGeneratorTest.totalDurationIsExactlyTenSeconds`, `.pinkNoiseHasEqualEnergyPerOctave`, `.phasesAreOrdered`, `.leadSilenceAndGapAreSilent` | — |
| Volume ≈ 70 %, safety confirmation, slider | `RouteClassifierTest.volumeMathTargetsSeventyPercent`, `WizardLifecycleE2ETest.volumeIsSetToAbout70Percent_andRestoredOnCancel`, safety screens in the tour | EUT T1, Q5 |
| Countdown, cancel at any time | `WizardLifecycleE2ETest.countdown*`, `.cancelWhileMeasuringFirstPosition…` | EUT T1 |
| Screen stays on only while measuring; outdoor brightness | `WizardLifecycleE2ETest.screenStaysOnWhileMeasuring_only`, `.outdoor_usesFullBrightness_indoorDoesNot` | EUT T4 |
| UNPROCESSED → VOICE_RECOGNITION input, capture errors | `WizardErrorsE2ETest.driverError_showsCaptureFailed…`, `.silentMicrophone_showsNoSignal…` | beta device matrix |
| Microphone permission denial | `PermissionDeniedE2ETest.deniedMicrophone_explainsAndLinksToAppSettings` | EUT T7 |
| Mic profile DB, USB mic, calibration files | `MicProfileDatabaseTest`, `MicProfilesTest.*`, `CalibrationParserFuzzTest.*` | EUT T8 (optional) |
| C++/Oboe FFT | `tools/native/host_check.sh`, `FftTest.*` | — |
| Analysis accuracy, dynamic smoothing | `MeasurementAnalyzerTest.*`, `AutoEqRendererTest.*` | EUT T2 (blind A/B) |
| Never boost below speaker roll-off, excursion guard | `AutoEqRendererTest.neverBoostsBelowSpeakerRollOff`, `.excursionGuard*`, `AutoEqPropertyTest.safetyInvariantsHoldForRandomSpeakers` (200 random speakers) | Q5 |
| Targets Smart / Neutral / Bass Boost / Harman / Custom, 5 or 10 bands | `AutoEqRendererTest.targetsAreNormalisedAndShaped`, `.fiveBandModeUsesAtMostFiveBands`, `WizardFlowE2ETest.resultRefit_fiveBandsAndOtherTarget_areSaved` | EUT T2 |
| Auto pre-gain + limiter | `AutoEqPropertyTest.safetyInvariants…`, `ProE2ETest.calibration_canBeAppliedFromPro` | EUT T3 |
| Multi-position averaging | `SpatialAveragerTest.*`, `WizardFlowE2ETest.preciseCalibration_measuresThreePositions`, `.multiPosition_canFinishEarly`, `.disturbedLastPosition_offersRepeatBeforeAveraging`, `WizardErrorsE2ETest.failureAtPosition2…`, `.failureAtLastPosition…` | EUT T2 |
| Outdoor / party robustness | `OutdoorScenarioTest.*` (wind, birds, BT dropout, noise burst, limits), `WizardFlowE2ETest.outdoorWindyPosition_canBeRepeated`, `.audioGlitch_isReportedOnTheResult`, `WizardErrorsE2ETest.audioFocusLoss…`, `.routeChangeDuringCapture…` | EUT T4 |
| Preset name `AutoVybz_[device]_[date]` | `PresetExportersTest.presetNamesFollowScheme`, `PresetNamingAndApoFuzzTest.*`, `WizardFlowE2ETest.speakerName_isUsedForThePresetName`, `.invalidAndDuplicateNames_areHandled` | EUT T3 |
| Safe file names, presets survive | `PresetFilesTest.*`, `AutoVybzPresetCodecTest.*`, `AutoVybzPresetTest.*` | — |
| Exports internal / APO / REW CSV / PEQ JSON | `PresetExportersTest.*`, `PresetNamingAndApoFuzzTest.apo*`, `ExportShareE2ETest.allExportFormats_produceValidFiles` | EUT T5 |
| Share card | `ExportShareE2ETest.shareCard_isAPngGrantedThroughTheFileProvider`, `ManifestAuditTest.fileProviderIsPrivate…` | EUT T5 |
| Pro area (PEQ, MBC, limiter, calibrations) | `ProE2ETest.*`, `NavigationE2ETest.proLinks_openTheEqualizer314Engine`, `LegacyActivitiesE2ETest.*`, `BiquadParityTest` | EUT T3 |
| Navigation Wizard / Pro / Help / About | `NavigationE2ETest.allTabsAreReachable`, `.helpSections_expandAndCollapse`, `.wizardHelpLink_opensHelp`, `WizardLifecycleE2ETest.backFromOtherTabs_returnsToWizard` | EUT all |
| Survives rotation / process recreation | `WizardLifecycleE2ETest.resultAndOpenDialog_surviveActivityRecreation`, `.measurementSurvivesRecreation`, `LegacyActivitiesE2ETest.*` (recreate) | — |
| Unsaved-result protection | `WizardLifecycleE2ETest.back*AsksBeforeDiscarding…`, `.measureAgain_asksBeforeDiscarding…` | EUT T2 |
| Settings persist | `WizardLifecycleE2ETest.wizardChoices_arePersisted`, `ProE2ETest.wizardPicksUpSettingsChangedElsewhere` | — |
| Donate modal with exact addresses + copy | `ProjectInfoAuditTest.*` (checksums: Bech32, base58/32 bytes, EIP-55), `NavigationE2ETest.donateDialog_copiesAllThreeAddressesExactly`, website audit | EUT T6 |
| Contact e-mail, developer name | `ProjectInfoAuditTest.valuesAreExactlyTheRequestedOnes`, `NavigationE2ETest.feedbackMail_isAddressedToTheDeveloper`, website audit (imprint) | — |
| Theme #121212 / green #009B3A / gold #FED100, WCAG | `ColorContrastAuditTest.*` | Q7 |
| Accessibility (48 dp / WCAG 2.5.8 spacing, labels, headings, no clipped or mid-word split text) | `AccessibilityE2ETest` × 7 configurations (incl. font 130 %, small phone, landscape), `AdaptiveButtonRow` stacks buttons above 115 % font | EUT Q7, Part A |
| EN / DE / ES complete, consistent, own quotation marks | `StringResourceAuditTest.*` (13 checks incl. plurals with Spanish `many` and `everyLanguageUsesItsOwnQuotationMarks`), `ManifestAuditTest.localeConfig…`, screenshot matrix per language, website i18n parity | EUT in tester's language |
| GPL-3.0 open source, no trackers | `LicenseAuditTest.*`, OSV check, secret scan | — |
| No crashes | monkey + crash scan on 3 API levels, all suites | beta crash-free rate |
| Fast and lean | `PerformanceBudgetTest.*`, cold start + PSS on device, APK size budget | EUT Q4 |
| Landing page incl. legal pages | website audit (L13) | — |
| Android 9+ (minSdk 28: DynamicsProcessing) everywhere the same | `ManifestAuditTest.minSdkMatchesTheDocumentedAndroidVersion` (gradle, engine, README badge, website), device runs on API 28/30/35, `apk_audit.py` | beta device matrix |
| Edge-to-edge (Android 15+), landscape, keyboard | screenshot matrix (`de-landscape`, `en-tablet`, `result_name_invalid` with keyboard), `AccessibilityE2ETest` | Part A |
| Live A/B: switch the correction off/on while music plays | `ProE2ETest.soundCorrectionSwitch_isAccessibleAndFollowsTheEngine` (role, on/off state, engine sync) | EUT T2/T3 |
| No developer toasts over the AutoVybz UI | screenshot matrix (`move_*`, `result_*`, `pro_*`) | Part A |
| Classic Equalizer314 still works | `LegacyActivitiesE2ETest.*`, `HrirConvolverTest`, `HrirLoaderTest`, `BiquadParityTest` | — |
