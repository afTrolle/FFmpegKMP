import io.github.aftrolle.ffmpegkmp.buildlogic.useHostNativeRuntime
import org.gradle.api.tasks.testing.Test

plugins {
    id("ffmpegkmp.compose-multiplatform-library")
    id("ffmpegkmp.shared-media-test-fixtures")
}

tasks.named<Test>("jvmTest") { useHostNativeRuntime() }

description = "State-driven FFplay video playback and Compose Multiplatform presentation"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
            // Audio playback and its public track/level types.
            api(project(":library:player"))
            implementation(project(":bindings"))
            api(libs.kotlinx.coroutines.core)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Generates the audio + video fixture for the A/V sync tests.
            implementation(project(":library:ffmpeg"))
        }
    }
}
