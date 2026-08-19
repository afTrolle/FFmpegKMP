plugins {
    id("ffmpegkmp.multiplatform-library")
    alias(libs.plugins.kotlin.serialization)
}

description = "FFprobe execution API and typed media metadata"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
        }
    }
}
