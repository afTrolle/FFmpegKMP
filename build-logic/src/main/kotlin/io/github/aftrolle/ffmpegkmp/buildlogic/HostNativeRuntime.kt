package io.github.aftrolle.ffmpegkmp.buildlogic

import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/** The JVM host as the native build names it: `macos-arm64`, `linux-x64`, `windows-x64`. */
fun Project.hostMachine(): Provider<String> {
    val os = providers.systemProperty("os.name").map { name ->
        when {
            name.contains("mac", ignoreCase = true) -> "macos"
            name.contains("linux", ignoreCase = true) -> "linux"
            name.contains("windows", ignoreCase = true) -> "windows"
            else -> error("Unsupported JVM host operating system: $name")
        }
    }
    val architecture = providers.systemProperty("os.arch").map { name ->
        when (name.lowercase()) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> error("Unsupported JVM host architecture: $name")
        }
    }
    return os.zip(architecture) { system, cpu -> "$system-$cpu" }
}

/**
 * Runs these tests against the locally built FFmpeg and JNI runtime for this host, staged by
 * `:bindings:stageJavaCppHostRuntime`.
 */
fun Test.useHostNativeRuntime() {
    dependsOn(":bindings:stageJavaCppHostRuntime")
    val runtime = project.project(":bindings").layout.buildDirectory
        .dir(project.hostMachine().map { "generated/host-runtime/$it" })
        .get()
    val jni = runtime.dir("jni").asFile.absolutePath
    val libraries = runtime.dir("lib").asFile.absolutePath
    systemProperty("ffmpegkmp.jni.path", jni)
    systemProperty("java.library.path", "$jni${File.pathSeparator}$libraries")
    environment("DYLD_LIBRARY_PATH", libraries)
    environment("LD_LIBRARY_PATH", libraries)
}
