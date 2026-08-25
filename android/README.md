# SOS File Transfer — Android

By [Novosoft Labs](https://novosoftlabs.com/)

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
- **Received-file preview** (`FilePreview.kt`) — images, PDFs (first page,
  via the built-in `PdfRenderer`, no library), video/audio (inline via
  Media3 `ExoPlayer`), and plain text all get a real in-app preview.
  Word/Excel/PowerPoint and anything else fall back to the "Open" button
  (`ACTION_VIEW`, hands off to whatever app the OS considers the right
  viewer) — there's no built-in Android renderer for OOXML formats, and
  building one would mean either a heavy proprietary library or a cloud
  conversion API that breaks this app's whole no-network premise, so that's
  a deliberate scope boundary, not a gap to fill later. Media3 is pinned to
  1.6.0, not the latest (1.11.0 requires compileSdk 36; this project is on
  35, and bumping that is a bigger, harder-to-verify change than this
  feature itself).

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

1. **Confirm the multi-code grid on a real device** — the sender now shows
   several independent QR codes per tick (default 4, `SendScreen.kt`'s
   `QrGrid`/`codesPerFrame`) and `QrFrameAnalyzer` decodes every code ML
   Kit finds per camera frame, not just the first — a research-grounded
   throughput multiplier (see the screen-camera VLC / "visual MIMO" /
   QR-grid literature), but unverified at real hand-held distance: more
   codes per frame means each is physically smaller, which trades off
   against decode reliability the further/shakier the camera is. Tap-to-
   focus and continuous-video AF are already in to help with that side of
   it.
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
