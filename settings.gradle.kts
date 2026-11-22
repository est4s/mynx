pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Robolectric's Android (150 MB) from Google's mirror of Maven Central:
        // Maven Central answered CI's runners with 429 Too Many Requests (2026-11-22).
        exclusiveContent {
            forRepository { maven("https://maven-central.storage-download.googleapis.com/maven2") }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
        google()
        mavenCentral()
        // Termux's terminal libraries and usb-serial-for-android are only published on JitPack.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.termux.termux-app")
                includeGroup("com.github.mik3y")
            }
        }
    }
}

rootProject.name = "mynx"

// Plain Kotlin logic, testable anywhere (including on the phone).
include(":core")

// The Android app needs the Android SDK, which can't run on the phone (no arm64
// aapt2). Without an SDK, only :core is configured, so `./gradlew :core:test` works.
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found: building :core only.")
}
