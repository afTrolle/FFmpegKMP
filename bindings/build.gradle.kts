// SPDX-License-Identifier: LGPL-2.1-or-later

import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Properties

plugins {
    id("ffmpegkmp.multiplatform-library")
}

description = "Generated FFmpeg bindings for all supported Kotlin targets"

val ffmpegSourceDirectory = layout.projectDirectory.dir("../ffmpeg")
val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val selectedNativeProfileTaskSuffix = selectedNativeProfile.map { profile ->
    profile.split('-', '_').joinToString("") { part -> part.replaceFirstChar(Char::titlecase) }
}
val hostOperatingSystem = providers.systemProperty("os.name").map { name ->
    when {
        name.contains("mac", ignoreCase = true) -> "macos"
        name.contains("linux", ignoreCase = true) -> "linux"
        name.contains("windows", ignoreCase = true) -> "windows"
        else -> error("Unsupported JavaCPP host operating system: $name")
    }
}
val hostArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "x64"
        else -> error("Unsupported JavaCPP host architecture: $architecture")
    }
}
val hostMachine = hostOperatingSystem.zip(hostArchitecture) { os, architecture -> "$os-$architecture" }
val javaCppGenerator by configurations.creating

dependencies {
    javaCppGenerator(libs.javacpp)
}

tasks.register<Sync>("prepareFfmpegHeaders") {
    group = "ffmpeg bindings"
    description = "Stages pinned FFmpeg headers and the bindings licence for local generation"

    from(ffmpegSourceDirectory) {
        include(
            "libavcodec/**/*.h",
            "libavdevice/**/*.h",
            "libavfilter/**/*.h",
            "libavformat/**/*.h",
            "libavutil/**/*.h",
            "libswresample/**/*.h",
            "libswscale/**/*.h",
        )
    }
    from(layout.projectDirectory.file("LICENSE"))
    into(layout.buildDirectory.dir("generated/ffmpeg-headers"))
}

val compileJavaCppPresets = tasks.register<JavaCompile>("compileJavaCppPresets") {
    group = "ffmpeg bindings"
    description = "Compiles the version-pinned JavaCPP InfoMap presets"
    source(layout.projectDirectory.dir("src/javacpp/java"))
    classpath = javaCppGenerator
    destinationDirectory.set(layout.buildDirectory.dir("javacpp/preset-classes"))
    options.release.set(11)
}

val javaCppFamilies = listOf(
    "Avutil", "Swresample", "Swscale", "Avcodec",
    "Avformat", "Avfilter", "Avdevice", "Bridge",
)

val generateJavaCppBindings = javaCppFamilies.map { family ->
    tasks.register<JavaExec>("generateJavaCpp$family") {
        group = "ffmpeg bindings"
        description = "Parses pinned ${family.lowercase()} headers into local Java declarations"
        dependsOn(compileJavaCppPresets)
        classpath = javaCppGenerator
        mainClass.set("org.bytedeco.javacpp.tools.Builder")

        val profile = selectedNativeProfile.get()
        val install = rootProject.layout.projectDirectory.dir(
            "native-build/jvm/out/$profile/${hostMachine.get()}",
        )
        val projectHeaders = layout.projectDirectory.dir("src/main/headers")
        val generated = layout.buildDirectory.dir("generated/javacpp/$family")
        inputs.dir(install.dir("include"))
        inputs.dir(projectHeaders)
        inputs.dir(rootProject.layout.projectDirectory.dir("native-build/bridge"))
        inputs.files(compileJavaCppPresets.map { it.outputs.files })
        outputs.dir(generated)
        outputs.cacheIf { false }

        args(
            "-classpath", compileJavaCppPresets.get().destinationDirectory.get().asFile.absolutePath,
            "-d", generated.get().asFile.absolutePath,
            "-nogenerate",
            "-Dplatform.includepath=${listOf(
                install.dir("include").asFile.absolutePath,
                projectHeaders.asFile.absolutePath,
                rootProject.layout.projectDirectory.dir("native-build/bridge").asFile.absolutePath,
            ).joinToString(File.pathSeparator)}",
            "io.github.aftrolle.ffmpegkmp.bindings.javacpp.$family",
        )
        doLast {
            generated.get().asFileTree.matching { include("**/*.java") }.forEach { source ->
                val withoutBlockComments = source.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
                source.writeText(
                    withoutBlockComments
                        .lineSequence()
                        .filterNot { it.trimStart().startsWith("//") }
                        .joinToString("\n", postfix = "\n"),
                )
            }
        }
    }
}

