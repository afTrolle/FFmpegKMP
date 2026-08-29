import org.gradle.api.tasks.testing.Test

plugins {
    id("ffmpegkmp.compose-multiplatform-library")
    id("ffmpegkmp.shared-media-test-fixtures")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

val hostOperatingSystem = providers.systemProperty("os.name").map { name ->
    when {
        name.contains("mac", ignoreCase = true) -> "macos"
        name.contains("linux", ignoreCase = true) -> "linux"
        name.contains("windows", ignoreCase = true) -> "windows"
        else -> error("Unsupported JVM host operating system: $name")
    }
}
val hostArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> error("Unsupported JVM host architecture: $architecture")
    }
}
val hostMachine = hostOperatingSystem.zip(hostArchitecture) { os, architecture -> "$os-$architecture" }

tasks.named<Test>("jvmTest") {
    dependsOn(":bindings:stageJavaCppHostRuntime")
    val runtimeDirectory = project(":bindings").layout.buildDirectory.dir(
        hostMachine.map { "generated/host-runtime/$it" },
    )
    val jniPath = runtimeDirectory.get().dir("jni").asFile.absolutePath
    val nativeLibraryPath = runtimeDirectory.get().dir("lib").asFile.absolutePath
    systemProperty("ffmpegkmp.jni.path", jniPath)
    systemProperty("java.library.path", "$jniPath${File.pathSeparator}$nativeLibraryPath")
    environment("DYLD_LIBRARY_PATH", nativeLibraryPath)
    environment("LD_LIBRARY_PATH", nativeLibraryPath)
}

description = "State-driven FFplay video playback and Compose Multiplatform presentation"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":library:core"))
            implementation(project(":bindings"))
            api(libs.kotlinx.coroutines.core)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
