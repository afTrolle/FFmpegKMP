import io.github.aftrolle.ffmpegkmp.buildlogic.useHostNativeRuntime
import org.gradle.api.tasks.testing.Test

plugins {
    id("ffmpegkmp.compose-multiplatform-library")
    id("ffmpegkmp.shared-media-test-fixtures")
}

tasks.named<Test>("jvmTest") { useHostNativeRuntime() }

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")

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
        // One VideoDecoder thread implementation for both JVM targets.
        jvmMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        androidMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        // Real-decoder tests need the native runtime: the JVM and Kotlin/Native have it.
        jvmTest {
            kotlin.srcDir("src/systemTest/kotlin")
        }
        nativeTest {
            kotlin.srcDir("src/systemTest/kotlin")
        }
        // The device tests decode for real, so their APK links the Android runtime an app would.
        getByName("androidDeviceTest").dependencies {
            runtimeOnly(
                files(
                    selectedNativeProfile.map { profile ->
                        rootProject.layout.projectDirectory.file(
                            "bindings/build/generated/android-runtime/ffmpegkmp-runtime-$profile-local.aar",
                        )
                    },
                ).builtBy(":bindings:assembleJavaCppAndroidRuntime"),
            )
        }
        jvmTest.dependencies {
            // Generates the audio + video fixture for the A/V sync tests.
            implementation(project(":library:ffmpeg"))
            // Skia's native runtime, for the VideoDecoder tests that read decoded images.
            implementation(compose.desktop.currentOs)
        }
    }
}
