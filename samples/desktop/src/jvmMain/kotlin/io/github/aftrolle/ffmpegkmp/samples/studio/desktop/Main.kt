// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.samples.studio.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.aftrolle.ffmpegkmp.samples.studio.StudioApp
import io.github.vinceglb.filekit.FileKit
import java.io.File

public fun main() {
    configureDesktopNativeRuntime()
    System.setProperty("apple.awt.application.appearance", "system")
    FileKit.init("io.github.aftrolle.ffmpegkmp.studio")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "FFmpegKMP Studio",
        ) {
            StudioApp()
        }
    }
}

/** Makes an IDE-launched sample behave like the Gradle `run` task. */
internal fun configureDesktopNativeRuntime() {
    if (!System.getProperty("ffmpegkmp.jni.path").isNullOrBlank()) return

    val host = desktopHost() ?: return nativeRuntimeWarning("unsupported host")
    val profile = System.getProperty("ffmpegkmp.profile")?.takeIf(String::isNotBlank) ?: "standard"
    val root = runtimeRootCandidates().firstOrNull { candidate ->
        File(candidate, "bindings/build/generated/javacpp-jni/$host/Bridge").isDirectory &&
            File(candidate, "native-build/jvm/out/$profile/$host/lib").isDirectory
    } ?: return nativeRuntimeWarning(
        "generated libraries were not found; run ./gradlew :bindings:buildJavaCppHostBindings",
    )

    val families = listOf("Avutil", "Swresample", "Swscale", "Avcodec", "Avformat", "Avfilter", "Avdevice", "Bridge")
    val searchDirectories = families.map { family ->
        File(root, "bindings/build/generated/javacpp-jni/$host/$family")
    } + File(root, "native-build/jvm/out/$profile/$host/lib")
    val searchPath = searchDirectories.joinToString(File.pathSeparator, transform = File::getAbsolutePath)
    System.setProperty("ffmpegkmp.jni.path", searchPath)
    System.setProperty("org.bytedeco.javacpp.platform.linkpath", searchPath)
    System.err.println("[FFmpegKMP Studio] Native runtime: ${root.absolutePath} ($profile/$host)")
}

private fun desktopHost(): String? {
    val platform = when {
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
        System.getProperty("os.name").contains("linux", ignoreCase = true) -> "linux"
        System.getProperty("os.name").contains("windows", ignoreCase = true) -> "windows"
        else -> return null
    }
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> return null
    }
    return "$platform-$architecture"
}

private fun runtimeRootCandidates(): Sequence<File> {
    val codeLocation = runCatching {
        File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
    }.getOrNull()
    return sequenceOf(File(System.getProperty("user.dir")), codeLocation)
        .filterNotNull()
        .flatMap { start -> generateSequence(start.absoluteFile) { it.parentFile } }
        .distinctBy(File::getAbsolutePath)
}

private fun nativeRuntimeWarning(reason: String) {
    System.err.println("[FFmpegKMP Studio] Native runtime is not configured: $reason")
}
