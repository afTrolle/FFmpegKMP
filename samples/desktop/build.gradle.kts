import java.io.File

plugins {
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
        implementation(libs.kotlinx.io.core)
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

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
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

tasks.withType<JavaExec>().configureEach {
    if (name != "run" && name != "jvmRun") return@configureEach
    dependsOn(":bindings:buildJavaCppHostBindings")
    val families = listOf("Avutil", "Swresample", "Swscale", "Avcodec", "Avformat", "Avfilter", "Avdevice", "Bridge")
    val jniPath = families.joinToString(File.pathSeparator) { family ->
        rootProject.layout.projectDirectory
            .dir("bindings/build/generated/javacpp-jni/${studioHost.get()}/$family")
            .asFile.absolutePath
    }
    val nativePath = rootProject.layout.projectDirectory
        .dir("native-build/jvm/out/${selectedNativeProfile.get()}/${studioHost.get()}/lib")
        .asFile.absolutePath
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
    dependsOn(":bindings:buildJavaCppHostBindings")
}

tasks.named("jvmTest") {
    dependsOn(":bindings:buildJavaCppHostBindings")
}
