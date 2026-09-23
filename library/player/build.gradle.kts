import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "FFmpeg audio decoding and playback with per-track volume, mute, and track selection"

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

// The decoder integration tests drive the real native engine, like :library:core's.
tasks.named<Test>("jvmTest") {
    dependsOn(":bindings:buildJavaCppHostBindings")
    val bindingsBuildDirectory = project(":bindings").layout.buildDirectory
    val install = rootProject.layout.projectDirectory.dir(
        "native-build/jvm/out/${selectedNativeProfile.get()}/${hostMachine.get()}",
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
