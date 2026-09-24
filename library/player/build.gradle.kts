import io.github.aftrolle.ffmpegkmp.buildlogic.useHostNativeRuntime
import org.gradle.api.tasks.testing.Test

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "FFmpeg audio decoding and playback with per-track volume, mute, and track selection"


// The decoder integration tests drive the real native engine.
tasks.named<Test>("jvmTest") { useHostNativeRuntime() }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
            implementation(project(":bindings"))
        }
        commonTest.dependencies {
            implementation(project(":library:ffmpeg"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Real-decoder tests need a synchronous filesystem and a loadable native runtime:
        // JVM and Kotlin/Native have both; the browser and Android host tests have neither.
        jvmTest {
            kotlin.srcDir("src/systemTest/kotlin")
        }
        nativeTest {
            kotlin.srcDir("src/systemTest/kotlin")
        }
    }
}
