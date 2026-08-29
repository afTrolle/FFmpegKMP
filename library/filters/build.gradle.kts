plugins {
    id("ffmpegkmp.multiplatform-library")
    id("ffmpegkmp.shared-media-test-fixtures")
}

description = "Typed FFmpeg filter-graph DSL"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:ffmpeg"))
        }
    }
}
