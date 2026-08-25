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
val prepareHostFfmpegHeaders = selectedNativeProfileTaskSuffix.zip(hostMachine) { profileSuffix, machine ->
    val machineSuffix = machine.split('-', '_').joinToString("") { part ->
        part.replaceFirstChar(Char::titlecase)
    }
    ":native-build:jvm:prepareFfmpeg${profileSuffix}${machineSuffix}Headers"
}
val javaCppGenerator = configurations.create("javaCppGenerator")
val androidApiLevel = libs.versions.android.minSdk
val androidNdkVersion = libs.versions.android.ndk
val jvmBytecodeTarget = libs.versions.jvm.bytecode.map(String::toInt)
val bridgeSourceDirectory = rootProject.layout.projectDirectory.dir("native-build/bridge")
val nativeJvmInstall = selectedNativeProfile.zip(hostMachine) { profile, machine ->
    rootProject.layout.projectDirectory.dir("native-build/jvm/out/$profile/$machine")
}
val nativeJvmHeaders = selectedNativeProfile.zip(hostMachine) { profile, machine ->
    rootProject.layout.projectDirectory.dir("native-build/jvm/headers/$profile/$machine")
}

val stageBindingNotices = tasks.register<Sync>("stageBindingNotices") {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-FFmpegKMP-bindings.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
        rename { "NOTICE-FFmpegKMP.txt" }
    }
    into(layout.buildDirectory.dir("generated/binding-notices"))
}

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
    options.release.set(jvmBytecodeTarget)
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
        dependsOn(prepareHostFfmpegHeaders.get())
        classpath = javaCppGenerator
        mainClass.set("org.bytedeco.javacpp.tools.Builder")

        val headers = nativeJvmHeaders
        val projectHeaders = layout.projectDirectory.dir("src/main/headers")
        val generated = layout.buildDirectory.dir("generated/javacpp/$family")
        val presetClasses = compileJavaCppPresets.flatMap { it.destinationDirectory }
        inputs.dir(headers.map { it.dir("include") })
        inputs.dir(projectHeaders)
        inputs.dir(bridgeSourceDirectory)
        inputs.files(compileJavaCppPresets.map { it.outputs.files })
        outputs.dir(generated)
        outputs.cacheIf { false }

        args(
            "-classpath", presetClasses.get().asFile.absolutePath,
            "-d", generated.get().asFile.absolutePath,
            "-nogenerate",
            "-Dplatform.includepath=${listOf(
                headers.get().dir("include").asFile.absolutePath,
                projectHeaders.asFile.absolutePath,
                bridgeSourceDirectory.asFile.absolutePath,
            ).joinToString(File.pathSeparator)}",
            "io.github.aftrolle.ffmpegkmp.bindings.javacpp.$family",
        )
        doLast {
            generated.get().asFileTree.matching { include("**/*.java") }.forEach { source ->
                val withoutBlockComments = source.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
                source.writeText(
                    "// SPDX-License-Identifier: LGPL-2.1-or-later\n" +
                        "// Generated by JavaCPP from FFmpeg headers; see the artifact licence and notices.\n\n" +
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
    options.release.set(jvmBytecodeTarget)
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
val javaCppDeclarations = files(javaCppDeclarationsJar.flatMap { it.archiveFile })
    .builtBy(javaCppDeclarationsJar)

tasks.withType<Jar>().matching { it.name != "javaCppDeclarationsJar" }.configureEach {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-FFmpegKMP-bindings.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
        rename { "NOTICE-FFmpegKMP.txt" }
    }
}

tasks.named<Jar>("jvmJar") {
    dependsOn(javaCppDeclarationsJar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(javaCppDeclarationsJar.flatMap { it.archiveFile }.map(::zipTree)) {
        exclude("META-INF/MANIFEST.MF")
    }
}

tasks.withType<Jar>().matching {
    it.name == "jvmSourcesJar" || it.name == "androidSourcesJar"
}.configureEach {
    dependsOn(generateJavaCppBindings)
    from(javaCppFamilies.map { layout.buildDirectory.dir("generated/javacpp/$it") })
    from(layout.projectDirectory.dir("src/javacpp/java"))
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

        val install = nativeJvmInstall
        val projectHeaders = layout.projectDirectory.dir("src/main/headers")
        val output = layout.buildDirectory.dir(hostMachine.map { "generated/javacpp-jni/$it/$family" })
        val generatedClasses = verifyJavaCppBindings.flatMap { it.destinationDirectory }
        val presetClasses = compileJavaCppPresets.flatMap { it.destinationDirectory }
        inputs.dir(install.map { it.dir("include") })
        inputs.dir(install.map { it.dir("lib") })
        inputs.dir(projectHeaders)
        inputs.dir(bridgeSourceDirectory)
        inputs.files(verifyJavaCppBindings.map { it.outputs.files })
        outputs.dir(output)
        outputs.cacheIf { false }

        args(
            "-classpath", listOf(
                generatedClasses.get().asFile,
                presetClasses.get().asFile,
            ).joinToString(File.pathSeparator),
            "-d", output.get().asFile.absolutePath,
            "-nodelete",
            "-Dplatform.includepath=${listOf(
                install.get().dir("include").asFile.absolutePath,
                projectHeaders.asFile.absolutePath,
                bridgeSourceDirectory.asFile.absolutePath,
            ).joinToString(File.pathSeparator)}",
            "-Dplatform.linkpath=${install.get().dir("lib").asFile.absolutePath}",
            "io.github.aftrolle.ffmpegkmp.bindings.generated.${family.lowercase()}.**",
        )
    }
}