tasks.register("generateJavaCppBindings") {
    group = "ffmpeg bindings"
    description = "Generates all locally-pinned JVM/Android JavaCPP declaration families"
    dependsOn(generateJavaCppBindings)
}

val verifyJavaCppBindings = tasks.register<JavaCompile>("verifyJavaCppBindings") {
    group = "verification"
    description = "Compiles every generated JavaCPP declaration family without publishing it"
    dependsOn(generateJavaCppBindings)
    source(javaCppFamilies.map { layout.buildDirectory.dir("generated/javacpp/$it") })
    classpath = javaCppGenerator + files(compileJavaCppPresets.map { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("javacpp/verified-classes"))
    options.release.set(11)
    outputs.cacheIf { false }
}

val javaCppDeclarationsJar = tasks.register<Jar>("javaCppDeclarationsJar") {
    group = "ffmpeg bindings"
    description = "Packages generated declarations for local JVM-family Kotlin compilation"
    dependsOn(verifyJavaCppBindings, compileJavaCppPresets)
    archiveClassifier.set("local-javacpp-declarations")
    from(verifyJavaCppBindings.map { it.destinationDirectory })
    from(compileJavaCppPresets.map { it.destinationDirectory })
    outputs.cacheIf { false }
}

val buildJavaCppHostBindings = javaCppFamilies.map { family ->
    tasks.register<JavaExec>("buildJavaCppHost$family") {
        group = "ffmpeg bindings"
        description = "Builds the local JavaCPP JNI library for ${family.lowercase()} on the host"
        dependsOn(verifyJavaCppBindings)
        classpath = javaCppGenerator + files(
            verifyJavaCppBindings.map { it.destinationDirectory },
            compileJavaCppPresets.map { it.destinationDirectory },
        )
        mainClass.set("org.bytedeco.javacpp.tools.Builder")

        val profile = selectedNativeProfile.get()
        val install = rootProject.layout.projectDirectory.dir(
            "native-build/jvm/out/$profile/${hostMachine.get()}",
        )
        val projectHeaders = layout.projectDirectory.dir("src/main/headers")
        val output = layout.buildDirectory.dir("generated/javacpp-jni/${hostMachine.get()}/$family")
        val generatedClasses = verifyJavaCppBindings.get().destinationDirectory.get().asFile
        val presetClasses = compileJavaCppPresets.get().destinationDirectory.get().asFile
        inputs.dir(install.dir("include"))
        inputs.dir(install.dir("lib"))
        inputs.dir(projectHeaders)
        inputs.dir(rootProject.layout.projectDirectory.dir("native-build/bridge"))
        inputs.files(verifyJavaCppBindings.map { it.outputs.files })
        outputs.dir(output)
        outputs.cacheIf { false }

        args(
            "-classpath", listOf(generatedClasses, presetClasses).joinToString(File.pathSeparator),
            "-d", output.get().asFile.absolutePath,
            "-nodelete",
            "-Dplatform.includepath=${listOf(
                install.dir("include").asFile.absolutePath,
                projectHeaders.asFile.absolutePath,
                rootProject.layout.projectDirectory.dir("native-build/bridge").asFile.absolutePath,
            ).joinToString(File.pathSeparator)}",
            "-Dplatform.linkpath=${install.dir("lib").asFile.absolutePath}",
            "io.github.aftrolle.ffmpegkmp.bindings.generated.${family.lowercase()}.**",
        )
    }
}

