import java.io.File

plugins {
    id("ffmpegkmp.project")
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

description = "JVM desktop launcher for the FFmpegKMP Studio sample"

kotlin {
    jvm()
    sourceSets.jvmMain.dependencies {
        implementation(project(":samples:studio"))
        implementation(libs.filekit.core)
        implementation(compose.desktop.currentOs)
    }
    sourceSets.jvmTest.dependencies {
        implementation(project(":library:ffmpeg"))
        implementation(libs.okio)
        implementation(kotlin("test"))
    }
}

compose.desktop {
    application {
        mainClass = "io.github.aftrolle.ffmpegkmp.samples.studio.desktop.MainKt"
        nativeDistributions {
            packageName = "FFmpegKMP Studio"
            packageVersion = "0.1.0"
        }
    }
}

val studioHost = providers.systemProperty("os.name").zip(providers.systemProperty("os.arch")) { os, arch ->
    val platform = when {
        os.contains("mac", ignoreCase = true) -> "macos"
        os.contains("linux", ignoreCase = true) -> "linux"
        os.contains("windows", ignoreCase = true) -> "windows"
        else -> error("Unsupported desktop host: $os")
    }
    val architecture = when (arch.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> error("Unsupported desktop architecture: $arch")
    }
    "$platform-$architecture"
}

val prepareFFmpegKmpRuntime = tasks.register("prepareFFmpegKmpRuntime") {
    group = "ffmpeg sample"
    description = "Builds and stages the local FFmpeg/JNI runtime used by the desktop sample"
    dependsOn(":bindings:stageJavaCppHostRuntime")
}

tasks.withType<JavaExec>().configureEach {
    if (name != "run" && name != "jvmRun") return@configureEach
    dependsOn(prepareFFmpegKmpRuntime)
    val runtime = rootProject.layout.projectDirectory
        .dir("bindings/build/generated/host-runtime/${studioHost.get()}")
    val jniPath = runtime.dir("jni").asFile.absolutePath
    val nativePath = runtime.dir("lib").asFile.absolutePath
    jvmArgs(
        "-Dffmpegkmp.jni.path=$jniPath",
        "-Djava.library.path=$jniPath${File.pathSeparator}$nativePath",
    )
    when {
        studioHost.get().startsWith("macos") -> environment("DYLD_LIBRARY_PATH", nativePath)
        studioHost.get().startsWith("linux") -> environment("LD_LIBRARY_PATH", nativePath)
        studioHost.get().startsWith("windows") ->
            environment("PATH", "$nativePath${File.pathSeparator}${System.getenv("PATH")}")
    }
}

// IntelliJ can launch MainKt directly, bypassing the Compose `run` task.
// Ensure that such a build still produces the local JNI runtime discovered by Main.kt.
tasks.named("jvmMainClasses") {
    dependsOn(prepareFFmpegKmpRuntime)
}

tasks.named("jvmTest") {
    dependsOn(prepareFFmpegKmpRuntime)
}
