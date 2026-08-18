pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "FFmpegKMP"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Convention plugins live in an included build so every project uses one build model.
includeBuild("build-logic")

include(
    ":bindings",
    ":native-build:android",
    ":native-build:apple",
    ":native-build:jvm",
    ":native-build:wasm",
    ":library:core",
    ":library:ffmpeg",
    ":library:ffprobe",
    ":library:filters",
    ":samples:android",
    ":samples:desktop",
    ":samples:ios",
    ":samples:web",
)
