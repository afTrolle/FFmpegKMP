@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.tasks.Sync

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

description = "WebAssembly launcher for the FFmpegKMP Studio sample"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val stageFFmpegKmpWasmRuntime = tasks.register<Sync>("stageFFmpegKmpWasmRuntime") {
    group = "ffmpeg sample"
    description = "Builds and stages the local FFmpeg WebAssembly runtime used by the web sample"
    dependsOn(":bindings:stageWasmRuntime")
    from(selectedNativeProfile.map { profile ->
        rootProject.layout.projectDirectory.dir("bindings/build/generated/wasm-runtime/$profile")
    })
    into(layout.buildDirectory.dir("generated/ffmpegkmp-wasm-runtime"))
}

kotlin {
    wasmJs {
        outputModuleName = "ffmpegkmp-studio"
        browser {
            commonWebpackConfig {
                outputFileName = "ffmpegkmp-studio.js"
            }
        }
        binaries.executable()
    }
    sourceSets.wasmJsMain.dependencies {
        implementation(project(":samples:studio"))
        implementation(libs.compose.ui)
    }
    sourceSets.wasmJsMain {
        resources.srcDir(stageFFmpegKmpWasmRuntime)
    }
}
