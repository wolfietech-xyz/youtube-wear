import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "ca.wolfietech.dev.android.ytwear"

    // Compile against the newest stable platform; targetSdk is what Play enforces.
    compileSdk = 37

    // NDK r30 (LTS). r28+ links with 16 KB page alignment by default, which Play requires.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "ca.wolfietech.dev.android.ytwear"
        // Wear OS 3 (Android 11). Wear OS 2 watches (API 25-28) are 2021-and-older hardware
        // and were never updated; everything sold since, including every Samsung Galaxy Watch
        // and Pixel Watch, runs Wear OS 3 or later.
        minSdk = 30
        // Google Play minimum for Wear OS apps from Aug 31 2026.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // Chaquopy ships a prebuilt Python per ABI, so the list must be explicit.
            // armeabi-v7a matters: many watches run a 32-bit userspace even on 64-bit chips.
            // x86_64 is for the emulator.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

chaquopy {
    defaultConfig {
        // 3.11 is the newest Python Chaquopy builds for 32-bit ARM (3.12+ is 64-bit only).
        // yt-dlp needs 3.10+. The build machine needs a matching python3.11 on PATH
        // (py -3.11 on Windows), or buildPython=<path> in local.properties.
        version = "3.11"
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load)
        }
        localProps.getProperty("buildPython")?.let { buildPython(it) }
        pip {
            install("yt-dlp")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.tooling.preview)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
