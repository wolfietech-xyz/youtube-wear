# youtube-wear

YouTube on Wear OS watches, backed by yt-dlp / youtube-dl. This is the initial workspace:
Kotlin DSL Gradle with a version catalog, one Wear OS app module (Compose for Wear OS,
Material 3), a CMake/NDK native library wired in through JNI, and yt-dlp running on an
embedded CPython via [Chaquopy](https://chaquo.com/chaquopy/).

## Layout

```
settings.gradle.kts, build.gradle.kts   root build (Kotlin DSL)
gradle/libs.versions.toml               version catalog
app/                                    Wear OS app module
  src/main/java/.../MainActivity.kt     Compose for Wear OS entry point
  src/main/java/.../NativeLib.kt        JNI bridge
  src/main/java/.../YtDlp.kt            Kotlin bridge to the Python module
  src/main/python/ytwear.py             yt-dlp calls (extract formats, version)
  src/main/cpp/                         CMakeLists.txt + native-lib.cpp
```

## SDK levels

| Setting | Value | Why |
|---|---|---|
| `targetSdk` | 35 (Android 15) | Google Play minimum for Wear OS apps from Aug 31 2026 (phones need 36; Wear OS is 35). |
| `compileSdk` | 37 | Current AndroidX libraries (core-ktx 1.19) require it. Does not change runtime behaviour. |
| `minSdk` | 30 (Wear OS 3, Android 11) | See below. |

Google does not publish a Wear OS version distribution, so `minSdk` is a reasoned estimate:
Wear OS 2 (API 25 to 28) only shipped on watches from 2021 and earlier, none of which were
updated to Wear OS 3. Every Samsung Galaxy Watch (the majority of Wear OS devices), every
Pixel Watch, and every other watch sold since late 2021 runs Wear OS 3 or newer, so API 30
should cover well over 90% of active watches. Going lower would mean supporting 5+ year old
hardware with 512 MB to 1 GB of RAM that struggles with video playback anyway. Raising it to
33 (Wear OS 4) would drop the Wear OS 3 watches that never got an update (older Fossil,
Mobvoi and Montblanc models), so 30 is the sweet spot.

## Toolchain

- Android Gradle Plugin 9.4.0 (built-in Kotlin, so no `kotlin-android` plugin) with Gradle 9.6.0
- Kotlin 2.4.20 (Compose compiler plugin), JDK 17+
- NDK r30 LTS (`30.0.16248370`), CMake 3.22.1. r28+ produces 16 KB page aligned libraries.
- Chaquopy 17.0.0 with Python 3.11 and `yt-dlp` installed by pip at build time.
- ABIs: armeabi-v7a, arm64-v8a, x86_64 (emulator).

### Why Python 3.11

Chaquopy only builds Python 3.12+ for 64-bit ABIs. Many watches run a 32-bit
(armeabi-v7a) userspace, so going to 3.12+ would cut device coverage. 3.11 is supported
by yt-dlp (needs 3.10+) until upstream drops it; revisit when it does. Chaquopy notes
that 16 KB page devices work best on 3.13+, which is not a concern on current watches.

yt-dlp increasingly needs a JavaScript runtime to solve YouTube's player challenges.
That is not wired in yet; the likely route is QuickJS built through the NDK.

## Building

Install Android SDK Platform 37, Build Tools 36.0.0, NDK 30.0.16248370 and CMake 3.22.1
(Android Studio will offer to). Chaquopy also needs Python 3.11 on the build machine
(`py -3.11` on Windows, `python3.11` elsewhere, or a `buildPython=<path>` line in
`local.properties`). The Gradle configuration cache is off because Chaquopy runs Python
while configuring. Then:

```
./gradlew :app:assembleDebug
```
