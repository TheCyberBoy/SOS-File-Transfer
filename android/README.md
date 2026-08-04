# SOS File Transfer — Android

Native Android sender/receiver (Kotlin + Jetpack Compose), sharing the same
wire format as the web app in `../send`, `../receive` and `../shared`.

## Status

- **`:core`** — pure Kotlin/JVM port of `shared/protocol.ts` and
  `shared/fountain.ts` (frame header, file container, LT fountain
  encoder/decoder). **Fully verified**: the golden-vector test suite ported
  from `tests/fountain.test.ts` and `tests/protocol.test.ts` passes
  byte-for-byte, which is the actual proof this interoperates with the web
  sender/receiver — see the file header comments in `Fountain.kt` for why
  that matters (deterministic cross-platform math).
- **`:app`** — Compose UI (Home, Send, Receive), CameraX + ML Kit for
  decoding, `com.google.zxing:core` for encoding. Builds and installs — a
  real `assembleDebug` APK links successfully — but **has not been run on a
  device or emulator**. There was no Android SDK, emulator, or physical
  device available in the environment this was built in, and camera-based QR
  scanning can't be meaningfully tested on an emulator anyway (no real
  camera). Treat the UI/camera code as compiled-but-unverified until someone
  runs it on an actual phone.

## Build

```bash
cd android
./gradlew :core:test        # codec correctness — no SDK/device needed
./gradlew :app:assembleDebug # produces app/build/outputs/apk/debug/app-debug.apk
```

Or just open `android/` in Android Studio — it's a standard Gradle project.

**Requirements:** JDK 17+, Android SDK with platform 35 and build-tools
35.0.0 (Android Studio installs both automatically; from the command line,
`sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`).
`ANDROID_HOME` needs to point at the SDK if you're not using Android Studio.

## What's next

Roughly in priority order:

1. **Run it on a real device** and see what breaks — camera permission
   flow, CameraX binding on a real sensor, ML Kit decode latency at the
   frame rate the web sender targets.
2. **Settings UI** — tx fps / bytes-per-frame / error correction on the
   sender, capture width / fps / decode workers on the receiver (currently
   hardcoded to conservative defaults — see `SendScreen.kt`'s `TX_FPS`).
3. **Real theming polish** — a working manual light/dark toggle (currently
   just follows the OS setting) and the glass/blur treatment from
   `Modifier.blur()` / `RenderEffect` on API 31+, per the design notes this
   project's architecture pass produced.
4. **Text snippet sending** from the app (receiving already handles it —
   `ReceiveScreen.kt` detects a snippet container and shows the text; the
   Send screen only offers file picking so far).
