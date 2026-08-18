plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "FFprobe execution API and typed media metadata"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
        }
    }
}
