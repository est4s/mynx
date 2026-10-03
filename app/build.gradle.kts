plugins {
    id("com.android.application")
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
}

android {
    // Neutral ID: the app's display name will change before release, the ID can't.
    namespace = "io.github.est4s.terminal"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.est4s.terminal"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.0.1"

        // proot and the Debian rootfs will be arm64-only, so ship nothing else.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Shared debug key committed to the repo, so every CI build installs
        // over the previous one. Release builds will use a secret key instead.
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // proot runs as an executable, and Android only allows exec from the
    // extracted native library dir, so the .so files must be extracted.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    // The rootfs is already xz-compressed.
    androidResources {
        noCompress += "xz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
