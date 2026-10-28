# Test matrix: what proves what, and where it runs

Everything below is executed by GitHub Actions; nothing in this document is aspirational. Two workflows carry
the work:

* **`ci.yml`** ("CI") – the fast gate: JVM unit tests, `assembleDebug`, `assembleDebugAndroidTest`, asset
  freshness, native host check. Runs on every push and PR (~5 min).
* **`e2e.yml`** ("Beta test campaign") – the full gate: unit tests + lint + **all static audits**, the
  instrumented suites on three API levels, monkey, crash/ANR scan, cold-start measurement, and a
  screenshot/accessibility matrix in seven configurations. Runs on every push that touches code (~40–70 min).

There is no JDK/Android SDK in the coding sandbox, so local verification is limited to the pure-Python and
native host tools (see §5).

## 1. JVM unit tests – 40 classes, 240 `@Test` methods

| Package | Classes | What they pin down |
|---|---|---|
| `measurement/dsp` | `MeasurementAnalyzerTest` (13), `FftTest`, `StimulusGeneratorTest`, `SpatialAveragerTest` | sweep deconvolution, latency window, SNR blending, power-of-two stimulus period per sample rate, spatial average + spread |
| `measurement/eq` | `AutoEqRendererTest` (20), `PreampPlannerTest` (9), `AutoEqPropertyTest`, `BiquadParityTest` | target inversion, band fitting, clipping-safe pre-gain, Kotlin↔C++ biquad parity |
| `measurement/audio` | `CaptureRatesTest`, `CaptureTimingTest`, `RouteClassifierTest`, `InputSourceResolverTest` | rate negotiation, capture tail derivation, route verdicts, abandoned-volume rule, input device choice |
| `measurement` | `OutdoorScenarioTest`, `SpeakerClassTuningTest`, `PerformanceBudgetTest` | outdoor/rescue/device tuning, analysis time budget |
| `measurement/mic` | `MicProfileDatabaseTest`, `MicProfilesTest`, `CalibrationParserFuzzTest` | profile lookup, calibration-file parsing incl. garbage input |
| `sound` | `SoundStyleTest` (10), `SoundComposerTest` (7), `PresetSafetyTest` (6) | style design limits, layering + band budget, limiter rule |
| `preset` | `AutoVybzPresetCodecTest`, `PresetFilesTest`, `PresetDisplayNameTest` (8), `model/AutoVybzPresetTest` | round-trip codec, file names, head-only name parse, display names (compact + unique picker labels), model invariants |
| `premium` | `EntitlementPolicyTest` (13) | trial/paid/unlimited rules, TXID handling, clock-rollback **and** implausible-jump rules |
| `export` | `PresetExportersTest`, `PresetNamingAndApoFuzzTest` (7) | APO/PEQ/REW output, naming fuzz, code-point-safe names |
| `ui/wizard` | `WizardErrorMappingTest` | capture failure → user-facing error bucket |
| `audit` | `StringResourceAuditTest` (15), `ManifestAuditTest` (7), `ColorContrastAuditTest` (8), `ProjectInfoAuditTest`, `LicenseAuditTest`, `InheritedPaletteAuditTest`, `StickyFooterBannerAuditTest`, `PresetDisplayNameUsageAuditTest`, `ShareThreadingAuditTest` | see §3 |
| inherited | `AutoEqPackTest`, `HrirLoaderTest` | bundled AutoEq pack + HRIR assets |

## 2. Static audits (run inside the campaign, `build` job)

| Audit | Tool | Gate |
|---|---|---|
| Unit-test result digest | `tools/audit/junit_summary.py` | any failure → job fails |
| Android Lint | `tools/audit/lint_summary.py` | **0 errors** in AutoVybz code/resources (inherited findings reported, not gated) |
| APK audit | `tools/audit/apk_audit.py` (aapt2) | permissions, min/target SDK, no debuggable leftovers, size budget |
| Dependency vulnerabilities | `tools/audit/osv_check.py` | no known CVEs in the resolved runtime classpath |
| Secret scan | `tools/audit/secret_scan.py` | 0 findings over all tracked files |
| Website | `site/build.py` + `tools/audit/site_audit.py` | 0 errors/warnings, i18n key parity, sitemap |
| Native host check | `tools/native/host_check.sh` | FFT vs. DFT (≤1e-6), non-pow-2 rejected, Oboe/JNI syntax |
| Asset freshness | `tools/micdb/generate_mic_profiles.py`, `tools/autoeq/pack_autoeq.py` | regenerating must be byte-identical |
| App lint gates as tests | `app/src/test/.../audit/*` | strings (parity, placeholders, plurals, escaping, unused keys, no hardcoded UI text), manifest, contrast, license, project info |

## 3. Instrumented suites – 14 classes, 95 `@Test` methods, API 28 / 30 / 35

