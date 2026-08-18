plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "FFmpeg execution API and command DSL"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
        }
    }
}
