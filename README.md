# Chromium for Android

Lightweight Chromium preview app for Android.

- First-run Material 3 onboarding (logo, storage + notification toggles, circular Next)
- Minimal browser environment + only required Chromium packages
- proot guest (binaries in `jniLibs`, not filesDir)
- Session notification **only while browsing** — stopped when you leave the app
- Long-press app icon → **Settings** only (display scale, touch, mouse)
- Touch scroll / long-press via WebView preview surface
- Errors only on failure (no internal backend spam)
- GitHub Actions builds **debug APK only** (no unsigned release)

## Quick structure

```
chromium/
  app/src/main/java/com/alpine/chrome/
    ui/          Onboarding, Setup, Main
    engine/      Rootfs, proot, Chromium install, session service
    settings/    SettingsActivity
  app/src/main/jniLibs/arm64-v8a/   ← proot stack
  docs/BINARIES_AND_ASSETS.md
  .github/workflows/android-build.yml
```

## Build (CI or local)

```bash
# needs JDK 17 + Android SDK 36
./gradlew :app:assembleDebug
```

CI uploads only the debug APK. Release/unsigned artifacts are not produced.

See `docs/BINARIES_AND_ASSETS.md` for native libs, optional rootfs asset, and icons.

## Flow

1. Splash → Onboarding (3 pages; permissions required before Next on page 2)
2. Setup: download/extract environment → install Chromium (minimal)
3. Main: start short-lived session service + WebView preview
4. `onStop` → stop Chromium (no all-day background)

## Reference

Proot patterns adapted from the existing `android/` (mscode) project in this workspace.
