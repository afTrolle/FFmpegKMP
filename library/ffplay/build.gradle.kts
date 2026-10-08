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
            // The source, decoder and stream types, which ffplay also names with typealiases.
            api(project(":library:codec"))
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
        // One implementation of the operation lock for both JVM targets.
        // A real jvmAndroidMain source set would need an experimental hierarchy-template extension
        // (withAndroidTarget() does not match the Android library plugin's target) and would add a
        // metadata compilation, so its sources compile into each target instead.
        jvmMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
            kotlin.srcDir("src/skikoMain/kotlin")
        }
        androidMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        // VideoFrame.toImageBitmap for every target that draws through Skia, compiled into each the same way.
        nativeMain {
            kotlin.srcDir("src/skikoMain/kotlin")
        }
        webMain {
            kotlin.srcDir("src/skikoMain/kotlin")
        }
        // The decoder fixtures, for the toImageBitmap tests.
        commonTest {
            resources.srcDir(rootProject.layout.projectDirectory.dir("library/codec/src/commonTest/resources"))
        }
        // Real-decoder tests need the native runtime: the JVM and Kotlin/Native have it.
        jvmTest {
            kotlin.srcDir("src/systemTest/kotlin")
        }
        nativeTest {
            kotlin.srcDir("src/systemTest/kotlin")
        }
        // The composite export measurement decodes codec's budget clip (scripts/generate-budget-clip.sh).
        getByName("androidDeviceTest") {
            resources.srcDir(rootProject.layout.projectDirectory.dir("library/codec/src/androidDeviceTest/resources"))
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
            // Skia's native runtime, for the toImageBitmap tests.
            implementation(compose.desktop.currentOs)
        }
    }
}
