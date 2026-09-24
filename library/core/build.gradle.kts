import org.gradle.api.tasks.Sync
import io.github.aftrolle.ffmpegkmp.buildlogic.useHostNativeRuntime
import org.gradle.api.tasks.testing.Test

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Shared runtime, sessions, logging, progress, and file abstractions"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")

val stageCoreWasmTestRuntime = tasks.register<Sync>("stageCoreWasmTestRuntime") {
    dependsOn(":native-build:wasm:linkFfmpegKmpWorker")
    from(selectedNativeProfile.map { profile ->
        rootProject.layout.projectDirectory.dir("native-build/wasm/build/worker/$profile")
    }) {
        include("ffmpegkmp.mjs", "ffmpegkmp.wasm")
    }
    from(rootProject.layout.projectDirectory.dir("bindings/src/webMain/resources")) {
        include("ffmpegkmp-worker.mjs")
    }
    from(layout.projectDirectory.dir("src/webTest/runtime"))
    into(layout.buildDirectory.dir("generated/core-wasm-test-runtime"))
}

tasks.named<Test>("jvmTest") { useHostNativeRuntime() }

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    // Android host tests run on the build machine and cannot load the Android
    // runtime .so files. Device tests retain the compiled-runtime coverage.
    exclude("**/CompiledRuntimeIntegrationTest.class")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Internal: modules that use the opt-in mounted-I/O adapter (player, ffplay) depend on
            // :bindings themselves, so apps don't get the generated declarations on their classpath.
            implementation(project(":bindings"))
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // TemporaryFile only needs a real synchronous filesystem, which JVM, Android, and every
        // Kotlin/Native target share via okio.FileSystem.SYSTEM (unlike Kotlin/JS and Kotlin/Wasm,
        // which get their own actual under webMain) — one shared source directory for all three,
        // since Kotlin does not support a JVM+Android+Native intermediate source set directly.
        jvmMain {
            kotlin.srcDir("src/systemMain/kotlin")
        }
        androidMain {
            kotlin.srcDir("src/systemMain/kotlin")
        }
        nativeMain {
            kotlin.srcDir("src/systemMain/kotlin")
        }
        webTest {
            resources.srcDir(stageCoreWasmTestRuntime)
        }
    }
}
