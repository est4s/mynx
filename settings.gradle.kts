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
        google()
        mavenCentral()
    }
}

rootProject.name = "pocket-terminal"

// Plain Kotlin logic, testable anywhere (including on the phone).
include(":core")

// The Android app needs the Android SDK, which can't run on the phone (no arm64
// aapt2). Without an SDK, only :core is configured, so `./gradlew :core:test` works.
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found: building :core only.")
}
