# Alpine Chrome

Lightweight **Alpine Linux + Chromium** preview app for Android.

- First-run Material 3 onboarding (logo, storage + notification toggles, circular Next)
- Minimal Alpine rootfs + only required Chromium packages
- proot guest (binaries in `jniLibs`, not filesDir)
- Session notification **only while browsing** — stopped when you leave the app
- Long-press app icon → **Settings** only (display scale, touch, mouse)
- Touch scroll / long-press via WebView preview surface
- Errors only on failure (no internal backend spam)
- GitHub Actions build (no Android Studio required)

## Quick structure

```
alpine-chromium/
  app/src/main/java/com/alpine/chrome/
    ui/          Onboarding, Setup, Main
    engine/      Rootfs, proot, Chromium install, session service
    settings/    SettingsActivity
  app/src/main/jniLibs/arm64-v8a/   ← proot stack (copied from reference)
  docs/BINARIES_AND_ASSETS.md
  .github/workflows/android-build.yml
```

## Build (CI or local)

```bash
# needs JDK 17 + Android SDK 36
./gradlew :app:assembleDebug
```

See `docs/BINARIES_AND_ASSETS.md` for native libs, optional rootfs asset, and icons.

## Flow

1. Splash → Onboarding (3 pages)
2. Setup: download/extract Alpine → `apk add chromium` (minimal)
3. Main: start short-lived session service + WebView preview
4. `onStop` → stop Chromium (no all-day background)

## Reference

Proot / Alpine patterns adapted from the existing `android/` (mscode) project in this workspace.
