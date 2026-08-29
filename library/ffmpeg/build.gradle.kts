plugins {
    id("ffmpegkmp.multiplatform-library")
    id("ffmpegkmp.shared-media-test-fixtures")
}

description = "FFmpeg execution API and command DSL"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
        }
    }
}
