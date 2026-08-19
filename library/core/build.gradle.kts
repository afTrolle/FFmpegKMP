plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Shared runtime, sessions, logging, progress, and file abstractions"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":bindings"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
