plugins {
    id("ffmpegkmp.project")
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")

tasks.register("assembleNativeBinaries") {
    group = "ffmpeg native build"
    description = "Assembles the selected FFmpeg profile for Android, Apple, JVM, and browser Wasm"
    dependsOn(selectedNativeProfile.map { profile ->
        val suffix = profile.split('-', '_').joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        listOf(
            ":native-build:android:assembleFfmpeg$suffix",
            ":native-build:apple:assembleFfmpeg$suffix",
            ":native-build:jvm:assembleFfmpeg$suffix",
            ":native-build:wasm:assembleFfmpeg$suffix",
        )
    })
}
