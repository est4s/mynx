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
        // The release workflow sets it from the tag (v0.1.0 → 0.1.0).
        versionName = System.getenv("MYNX_VERSION_NAME") ?: "0.0.1"

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
        // The release key never touches the repo: the release workflow decodes
        // it from a GitHub secret and passes it in through these variables.
        // Without them (debug CI builds, the phone) there's no release key and
        // release builds come out unsigned.
        val releaseKeystore = System.getenv("MYNX_RELEASE_KEYSTORE")
        if (!releaseKeystore.isNullOrEmpty()) {
            fun required(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
                ?: error("$name must be set with MYNX_RELEASE_KEYSTORE")
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = required("MYNX_RELEASE_STORE_PASSWORD")
                keyAlias = required("MYNX_RELEASE_KEY_ALIAS")
                keyPassword = required("MYNX_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug builds are a separate app, "mynx dev", with their own
        // Debian, so they install beside the release instead of over it.
        getByName("debug") {
            applicationIdSuffix = ".dev"
        }
        getByName("release") {
            // Off: the Termux libraries and our own code are small, and an R8
            // mistake would only show on the phone.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
