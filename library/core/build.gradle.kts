import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Shared runtime, sessions, logging, progress, and file abstractions"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val hostOperatingSystem = providers.systemProperty("os.name").map { name ->
    when {
        name.contains("mac", ignoreCase = true) -> "macos"
        name.contains("linux", ignoreCase = true) -> "linux"
        name.contains("windows", ignoreCase = true) -> "windows"
        else -> error("Unsupported JVM test host operating system: $name")
    }
}
val hostArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> error("Unsupported JVM test host architecture: $architecture")
    }
}
val hostMachine = hostOperatingSystem.zip(hostArchitecture) { os, architecture -> "$os-$architecture" }
val javaCppFamilies = listOf(
    "Avutil", "Swresample", "Swscale", "Avcodec",
    "Avformat", "Avfilter", "Avdevice", "Bridge",
)

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

tasks.named<Test>("jvmTest") {
    dependsOn(":bindings:buildJavaCppHostBindings")
    val bindingsBuildDirectory = project(":bindings").layout.buildDirectory
    val profile = selectedNativeProfile.get()
    val install = rootProject.layout.projectDirectory.dir(
        "native-build/jvm/out/$profile/${hostMachine.get()}",
    )
    val jniPath = javaCppFamilies.joinToString(File.pathSeparator) { family ->
        bindingsBuildDirectory.dir("generated/javacpp-jni/${hostMachine.get()}/$family")
            .get().asFile.absolutePath
    }
    systemProperty("ffmpegkmp.jni.path", jniPath)
    systemProperty("java.library.path", "$jniPath${File.pathSeparator}${install.dir("lib").asFile.absolutePath}")
    when (hostOperatingSystem.get()) {
        "macos" -> environment("DYLD_LIBRARY_PATH", install.dir("lib").asFile.absolutePath)
        "linux" -> environment("LD_LIBRARY_PATH", install.dir("lib").asFile.absolutePath)
    }
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    // Android host tests run on the build machine and cannot load the Android
    // runtime .so files. Device tests retain the compiled-runtime coverage.
    exclude("**/CompiledRuntimeIntegrationTest.class")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Sibling modules use the opt-in mounted-I/O adapter without exposing
            // generated platform declarations in their public APIs.
            api(project(":bindings"))
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        webTest {
            resources.srcDir(stageCoreWasmTestRuntime)
        }
    }
}
