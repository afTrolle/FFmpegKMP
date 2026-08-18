// SPDX-License-Identifier: LGPL-2.1-or-later

import org.gradle.api.tasks.Sync

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Generated FFmpeg bindings for all supported Kotlin targets"

val ffmpegSourceDirectory = layout.projectDirectory.dir("../ffmpeg")

tasks.register<Sync>("prepareFfmpegHeaders") {
    group = "ffmpeg bindings"
    description = "Stages pinned FFmpeg headers and the bindings licence for local generation"

    from(ffmpegSourceDirectory) {
        include(
            "libavcodec/**/*.h",
            "libavdevice/**/*.h",
            "libavfilter/**/*.h",
            "libavformat/**/*.h",
            "libavutil/**/*.h",
            "libpostproc/**/*.h",
            "libswresample/**/*.h",
            "libswscale/**/*.h",
        )
    }
    from(layout.projectDirectory.file("LICENSE"))
    into(layout.buildDirectory.dir("generated/ffmpeg-headers"))
}

// Kotlin/Native cinterop, the shared JVM/Android JNI generator, and Wasm
// generation will all consume prepareFfmpegHeaders as their common input. The
// resulting declarations are generated locally and remain LGPL-2.1-or-later.
