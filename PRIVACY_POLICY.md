# Privacy Policy — AutoVybz

**Effective date:** 2026-10-25
**App:** AutoVybz (`com.andrexthedev.autovybz`)
**Developer:** AndrexTheDev — hippie.highho@gmail.com
**Source code:** https://github.com/AndrexTheDev/AutoVybz
**License:** GNU General Public License v3.0 (fork of [Equalizer314](https://github.com/bearinmindcat/Equalizer314) by bearinmindcat)

---

## Short version

AutoVybz **does not collect, store remotely, or transmit any personal data**. No analytics, no ad SDK, no trackers, no accounts and no third-party billing SDK. The permanent Alpha banner is an in-app Pro entry point, not a third-party advertisement. Measurements, presets and settings stay on your device unless *you* export them.

## 1. Microphone (speaker calibration)

When you start a calibration and confirm the safety notice, AutoVybz records **about 11 seconds** of microphone audio while your speaker plays the test signal (pink noise + sine sweep). The recording:

- is processed **only in memory on your device** to compute the frequency response,
- is **never saved as audio** and never leaves the device,
- is discarded right after the analysis.

What *is* stored (app-private storage) is the derived result: frequency/level curves, the calculated EQ filters and technical metadata (phone manufacturer/model, input source, latency). Your phone model (`Build.MANUFACTURER` / `Build.MODEL`) is used locally to pick a microphone compensation profile from a database that ships inside the app — no lookup is made online.

The inherited Equalizer314 features use `RECORD_AUDIO` additionally for the `Visualizer` spectrum display (output mix only, not the microphone).

## 2. Network access

AutoVybz itself makes **no network requests**. The `INTERNET`, `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE` permissions come from Equalizer314's optional **TV Mode**, a LAN-only remote control (local sockets + mDNS discovery); it does not contact the internet. Links in the About screen (GitHub, e-mail) open in your browser / mail app.

## 3. Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Calibration measurement (above); Visualizer-based spectrum displays. |
| `MODIFY_AUDIO_SETTINGS` | System-wide EQ via `DynamicsProcessing`; setting media volume to ~70 % for a measurement (restored afterwards by default). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keeps the EQ engine running while other apps play audio. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Optional experimental virtual-surround feature (upstream). |
| `POST_NOTIFICATIONS` | The EQ service's ongoing notification. |
| `BLUETOOTH_CONNECT` | Reading the name of the connected Bluetooth output for per-device presets. |
| `RECEIVE_BOOT_COMPLETED` | Optional EQ auto-start after reboot. |
| `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | Optional LAN-only TV Mode (see 2). |
| `DUMP` | Optional per-app session detection (upstream); not grantable on normal devices, failures are handled. |

## 4. Stored data

Settings, presets and calibrations are stored with `SharedPreferences` and in app-private files (`files/autovybz/…`). They are removed when you uninstall the app. Exports (APO text, JSON, REW CSV, `.avz.json`) are written only to locations you choose.

## 5. Pro payments & donations

The About and AutoVybz Pro screens show public crypto wallet addresses (SOL, BTC, ETH/EVM) that you can copy. If you choose the Alpha Pro activation, you enter a transaction ID (TXID); AutoVybz stores only a SHA-256 hash of that TXID, plus the selected plan/coin and expiry, in app-private preferences. The app itself does not interact with any wallet or blockchain, verify the payment over the network, or send the TXID anywhere. Public blockchains make the transaction itself publicly visible.

## 6. Children

Nothing is collected from anyone, including children.

## 7. Changes & contact

Changes to this policy are visible in the repository history. Questions: hippie.highho@gmail.com or the GitHub issue tracker of AutoVybz.