| Suite (class) | Tests | Focus |
|---|---|---|
| `WizardFlowE2ETest` | 11 | happy paths: indoor/outdoor, 1/3 positions, 5/10 bands, naming, hand-over to the engine |
| `WizardLifecycleE2ETest` | 14 | cancel/back confirmation, rotation and process restore, window flags, volume set/restore + abandoned volume |
| `WizardErrorsE2ETest` | 10 | every error path incl. resume with kept positions |
| `SoundE2ETest` | 6 | speaker profiles, styles, deleting the audible profile, engine state (band count, pre-gain) |
| `ProE2ETest` / `LegacyScreensE2ETest` | 4 / 19 | Studio settings, inherited engine screens |
| `PremiumE2ETest` | 4 | Free limits (incl. that studio presets do not consume the free profile), trial, Alpha activation/reset |
| `NavigationE2ETest` | 7 | tabs, deep links into the engine, feedback mail |
| `ExportShareE2ETest` | 3 | SAF export, share card through the FileProvider |
| `AccessibilityE2ETest` | 1 (long) | per screen: 48 dp targets, labels, headings, no clipped text |
| `ScreenshotTourE2ETest` | 8 | every state once (also the screenshot source) |
| `IntroE2ETest`, `PermissionDeniedE2ETest` | 4 / 1 | brand intro, permission denial |

Plus, per device job: `monkey` twice (4000 events each, seeds 4711 and 20261028 — two independent random
walks, both reproducible), a **crash scan** over every logcat
(`FATAL EXCEPTION` from the app), an **ANR scan** (`ANR in com.andrexthedev.autovybz`), cold-start timing (3
runs, `am start -W`) and a `meminfo` snapshot.

Monkey verdicts (why an "ANR" is not automatically an app bug): a crash or an ANR *with an app frame on the
main thread* fails the job. Two shapes are re-run with the same seed first and only fail when they reproduce —
a **framework graphics stall** (main thread in hwui/EGL, no app frame) and an **idle main thread** (looper in
`MessageQueue.next` while Android reports *Input dispatching timed out*, i.e. an event was dispatched to a
window that never got a frame). A system-process ANR is an environment finding. Every verdict names the seed,
and the report keeps the monkey output, the ANR trace and the main-thread stack of both runs.

## 4. Screenshot + accessibility matrix (API 35)

Seven configurations, each running `ScreenshotTourE2ETest` + `LegacyScreensE2ETest` and then
`AccessibilityE2ETest`: **en/de/es** phone, German at font scale 1.3, Spanish 720×1280 at 1.3, English tablet
1600×2560, German landscape. Contact sheets are generated from the screenshots in the report job.

## 5. What can be verified without a device

```bash
python3 tools/probe/check_parity.py                     # 29 self-checks: Python port vs. Kotlin goldens
python3 tools/probe/live_probe.py --music <recording>   # real programme material through the whole chain
python3 tools/probe/pipeline_findings.py --music <recording>  # THD / true peak / RT60 / band saturation
                                                         # (the evidence behind docs/AUDIO_ROADMAP.md)
python3 tools/i18n/check_strings.py                     # string parity en/de/es
python3 tools/audit/secret_scan.py .                    # 0 findings
python3 site/build.py --out /tmp/site --stars 0 && python3 tools/audit/site_audit.py /tmp/site
python3 tools/micdb/generate_mic_profiles.py && git diff --exit-code app/src/main/assets/autovybz/mic_profiles.json
python3 tools/autoeq/pack_autoeq.py && git diff --exit-code app/src/main/assets/autoeq/autoeq.bin
bash tools/native/host_check.sh                          # FFT vs DFT + Oboe/JNI syntax
```

`tools/probe` is the only suite that judges the *result* instead of the code: it runs real music through a
modelled measurement chain (minimum-phase speaker + room + mic, sweep + noise) and scores tonal error,
loudness, peaks and clipping. `check_parity.py` guards the port against the Kotlin golden values and runs in
CI; `live_probe.py` needs programme material and `ffmpeg`/`imageio-ffmpeg`, so it stays a manual/probing
tool (see `tools/probe/README.md`). Its round-8 and round-9 findings are listed there.

Round 9 added the author's own recording as the second reference clip: `Demosong.mp3` (repo root, 174 s,
**5.9 dB crest** – a loudness-war master, the opposite corner from the 8.2 dB-crest `Intro1.mp4`). Both
pass 6/6. Note that the upload is a **Matroska/Opus container with an `.mp3` name**; the probe decodes it
with `ffmpeg`, and the landing page deliberately ships a real 128 kb/s MP3 re-encode
(`site/static/audio/demosong.mp3`), because browsers refuse the original container. `pages.yml` checks the
deployed file's ID3 header before publishing the site.

Everything else (Kotlin compile, unit tests, lint, instrumented suites) needs the Gradle toolchain and is
therefore proven only by CI. That is deliberate: the sandbox has no JDK, so "it compiles" is never claimed
from here.

## 6. Known gaps (honest list)

* **No coverage gate.** There is no JaCoCo/Kover report, so "the tests pass" says nothing about lines that no
  test touches (e.g. `MeasurementController`'s retry loop is only covered end-to-end).
* **Instrumented suites need an emulator**; they run on push, not on every PR from forks.
* **Only API 28/30/35.** API 29/31/32/33/34 are untested; behaviour differences are covered by
  `Build.VERSION` branches, not by runs.
* **Monkey uses two fixed seeds** (reproducible, but still only two paths through the state space per run).
* **Timing-sensitive results** (cold start, memory) are reported, not gated — they differ per runner.
* **Screenshot review is manual**: the contact sheets are produced automatically, judging them is not.
