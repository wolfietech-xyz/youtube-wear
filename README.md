# youtube-wear

YouTube on Wear OS watches, backed by yt-dlp / youtube-dl. This is the initial workspace:
Kotlin DSL Gradle with a version catalog, one Wear OS app module (Compose for Wear OS,
Material 3) and a CMake/NDK native library wired in through JNI.

## Layout

```
settings.gradle.kts, build.gradle.kts   root build (Kotlin DSL)
gradle/libs.versions.toml               version catalog
app/                                    Wear OS app module
  src/main/java/.../MainActivity.kt     Compose for Wear OS entry point
  src/main/java/.../NativeLib.kt        JNI bridge
  src/main/cpp/                         CMakeLists.txt + native-lib.cpp
```

## SDK levels

| Setting | Value | Why |
|---|---|---|
| `targetSdk` | 35 (Android 15) | Google Play minimum for Wear OS apps from Aug 31 2026 (phones need 36; Wear OS is 35). |
| `compileSdk` | 36 (Android 16) | Newest stable platform; current AndroidX libraries expect it. Does not change runtime behaviour. |
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
- No `abiFilters`: armeabi-v7a matters because many watches run a 32-bit userspace.

## Building

Install Android SDK Platform 36, Build Tools 36.0.0, NDK 30.0.16248370 and CMake 3.22.1
(Android Studio will offer to), then:

```
./gradlew :app:assembleDebug
```