tasks.register("buildJavaCppHostBindings") {
    group = "ffmpeg bindings"
    description = "Builds all locally-generated JavaCPP JNI libraries for the current JVM machine"
    dependsOn(buildJavaCppHostBindings)
}

val androidAbis = linkedMapOf(
    "armeabi-v7a" to Triple("android-arm", "armv7a-linux-androideabi24-clang++", "ArmeabiV7a"),
    "arm64-v8a" to Triple("android-arm64", "aarch64-linux-android24-clang++", "Arm64V8a"),
    "x86" to Triple("android-x86", "i686-linux-android24-clang++", "X86"),
    "x86_64" to Triple("android-x86_64", "x86_64-linux-android24-clang++", "X8664"),
)
val androidSdkDirectory = providers.gradleProperty("ffmpegkmp.android.sdkDir").orElse(
    providers.environmentVariable("ANDROID_SDK_ROOT").orElse(
        providers.provider {
            val properties = Properties()
            rootProject.layout.projectDirectory.file("local.properties").asFile
                .takeIf(File::isFile)?.inputStream()?.use { properties.load(it) }
            properties.getProperty("sdk.dir")
                ?: error("Set ffmpegkmp.android.sdkDir, ANDROID_SDK_ROOT, or sdk.dir in local.properties")
        },
    ),
)
val androidNdkDirectory = androidSdkDirectory.map { "$it/ndk/30.0.15729638" }
val androidNdkHost = hostOperatingSystem.map { os ->
    when (os) {
        "macos" -> "darwin-x86_64"
        "linux" -> "linux-x86_64"
        "windows" -> "windows-x86_64"
        else -> error("Unsupported Android NDK host: $os")
    }
}

val buildJavaCppAndroidBindings = androidAbis.flatMap { (abi, configuration) ->
    val (platform, compiler, taskSuffix) = configuration
    javaCppFamilies.map { family ->
        tasks.register<JavaExec>("buildJavaCppAndroid${taskSuffix}$family") {
            group = "ffmpeg bindings"
            description = "Builds the ${family.lowercase()} JavaCPP JNI library for Android $abi"
            dependsOn(verifyJavaCppBindings)
            dependsOn(":native-build:android:buildFfmpeg${selectedNativeProfileTaskSuffix.get()}$taskSuffix")
            classpath = javaCppGenerator + files(
                verifyJavaCppBindings.map { it.destinationDirectory },
                compileJavaCppPresets.map { it.destinationDirectory },
            )
            mainClass.set("org.bytedeco.javacpp.tools.Builder")

            val profile = selectedNativeProfile.get()
            val install = rootProject.layout.projectDirectory.dir("native-build/android/out/$profile/$abi")
            val projectHeaders = layout.projectDirectory.dir("src/main/headers")
            val output = layout.buildDirectory.dir("generated/javacpp-jni/android/$abi/$family")
            val ndk = androidNdkDirectory.get()
            val ndkHost = androidNdkHost.get()
            inputs.dir(install.dir("include"))
            inputs.dir(install.dir("lib"))
            inputs.dir(projectHeaders)
            inputs.dir(rootProject.layout.projectDirectory.dir("native-build/bridge"))
            inputs.files(verifyJavaCppBindings.map { it.outputs.files })
            outputs.dir(output)
            outputs.cacheIf { false }

            args(
                "-classpath", listOf(
                    verifyJavaCppBindings.get().destinationDirectory.get().asFile,
                    compileJavaCppPresets.get().destinationDirectory.get().asFile,
                ).joinToString(File.pathSeparator),
                "-d", output.get().asFile.absolutePath,
                "-nodelete",
                "-properties", platform,
                "-Dplatform.root=$ndk/",
                "-Dplatform.compiler=toolchains/llvm/prebuilt/$ndkHost/bin/$compiler",
                "-Dplatform.includepath=${listOf(
                    install.dir("include").asFile.absolutePath,
                    projectHeaders.asFile.absolutePath,
                    rootProject.layout.projectDirectory.dir("native-build/bridge").asFile.absolutePath,
                ).joinToString(File.pathSeparator)}",
                "-Dplatform.linkpath=${install.dir("lib").asFile.absolutePath}",
                "io.github.aftrolle.ffmpegkmp.bindings.generated.${family.lowercase()}.**",
            )
        }
    }
}

