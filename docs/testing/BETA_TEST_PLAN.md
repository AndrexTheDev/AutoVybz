# AutoVybz – beta test plan

**Product:** AutoVybz 0.1.x (Android 7.0+, API 24–36) · **Owner:** AndrexTheDev · **Feedback:** GitHub issue
"Beta report" or hippie.highho@gmail.com

## 1. Goals

1. **Does it make speakers sound better?** At least 70 % of blind A/B votes prefer the AutoVybz EQ over no EQ
   (indoor), and at least 60 % outdoor.
2. **Can laypeople do it alone?** At least 90 % of first-time users finish a calibration without help, with a
   median time under 2 minutes from first launch to saved preset.
3. **Is it safe?** No reports of uncomfortably loud measurements, damaged speakers or clipping after applying a
   preset.
4. **Is it stable?** Crash-free sessions of at least 99.5 %, and no data loss of saved presets.
5. **Does it work across hardware?** The device matrix below is covered, with a known-issues list for anything
   that does not work.

## 2. Phases

| Phase | Who | Size | Duration | Distribution | Entry criteria | Exit criteria |
|---|---|---|---|---|---|---|
| 0 – Automated gate | CI | — | each push | `e2e.yml` | — | everything in TEST_REPORT green (or a documented known issue) |
| 1 – Internal alpha | developer + 2–3 friends | 3–5 | 1 week | debug APK | phase 0 green | no S1/S2 open; END_USER_TEST done with 5 people |
| 2 – Closed beta | invited testers (Discord, Reddit r/audiophile, r/Android, friends) | 20–50 | 2–3 weeks | GitHub pre-release APK (signed) | phase 1 exit | goals 1–4 met; device matrix ≥ 80 % covered |
| 3 – Open beta | anyone via the landing page | 100+ | 2–4 weeks | GitHub pre-release + F-Droid / IzzyOnDroid | phase 2 exit, store texts EN/DE/ES | crash-free ≥ 99.5 %; no S1/S2 open for 7 days |
| 4 – Release | — | — | — | GitHub release (`release.yml`) | phase 3 exit | — |

## 3. Device and environment matrix

Testers record these values in the beta report. Each cell needs at least one passing report before phase 3.

| Dimension | Values to cover |
|---|---|
| Android version | 7–8 (API 24–27), 9–10, 11–12, 13, 14, 15, 16 |
| Manufacturer / audio stack | Samsung (One UI), Google Pixel, Xiaomi / Redmi / POCO (MIUI/HyperOS), OnePlus / OPPO / realme (ColorOS), Motorola, Sony, Fairphone, one tablet |
| Output | Bluetooth speaker (JBL, Bose, Sony, UE, Marshall, Anker), BT soundbar, BT car radio, wired 3.5 mm active speakers, USB-C DAC + speakers, hi-fi via BT receiver |
| Microphone | built-in (UNPROCESSED supported / not supported), USB measurement mic (UMIK-1 with calibration file), headset mic (must not be used: route blocked) |
| Codec / latency | SBC, AAC, aptX / LDAC (BT latency 150–400 ms) |
| Environment | small room, living room, large room with hard walls, garden / terrace, beach / park with wind, party with background noise |
| Language / font | EN, DE, ES; font size 100 % and 130 %+; dark-mode-only theme on OLED / LCD |

## 4. Test tasks for beta testers

Short version of [END_USER_TEST.md](END_USER_TEST.md):

1. Fresh install → Wizard → calibrate your main speaker indoors (quick mode). Save and apply.
2. Listen to 3 songs you know well, with the EQ on and off (Pro → *Sound correction* switch). Which sounds better?
3. Repeat with **Precise (3 positions)**. Compare against task 1.
4. Outdoors (if possible): environment *Outdoor*, measure, compare.
5. Export the preset (APO or REW CSV) and share the result card.
6. Try to break it: plug in headphones mid-measurement, take a call, rotate, background the app, switch speakers.
7. Fill in the beta report (issue template) – also when everything worked.

## 5. Feedback channels and data

- **GitHub issue form "Beta report"** (`.github/ISSUE_TEMPLATE/beta_report.yml`): device, Android version,
  speaker, environment, mode, A/B verdict, SUS-lite score, problems, attachments (export file / screenshot).
- **E-mail** via *About → Feedback* (prefilled subject `AutoVybz <version>`) for people without a GitHub account.
- **No telemetry.** AutoVybz makes no network requests. Crash data comes only from what testers attach
  (`adb logcat` instructions are in the issue form). The privacy promise on the website stays true in the beta.

## 6. Severity and triage

| Severity | Definition | Example | Response |
|---|---|---|---|
| S1 – critical | harm, data loss, crash on the main path, privacy breach | measurement at full volume, saved presets lost, crash on Start | fix before any further distribution |
| S2 – major | main goal unreachable for a group of devices | no signal on all Xiaomi phones, EQ not audible in Spotify | fix within the phase |
| S3 – minor | workaround exists | clipped German text at 130 % font, wrong result-card date format | next build |
| S4 – cosmetic / wish | — | colour nuance, new target curve | backlog |

Every report gets a label (`beta`, `S1`…`S4`, `device:<vendor>`, `area:wizard|pro|export|outdoor|i18n|a11y`).
Every S1/S2 needs an automated regression test (JVM or E2E) before it is closed. This is how the suites grew:
see the fixes found by the property/fuzz tests in commit `040fcdf`.

## 7. Metrics and release gates

| Metric | Source | Gate |
|---|---|---|
| Automated campaign | `e2e.yml` | all green, or a documented known issue |
| Crash-free sessions | beta reports + monkey | ≥ 99.5 % |
| Calibration success (first try) | beta reports | ≥ 85 % |
| Median time to first saved preset | END_USER_TEST, reports | < 2 min |
| Blind A/B preference indoor / outdoor | END_USER_TEST, reports | ≥ 70 % / ≥ 60 % |
| SUS-lite (5 items, 0–100) | questionnaire | ≥ 75 |
| Accessibility findings | AccessibilityE2ETest + TalkBack test | 0 open S1/S2 |
| Open S1 / S2 | issues | 0 / 0 |

## 8. Risks

| Risk | Mitigation |
|---|---|
| Vendor audio stacks ignore UNPROCESSED or apply AGC | fallback to VOICE_RECOGNITION, no-signal and SNR checks, device-matrix tracking |
| BT latency variance | latency search on the deconvolved impulse response (searched up to 800 ms, so Bluetooth delays are covered), `OutdoorScenarioTest.bluetoothDropoutIsDetected` |
| Users measure too loud | 70 % default, explicit safety screen, slider, volume restored afterwards |
| EQ fights the speaker's own DSP / bass extension | no boost below roll-off, excursion guard, pre-gain + limiter |
| Testers expect headphone EQ | blocked route with explanation; Pro links to the classic Equalizer314 AutoEQ headphone database |
