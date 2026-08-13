# E-Paper NFC

Android app to prepare images and write them to **WaveShare NFC-powered (passive) e-paper** displays.

This is a **fork and rebrand** of [joshuatz/nfc-epaper-writer](https://github.com/joshuatz/nfc-epaper-writer) by **Joshua Tzucker**. The original project built the first native Android app for these panels (WebView text/graphics editors, crop flow, first SDK wrapper). This fork keeps that foundation and replaces the write path, preview, and reliability pieces.

**Not affiliated with WaveShare.** The name is used only to identify the hardware.

## Features

- Flash images, text, or free-form graphics to WaveShare NFC e-paper (2.13" through 7.5" HD)
- **1-bit preview** of what the panel will show
- Render modes: threshold, Floyd–Steinberg / Atkinson / Bayer dither
- Invert, threshold cutoff, soften (Weichzeichner) so thin text and corners survive 1-bit conversion
- NFC reader mode with a **field lock** before the long 7.5" transfer
- Remembers last image and last render settings

## Changes in this fork

Compared with [joshuatz/nfc-epaper-writer](https://github.com/joshuatz/nfc-epaper-writer) `main` (`2fe5b01`):

| Area | What changed |
|---|---|
| NFC write | Replaced WaveShare `NFC.jar` with an original Kotlin writer (`WaveshareEpaperWriter.kt`) using the published `0xCD` ISO14443-A command set |
| Reliability | Reader mode, 5-minute presence-check delay, lock the field (`CD 0D` streak) before the SRAM stream, restart the **whole** transfer after a drop instead of continuing mid-image |
| Honesty | Success only after a real refresh wait — no fake “flashed” toast when the panel reset |
| Preview | Live 1-bit preview on the flash screen |
| Render | Threshold, Floyd–Steinberg, Atkinson, Bayer; invert; cutoff slider; soften / Weichzeichner (Gaussian blur + morphological close) |
| Branding | App name **E-Paper NFC**; Joshua’s MIT copyright kept |
| Legal | **Do not ship** WaveShare’s compiled JAR (no OSI license to redistribute). Protocol reimplemented from public docs / official C demo |

The original `WaveShareHandler` that reflected into the package-private JAR is gone.

## Getting the SDKs

You need the **Google Android SDK** to build. The **WaveShare NFC JAR** is **optional** and **not used** by this fork.

### 1. Android SDK (required)

1. Install [Android Studio](https://developer.android.com/studio) or the [command-line tools](https://developer.android.com/studio#command-line-tools-only).
2. Open SDK Manager and install:
   - Android SDK Platform **30** (this project’s `compileSdkVersion`)
   - Build-Tools **30.0.3**
   - Platform-Tools (for `adb`)
3. Accept licenses: `sdkmanager --licenses`
4. Point the project at the SDK. Either export the env var or create `local.properties` (gitignored):

```properties
sdk.dir=/home/YOU/Android/Sdk
```

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

JDK **11** is required for the current Gradle 6.5 / AGP 4.1 wrapper. Newer JDKs fail with `Unsupported class file major version`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64   # path will differ
./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Wireless install (example):

```bash
$ANDROID_HOME/platform-tools/adb pair PHONE_IP:PAIR_PORT
$ANDROID_HOME/platform-tools/adb connect PHONE_IP:DEBUG_PORT
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. WaveShare NFC-Powered e-Paper SDK (optional)

WaveShare publishes a compiled **`NFC.jar`** so third-party apps can call their send function. This fork **does not link or ship that JAR**. You only need it if you restore the original wrapper or want to compare against the official implementation.

1. Open the wiki: [Android SDK for NFC-Powered e-Paper](https://www.waveshare.com/wiki/Android_SDK_for_NFC-Powered_e-Paper)
2. Download the SDK zip: [NFC SDK](https://files.waveshare.com/upload/b/bf/NFC.zip)
3. Unzip. Inside is `NFC.jar`.
4. If you were using the **upstream** app (not this fork), copy it to `app/libs/waveshare-nfc/NFC.jar` and add:

```gradle
implementation files('libs/waveshare-nfc/NFC.jar')
```

WaveShare’s page describes two JAR entry points (obfuscated):

- `a.a(NfcA tag, int sizeEnum, Bitmap bmp)` — send; `1` = success
- `a.b()` — progress `0–100` or `-1`

Size enum (same as this app’s `getScreenSizeEnum()`):

| Enum | Panel | Bitmap |
| --- | --- | --- |
| 1 | 2.13" | 250×122 (SDK also accepts 250×128) |
| 2 | 2.9" | 296×128 |
| 3 | 4.2" | 400×300 |
| 4 | 7.5" | 800×480 |
| 5 | 7.5" HD | 880×528 |
| 6 | 2.7" | 264×176 |
| 7 | 2.9" B | 296×128 |

There is **no OSI license** on that bytecode. Do not commit `NFC.jar` to a public repo unless WaveShare grants you that right. This fork talks to the panel with `WaveshareEpaperWriter` instead.

C reference (same protocol, also from WaveShare): [ST25R3911B-NFC-Demo](https://www.waveshare.com/w/upload/e/e3/ST25R3911B-NFC-Demo.zip). An independent client is [Proxmark3 `cmdhfwaveshare.c`](https://github.com/RfidResearchGroup/proxmark3/blob/master/client/src/cmdhfwaveshare.c) (GPL-3 — **not copied** into this tree).

## Hardware notes

These panels harvest power from the phone’s NFC field. There is no battery and **no resumable image RAM**. A dropped field wipes the transfer.

On a Galaxy S24 Ultra the NFC antenna is near the **cameras**, not the phone center. Leave a 2–5 mm gap and hold still for the whole send + refresh (~30 s on 7.5"). WaveShare’s own docs warn that Samsung phones can be unreliable with this product line.

## Credits

- **Joshua Tzucker** — original [nfc-epaper-writer](https://github.com/joshuatz/nfc-epaper-writer) (MIT). Thank you.
- **CanHub / ArthurHub / Edmodo** — Android Image Cropper (Apache-2.0)
- **Isaiah Odhner** — [JS Paint](https://jspaint.app/) (MIT), in-app graphics editor
- **WaveShare** — hardware and published NFC e-paper command set

See [THIRD_PARTY.md](THIRD_PARTY.md) for licenses.

## License

[MIT](LICENSE) — Copyright (c) 2021 Joshua Tzucker; Copyright (c) 2026 Matthias and contributors.

You may fork, rebrand, and publish this app as long as you keep the copyright and permission notice from `LICENSE`.
