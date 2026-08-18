plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Typed FFmpeg filter-graph DSL"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:ffmpeg"))
        }
    }
}