tasks.register("buildJavaCppHostBindings") {
    group = "ffmpeg bindings"
    description = "Builds all locally-generated JavaCPP JNI libraries for the current JVM machine"
    dependsOn(buildJavaCppHostBindings)
}

val stageJavaCppHostRuntime = tasks.register<Sync>("stageJavaCppHostRuntime") {
    group = "ffmpeg bindings"
    description = "Stages the local FFmpeg and generated JNI runtime for the current JVM machine"
    dependsOn(buildJavaCppHostBindings)

    from(nativeJvmInstall.map { it.dir("lib") }) {
        include("*.dylib", "*.so", "*.so.*", "*.dll")
        into("lib")
    }
    javaCppFamilies.forEach { family ->
        from(layout.buildDirectory.dir(hostMachine.map { "generated/javacpp-jni/$it/$family" })) {
            include("*.dylib", "*.so", "*.so.*", "*.dll")
            into("jni")
        }
    }
    from(stageBindingNotices)
    from(nativeJvmInstall.map { it.file("build-manifest.json") })
    from(nativeJvmInstall.map { it.dir("share/licenses") }) {
        into("licenses")
    }
    into(layout.buildDirectory.dir(hostMachine.map { "generated/host-runtime/$it" }))
}

tasks.register<Zip>("assembleJavaCppHostRuntime") {
    group = "ffmpeg bindings"
    description = "Packages the ignored local FFmpeg/JNI runtime for the current JVM machine"
    dependsOn(stageJavaCppHostRuntime)
    archiveFileName.set(hostMachine.map { "ffmpegkmp-runtime-$it-local.zip" })
    destinationDirectory.set(layout.buildDirectory.dir("generated/host-runtime-archives"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    outputs.cacheIf { false }
    from(stageJavaCppHostRuntime)
}

val androidAbis = linkedMapOf(
    "armeabi-v7a" to Triple("android-arm", "armv7a-linux-androideabi", "ArmeabiV7a"),
    "arm64-v8a" to Triple("android-arm64", "aarch64-linux-android", "Arm64V8a"),
    "x86" to Triple("android-x86", "i686-linux-android", "X86"),
    "x86_64" to Triple("android-x86_64", "x86_64-linux-android", "X8664"),
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
val androidNdkDirectory = androidSdkDirectory.zip(androidNdkVersion) { sdk, ndk -> "$sdk/ndk/$ndk" }
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
            dependsOn(selectedNativeProfileTaskSuffix.map {
                ":native-build:android:buildFfmpeg$it$taskSuffix"
            }.get())
            classpath = javaCppGenerator + files(
                verifyJavaCppBindings.map { it.destinationDirectory },
                compileJavaCppPresets.map { it.destinationDirectory },
            )
            mainClass.set("org.bytedeco.javacpp.tools.Builder")

            val install = selectedNativeProfile.map {
                rootProject.layout.projectDirectory.dir("native-build/android/out/$it/$abi")
            }
            val projectHeaders = layout.projectDirectory.dir("src/main/headers")
            val output = layout.buildDirectory.dir("generated/javacpp-jni/android/$abi/$family")
            val generatedClasses = verifyJavaCppBindings.flatMap { it.destinationDirectory }
            val presetClasses = compileJavaCppPresets.flatMap { it.destinationDirectory }
            inputs.dir(install.map { it.dir("include") })
            inputs.dir(install.map { it.dir("lib") })
            inputs.dir(projectHeaders)
            inputs.dir(bridgeSourceDirectory)
            inputs.files(verifyJavaCppBindings.map { it.outputs.files })
            outputs.dir(output)
            outputs.cacheIf { false }

            args(
                "-classpath", listOf(
                    generatedClasses.get().asFile,
                    presetClasses.get().asFile,
                ).joinToString(File.pathSeparator),
                "-d", output.get().asFile.absolutePath,
                "-nodelete",
                "-properties", platform,
                "-Dplatform.root=${androidNdkDirectory.get()}/",
                "-Dplatform.compiler=toolchains/llvm/prebuilt/${androidNdkHost.get()}/bin/" +
                    "$compiler${androidApiLevel.get()}-clang++",
                "-Dplatform.includepath=${listOf(
                    install.get().dir("include").asFile.absolutePath,
                    projectHeaders.asFile.absolutePath,
                    bridgeSourceDirectory.asFile.absolutePath,
                ).joinToString(File.pathSeparator)}",
                "-Dplatform.linkpath=${install.get().dir("lib").asFile.absolutePath}",
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

val assembleJavaCppAndroidRuntime = tasks.register<Zip>("assembleJavaCppAndroidRuntime") {
    group = "ffmpeg bindings"
    description = "Assembles an ignored binary-only Android runtime AAR containing FFmpeg and JNI libraries"
    dependsOn(buildJavaCppAndroidBindings)
    archiveFileName.set(selectedNativeProfile.map { "ffmpegkmp-runtime-$it-local.aar" })
    destinationDirectory.set(layout.buildDirectory.dir("generated/android-runtime"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    outputs.cacheIf { false }

    from(layout.projectDirectory.file("src/androidPackaging/AndroidManifest.xml"))
    from(stageBindingNotices)
    androidAbis.forEach { (abi, _) ->
        from(selectedNativeProfile.map {
            rootProject.layout.projectDirectory.dir("native-build/android/out/$it/$abi/lib")
        }) {
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

val stageWasmRuntime = tasks.register<Sync>("stageWasmRuntime") {
    group = "ffmpeg bindings"
    description = "Stages the ignored local FFmpeg WebAssembly runtime and worker"
    dependsOn(":native-build:wasm:linkFfmpegKmpWorker")
    from(selectedNativeProfile.map { profile ->
        rootProject.layout.projectDirectory.dir("native-build/wasm/build/worker/$profile")
    }) {
        include("ffmpegkmp.mjs", "ffmpegkmp.wasm")
    }
    from(layout.projectDirectory.dir("src/wasmJsMain/resources")) {
        include("ffmpegkmp-worker.mjs")
    }
    into(layout.buildDirectory.dir(selectedNativeProfile.map { "generated/wasm-runtime/$it" }))
}

tasks.register<Zip>("assembleWasmRuntime") {
    group = "ffmpeg bindings"
    description = "Packages the ignored local FFmpeg WebAssembly runtime and worker"
    dependsOn(stageWasmRuntime)
    archiveFileName.set(selectedNativeProfile.map { "ffmpegkmp-wasm-runtime-$it-local.zip" })
    destinationDirectory.set(layout.buildDirectory.dir("generated/wasm-runtime-archives"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    outputs.cacheIf { false }
    from(stageWasmRuntime)
}

tasks.withType<Zip>().matching { it.name == "bundleAndroidMainAar" }.configureEach {
    from(stageBindingNotices)
}

tasks.named<Test>("jvmTest") {
    dependsOn(buildJavaCppHostBindings)
    val nativeLibraryPath = nativeJvmInstall.get().dir("lib").asFile.absolutePath
    val jniPath = layout.buildDirectory.get().let { buildDirectory ->
        val machine = hostMachine.get()
        javaCppFamilies.joinToString(File.pathSeparator) { family ->
            buildDirectory.dir("generated/javacpp-jni/$machine/$family").asFile.absolutePath
        }
    }
    systemProperty("ffmpegkmp.jni.path", jniPath)
    systemProperty(
        "java.library.path",
        "$jniPath${File.pathSeparator}$nativeLibraryPath",
    )
    environment("DYLD_LIBRARY_PATH", nativeLibraryPath)
    environment("LD_LIBRARY_PATH", nativeLibraryPath)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.okio)
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
            api(libs.javacpp)
            implementation(javaCppDeclarations)
        }
        androidMain.dependencies {
            api(libs.javacpp)
            implementation(javaCppDeclarations)
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val nativeTargetName = name
        val nativeTargetTaskSuffix = nativeTargetName.replaceFirstChar(Char::titlecase)
        val nativeProfileTaskSuffix = selectedNativeProfileTaskSuffix.get()
        compilations.getByName("main").cinterops.create("ffmpeg") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/ffmpeg.def"))
            packageName("io.github.aftrolle.ffmpegkmp.bindings.cinterop")

            val profile = selectedNativeProfile.get()
            val headers = rootProject.layout.projectDirectory.dir("native-build/apple/headers/$profile/$nativeTargetName")
            val projectHeaders = layout.projectDirectory.dir("src/main/headers")
            val bridgeHeaders = rootProject.layout.projectDirectory.dir("native-build/bridge")
            compilerOpts(
                "-I${projectHeaders.asFile.absolutePath}",
                "-I${bridgeHeaders.asFile.absolutePath}",
                "-I${headers.dir("include").asFile.absolutePath}",
            )
            includeDirs.headerFilterOnly(projectHeaders)
            includeDirs.headerFilterOnly(bridgeHeaders)
            includeDirs.allHeaders(headers.dir("include"))
        }
        tasks.named("cinteropFfmpeg$nativeTargetTaskSuffix").configure {
            dependsOn(":native-build:apple:prepareFfmpeg${nativeProfileTaskSuffix}${nativeTargetTaskSuffix}Headers")
        }
    }
}

tasks.matching { it.name == "compileKotlinJvm" }.configureEach {
    dependsOn(verifyJavaCppBindings)
}

// Kotlin/Native cinterop and JavaCPP declaration generation consume configured
// header-only native tasks. Full FFmpeg runtimes remain local build/test inputs
// and are never required to assemble a Maven publication.
