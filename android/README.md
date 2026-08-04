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
  decoding, `com.google.zxing:core` for encoding. Builds, installs, and has
  now been exercised on a real device — a first round of testing turned up a
  main-thread crash on file selection and a few layout issues (status bar
  overlap, a clipped stat row), all fixed. The sender's default bytes/frame
  was also backed off from the web app's 2953-byte (QR v40-L) ceiling to
  1465 (QR v27-L) as a precaution: that ceiling is zero-margin by
  definition, well-proven in zxing-**wasm** (the web app's decoder) but not
  yet confirmed at that exact boundary in zxing-**core**'s Java encoder,
  which this app uses instead. Still genuinely unverified: sustained
  multi-minute transfers, low-end/low-RAM devices, and the receive side's
  CameraX/ML Kit path end-to-end on a physical camera.

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

1. **Confirm the receive side on a real device** — camera permission flow,
   CameraX binding on a real sensor, ML Kit decode latency at the frame rate
   the sender now targets. The send side has had one round of real-device
   testing and fixes; receive hasn't yet been confirmed working end-to-end
   on physical hardware.
2. **Settings UI** — the sender now has tx fps / bytes-per-frame chips
   (`SendScreen.kt`, same option lists as the web app's
   `shared/send-settings.ts`); still missing: error correction level on the
   sender, and capture width / fps / decode workers on the receiver
   (currently hardcoded).
3. **Real theming polish** — a working manual light/dark toggle (currently
   just follows the OS setting) and the glass/blur treatment from
   `Modifier.blur()` / `RenderEffect` on API 31+, per the design notes this
   project's architecture pass produced.
4. **Text snippet sending** from the app (receiving already handles it —
   `ReceiveScreen.kt` detects a snippet container and shows the text; the
   Send screen only offers file picking so far).
