import io.github.aftrolle.ffmpegkmp.buildlogic.useHostNativeRuntime
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Compose-free frames, video decoding, media sources and video metadata shared by FFmpegKMP's decoders and players"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")

// The decoder tests drive the real native engine.
tasks.named<Test>("jvmTest") { useHostNativeRuntime() }

// Kotlin/Native test binaries have no class-path resources: they read the fixtures from the source tree,
// which the iOS simulator can reach too.
val fixtureDirectory = layout.projectDirectory.dir("src/commonTest/resources/video-decoder")
val generateNativeTestFixturePath = tasks.register("generateNativeTestFixturePath") {
    val directory = fixtureDirectory.asFile.absolutePath
    val output = layout.buildDirectory.dir("generated/nativeTestFixtures")
    inputs.property("directory", directory)
    outputs.dir(output)
    doLast {
        output.get().file("VideoDecoderFixtureDirectory.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package io.github.aftrolle.ffmpegkmp.codec\n\n" +
                    "internal const val VIDEO_DECODER_FIXTURES: String = \"$directory\"\n",
            )
        }
    }
}

// The browser tests decode and encode in the FFmpeg worker the bindings stage.
val stageBrowserTestRuntime = tasks.register<Sync>("stageBrowserTestRuntime") {
    dependsOn(":bindings:stageWasmRuntime")
    from(selectedNativeProfile.map { profile ->
        rootProject.layout.projectDirectory.dir("bindings/build/generated/wasm-runtime/$profile")
    })
    into(layout.buildDirectory.dir("generated/browser-test-runtime"))
}

kotlin {
    sourceSets {
        webTest {
            resources.srcDir(stageBrowserTestRuntime)
        }
        commonMain.dependencies {
            api(project(":library:core"))
            implementation(project(":bindings"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // One FrameBytes and decoder thread for both JVM targets. Kotlin does not support a JVM+Android
        // intermediate source set here (see ffplay), so its sources compile into each target.
        jvmMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        androidMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        // Real-decoder tests need the native runtime: the JVM and Kotlin/Native have it.
        jvmTest {
            kotlin.srcDir("src/systemTest/kotlin")
            kotlin.srcDir("src/jvmAndroidTest/kotlin")
        }
        getByName("androidHostTest") {
            kotlin.srcDir("src/jvmAndroidTest/kotlin")
        }
        getByName("androidDeviceTest") {
            kotlin.srcDir("src/jvmAndroidTest/kotlin")
        }
        nativeTest {
            kotlin.srcDir("src/systemTest/kotlin")
            kotlin.srcDir(generateNativeTestFixturePath)
        }
        // The CVPixelBuffer tests need IOSurface and VideoToolbox, which watchOS lacks, so they run on iOS and
        // macOS, the Apple targets the other modules' tests run on.
        iosTest {
            kotlin.srcDir("src/pixelBufferTest/kotlin")
        }
        macosTest {
            kotlin.srcDir("src/pixelBufferTest/kotlin")
        }
        // The system tests make a 4K clip with a command, run writers alongside one and probe what they wrote.
        jvmTest.dependencies {
            implementation(project(":library:ffmpeg"))
            implementation(project(":library:ffprobe"))
        }
        nativeTest.dependencies {
            implementation(project(":library:ffmpeg"))
            implementation(project(":library:ffprobe"))
        }
        // The device tests decode for real, so their APK links the Android runtime an app would.
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlinx.coroutines.test)
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
    }
}
