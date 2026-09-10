# Alpine Chrome — Binary files, icons, folders

Place these before a release / CI build. Paths are relative to the Android module root `app/`.

## 1. Native proot stack (required)

Copy from the existing `android/` reference project (`jniLibs`), or rebuild from Termux/proot sources.

```
app/src/main/jniLibs/arm64-v8a/
  libproot.so              # main proot binary (MUST be named lib*.so for nativeLibraryDir exec)
  libproot-loader.so       # ELF loader used by proot
  libproot-loader32.so     # optional, only if you support 32-bit guests
  libtalloc.so             # talloc dependency
  libtalloc.so.2           # soname symlink/copy of talloc
```

Optional 32-bit (if you enable `armeabi-v7a` in `abiFilters`):

```
app/src/main/jniLibs/armeabi-v7a/
  libproot.so
  libproot-loader.so
  libtalloc.so
  libtalloc.so.2
```

> **Why `lib*.so`?**  
> With `targetSdk > 28`, Android blocks executing binaries from `filesDir`. Shipping them as `jniLibs` puts them under `nativeLibraryDir`, which is executable.

## 2. Optional Alpine rootfs asset (keeps first run offline)

If present, setup skips the CDN download:

```
app/src/main/assets/
  alpine-aarch64.zip       # contains alpine-minirootfs-*-aarch64.tar.gz
  # or alpine-aarch64.tar.gz directly (see RootfsManager)
```

Official source example:

- https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/

Online download still works when the asset is absent (smaller APK).

## 3. Icons / logo

Already included as vectors (Material-friendly Chromium-style mark):

| File | Role |
|------|------|
| `res/drawable/ic_chromium_logo.xml` | In-app logo (onboarding, setup, loading, notification) |
| `res/drawable/ic_arrow_next.xml` | Onboarding circular Next FAB |
| `res/drawable/dot_active.xml` / `dot_inactive.xml` | Page indicators |
| `res/drawable/splash_transparent.xml` | System splash hides default icon |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive launcher |
| `res/mipmap-anydpi-v26/ic_launcher_round.xml` | Adaptive round |

For Play Store / high-res mipmaps, export PNG variants into:

```
res/mipmap-mdpi/ic_launcher.png
res/mipmap-hdpi/ic_launcher.png
res/mipmap-xhdpi/ic_launcher.png
res/mipmap-xxhdpi/ic_launcher.png
res/mipmap-xxxhdpi/ic_launcher.png
(+ _round / _foreground if you use layered icons)
```

Recommended: 512×512 Play icon + feature graphic separately (not in APK).

## 4. What gets installed *inside* Alpine (runtime, not APK)

Via `apk` during SetupActivity (minimal set only):

- `chromium` (or `chromium-browser`)
- `mesa-egl`, `mesa-gl`
- `font-dejavu`, `ttf-freefont`
- `dbus`
- `xvfb`, `x11vnc`

Launch helper written to guest:

```
/usr/local/bin/ac-start-chromium
```

## 5. Runtime layout under app filesDir

```
files/
  alpine_core/          # Alpine rootfs (etc/alpine-release)
  home/                 # bind-mounted to guest /root
  tmp/
```

## 6. GitHub Actions

Workflow file:

```
.github/workflows/android-build.yml
```

Secrets (optional, for signed release):

- `KEYSTORE_BASE64`
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 7. Play Store notes

- `minSdk 26`, `targetSdk 36`, `compileSdk 36`
- Foreground service type `specialUse` — only while user is in the browser session; stopped in `onStop`
- No permanent background Chromium
- Storage + notifications requested in onboarding with Material switches
- Long-press app icon exposes **only** Settings shortcut
