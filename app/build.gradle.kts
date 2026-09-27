plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ca.wolfietech.dev.android.ytwear"

    // Compile against the newest stable platform; targetSdk is what Play enforces.
    compileSdk = 36

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
        // No abiFilters: build armeabi-v7a (most watches ship a 32-bit userspace),
        // arm64-v8a, and x86/x86_64 for the emulator.
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

    debugImplementation(libs.androidx.compose.ui.tooling)
}
