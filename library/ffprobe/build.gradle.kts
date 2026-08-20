import org.gradle.api.tasks.Sync

plugins {
    id("ffmpegkmp.multiplatform-library")
    alias(libs.plugins.kotlin.serialization)
}

description = "FFprobe execution API and typed media metadata"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val stageFFprobeWasmTestRuntime = tasks.register<Sync>("stageFFprobeWasmTestRuntime") {
    dependsOn(":native-build:wasm:linkFfmpegKmpWorker")
    from(selectedNativeProfile.map { profile ->
        rootProject.layout.projectDirectory.dir("native-build/wasm/build/worker/$profile")
    }) {
        include("ffmpegkmp.mjs", "ffmpegkmp.wasm")
    }
    from(rootProject.layout.projectDirectory.dir("bindings/src/wasmJsMain/resources")) {
        include("ffmpegkmp-worker.mjs")
    }
    into(layout.buildDirectory.dir("generated/ffprobe-wasm-test-runtime"))
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
            api(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        wasmJsTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        wasmJsTest {
            resources.srcDir(stageFFprobeWasmTestRuntime)
        }
    }
}
