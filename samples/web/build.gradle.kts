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
    dependsOn(":native-build:wasm:linkFfmpegKmpWorker")
    from(selectedNativeProfile.map { profile ->
        rootProject.layout.projectDirectory.dir("native-build/wasm/build/worker/$profile")
    }) {
        include("ffmpegkmp.mjs", "ffmpegkmp.wasm")
    }
    from(rootProject.layout.projectDirectory.dir("bindings/src/wasmJsMain/resources")) {
        include("ffmpegkmp-worker.mjs")
    }
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
        implementation(compose.ui)
    }
    sourceSets.wasmJsMain {
        resources.srcDir(stageFFmpegKmpWasmRuntime)
    }
}