tasks.register("buildJavaCppAndroidBindings") {
    group = "ffmpeg bindings"
    description = "Builds every generated JavaCPP JNI family for all configured Android ABIs"
    dependsOn(buildJavaCppAndroidBindings)
}

tasks.register<Zip>("assembleJavaCppAndroidRuntime") {
    group = "ffmpeg bindings"
    description = "Assembles an ignored local Android runtime AAR containing generated declarations and JNI libraries"
    dependsOn(buildJavaCppAndroidBindings)
    archiveFileName.set("ffmpegkmp-bindings-${selectedNativeProfile.get()}-local.aar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/android-runtime"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    outputs.cacheIf { false }

    from(layout.projectDirectory.file("src/androidPackaging/AndroidManifest.xml"))
    from(javaCppDeclarationsJar.flatMap { it.archiveFile }) {
        rename { "classes.jar" }
    }
    androidAbis.forEach { (abi, _) ->
        from(rootProject.layout.projectDirectory.dir("native-build/android/out/${selectedNativeProfile.get()}/$abi/lib")) {
            include("libavcodec.so", "libavdevice.so", "libavfilter.so", "libavformat.so")
            include("libavutil.so", "libswresample.so", "libswscale.so")
            into("jni/$abi")
        }
        javaCppFamilies.forEach { family ->
            from(layout.buildDirectory.dir("generated/javacpp-jni/android/$abi/$family")) {
                include("*.so")
                into("jni/$abi")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    dependsOn(buildJavaCppHostBindings)
    val profile = selectedNativeProfile.get()
    val install = rootProject.layout.projectDirectory.dir(
        "native-build/jvm/out/$profile/${hostMachine.get()}",
    )
    val jniPath = javaCppFamilies.joinToString(File.pathSeparator) { family ->
        layout.buildDirectory.dir("generated/javacpp-jni/${hostMachine.get()}/$family")
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain {
            // Kotlin does not support a JVM+Android intermediate source set.
            // Compile the shared JavaCPP implementation into each target while
            // keeping target-specific actual declarations in the standard roots.
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        androidMain {
            kotlin.srcDir("src/jvmAndroidMain/kotlin")
        }
        jvmMain.dependencies {
            implementation(libs.javacpp)
            implementation(files(javaCppDeclarationsJar))
        }
        androidMain.dependencies {
            implementation(libs.javacpp)
            implementation(files(javaCppDeclarationsJar))
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val nativeTargetName = name
        compilations.getByName("main").cinterops.create("ffmpeg") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/ffmpeg.def"))
            packageName("io.github.aftrolle.ffmpegkmp.bindings.cinterop")

            val profile = selectedNativeProfile.get()
            val install = rootProject.layout.projectDirectory.dir("native-build/apple/out/$profile/$nativeTargetName")
            val projectHeaders = layout.projectDirectory.dir("src/main/headers")
            val bridgeHeaders = rootProject.layout.projectDirectory.dir("native-build/bridge")
            compilerOpts(
                "-I${projectHeaders.asFile.absolutePath}",
                "-I${bridgeHeaders.asFile.absolutePath}",
                "-I${install.dir("include").asFile.absolutePath}",
            )
            includeDirs.headerFilterOnly(projectHeaders)
            includeDirs.headerFilterOnly(bridgeHeaders)
            includeDirs.allHeaders(install.dir("include"))
            extraOpts("-libraryPath", install.dir("lib").asFile.absolutePath)
        }
    }
}

tasks.matching { it.name == "compileKotlinJvm" }.configureEach {
    dependsOn(verifyJavaCppBindings)
}

// Kotlin/Native cinterop, the shared JVM/Android JNI generator, and Wasm
// generation will all consume prepareFfmpegHeaders as their common input. The
// resulting declarations are generated locally and remain LGPL-2.1-or-later.
