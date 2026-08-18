package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Locally built FFmpeg binaries must not enter remote Gradle caches")
abstract class FfmpegBuildTask : DefaultTask() {
    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val workDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty

    @get:Input abstract val profileName: Property<String>
    @get:Input abstract val targetName: Property<String>
    @get:Input abstract val targetKind: Property<String>
    @get:Input abstract val profileInheritance: ListProperty<String>
    @get:Input abstract val architecture: Property<String>
    @get:Input abstract val targetTriple: Property<String>
    @get:Input abstract val sdkName: Property<String>
    @get:Input abstract val deploymentTarget: Property<String>
    @get:Input abstract val androidApiLevel: Property<Int>
    @get:Input abstract val androidNdkDirectory: Property<String>
    @get:Input abstract val jobs: Property<Int>

    @get:Input abstract val buildPrograms: Property<Boolean>
    @get:Input abstract val buildDocumentation: Property<Boolean>
    @get:Input abstract val externalAutodetect: Property<Boolean>
    @get:Input abstract val network: Property<Boolean>
    @get:Input abstract val devices: Property<Boolean>
    @get:Input abstract val enableAvailableSystemFeatures: Property<Boolean>
    @get:Input abstract val disableEverything: Property<Boolean>
    @get:Input abstract val hardwareDecoding: Property<Boolean>
    @get:Input abstract val hardwareEncoding: Property<Boolean>
    @get:Input abstract val androidMediaCodec: Property<Boolean>
    @get:Input abstract val appleVideoToolbox: Property<Boolean>
    @get:Input abstract val appleAudioToolbox: Property<Boolean>

    @get:Input abstract val encoders: SetProperty<String>
    @get:Input abstract val decoders: SetProperty<String>
    @get:Input abstract val muxers: SetProperty<String>
    @get:Input abstract val demuxers: SetProperty<String>
    @get:Input abstract val parsers: SetProperty<String>
    @get:Input abstract val protocols: SetProperty<String>
    @get:Input abstract val filters: SetProperty<String>
    @get:Input abstract val inputDevices: SetProperty<String>
    @get:Input abstract val outputDevices: SetProperty<String>
    @get:Input abstract val hardwareAccelerators: SetProperty<String>
    @get:Input abstract val extraConfigureArgs: ListProperty<String>
    @get:Input abstract val extraCompilerArgs: ListProperty<String>
    @get:Input abstract val extraLinkerArgs: ListProperty<String>

    init {
        sdkName.convention("")
        deploymentTarget.convention("")
        targetTriple.convention("")
        androidApiLevel.convention(24)
        androidNdkDirectory.convention("")
        encoders.convention(emptySet())
        decoders.convention(emptySet())
        muxers.convention(emptySet())
        demuxers.convention(emptySet())
        parsers.convention(emptySet())
        protocols.convention(emptySet())
        filters.convention(emptySet())
        inputDevices.convention(emptySet())
        outputDevices.convention(emptySet())
        hardwareAccelerators.convention(emptySet())
        profileInheritance.convention(emptyList())
        extraConfigureArgs.convention(emptyList())
        extraCompilerArgs.convention(emptyList())
        extraLinkerArgs.convention(emptyList())
    }

    @TaskAction
    fun buildFfmpeg() {
        val source = sourceDirectory.get().asFile
        val configure = source.resolve("configure")
        require(configure.isFile) {
            "FFmpeg source is not initialized at ${source.absolutePath}; initialize the ffmpeg submodule first."
        }

        val work = workDirectory.get().asFile
        val install = installDirectory.get().asFile
        require(work.absolutePath.contains("native-build") && install.absolutePath.contains("native-build")) {
            "Refusing to clean native output outside native-build: $work / $install"
        }
        fileSystemOperations.delete { delete(work, install) }
        work.mkdirs()
        install.mkdirs()

        val arguments = mutableListOf<String>()
        arguments += "--prefix=${install.absolutePath}"
        arguments += if (buildPrograms.get()) "--enable-programs" else "--disable-programs"
        arguments += if (buildDocumentation.get()) "--enable-doc" else "--disable-doc"
        if (!externalAutodetect.get()) arguments += "--disable-autodetect"
        if (!network.get()) arguments += "--disable-network"
        if (!devices.get()) arguments += "--disable-devices"
        if (disableEverything.get()) arguments += "--disable-everything"
        arguments += listOf("--disable-debug", "--enable-pic")

        when (targetKind.get()) {
            "android" -> configureAndroid(arguments)
            "apple" -> configureApple(arguments)
            "jvm" -> configureJvm(arguments)
            else -> error("Unsupported FFmpeg target kind: ${targetKind.get()}")
        }

        addComponentFlags(arguments, "encoder", encoders.get())
        addComponentFlags(arguments, "decoder", decoders.get())
        addComponentFlags(arguments, "muxer", muxers.get())
        addComponentFlags(arguments, "demuxer", demuxers.get())
        addComponentFlags(arguments, "parser", parsers.get())
        addComponentFlags(arguments, "protocol", protocols.get())
        addComponentFlags(arguments, "filter", filters.get())
        addComponentFlags(arguments, "indev", inputDevices.get())
        addComponentFlags(arguments, "outdev", outputDevices.get())
        addComponentFlags(arguments, "hwaccel", hardwareAccelerators.get())
        arguments += extraConfigureArgs.get()

        val assessment = assessLicense(arguments)
        assessment.warnings.forEach { logger.warn("FFmpeg licence warning: $it") }

        val configureLog = work.resolve("configure.log")
        val output = ByteArrayOutputStream()
        val configureCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("bash", configure.absolutePath) + arguments
        } else {
            listOf(configure.absolutePath) + arguments
        }
        val configureResult = execOperations.exec {
            workingDir(work)
            commandLine(configureCommand)
            standardOutput = output
            errorOutput = output
            environment(reproducibleEnvironment())
            isIgnoreExitValue = true
        }
        configureLog.writeBytes(output.toByteArray())
        if (configureResult.exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            error("FFmpeg configure failed for ${targetName.get()}; see ${configureLog.absolutePath}")
        }
        logger.lifecycle(output.toString(Charsets.UTF_8).lineSequence().take(30).joinToString("\n"))

        execOperations.exec {
            workingDir(work)
            commandLine("make", "-j${jobs.get()}")
            environment(reproducibleEnvironment())
        }
        execOperations.exec {
            workingDir(work)
            commandLine("make", "install-libs", "install-headers")
            environment(reproducibleEnvironment())
        }

        verifyInstalledArtifacts(install)
        copyLicences(source, install)
        writeMetadata(source, install, arguments, assessment)
    }

    private fun configureAndroid(arguments: MutableList<String>) {
        val ndk = File(androidNdkDirectory.get())
        require(ndk.isDirectory) {
            "Android NDK was not found at ${ndk.absolutePath}. Set -Pffmpegkmp.android.ndkDir=<path>."
        }
        val hostTag = when {
            System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
            System.getProperty("os.name").lowercase().contains("linux") -> "linux-x86_64"
            System.getProperty("os.name").lowercase().contains("windows") -> "windows-x86_64"
            else -> error("Unsupported Android NDK host")
        }
        val toolchain = ndk.resolve("toolchains/llvm/prebuilt/$hostTag")
        val bin = toolchain.resolve("bin")
        require(bin.isDirectory) { "Invalid Android NDK LLVM toolchain: ${bin.absolutePath}" }
        val tripleWithApi = "${targetTriple.get()}${androidApiLevel.get()}"
        arguments += listOf(
            "--target-os=android",
            "--arch=${architecture.get()}",
            "--enable-cross-compile",
            "--sysroot=${toolchain.resolve("sysroot").absolutePath}",
            "--cc=${bin.resolve("clang").absolutePath}",
            "--cxx=${bin.resolve("clang++").absolutePath}",
            "--ar=${bin.resolve("llvm-ar").absolutePath}",
            "--nm=${bin.resolve("llvm-nm").absolutePath}",
            "--ranlib=${bin.resolve("llvm-ranlib").absolutePath}",
            "--strip=${bin.resolve("llvm-strip").absolutePath}",
            "--disable-static",
            "--enable-shared",
            "--disable-fast-unaligned",
        )
        if (architecture.get() == "x86" || architecture.get() == "x86_64") {
            arguments += "--disable-x86asm"
        }
        if (architecture.get() == "x86") {
            // FFmpeg's 32-bit x86 inline CABAC assembly contains absolute
            // relocations that Android's lld correctly rejects in shared PIC.
            arguments += "--disable-inline-asm"
        }
        val cFlags = listOf("--target=$tripleWithApi") + extraCompilerArgs.get()
        val ldFlags = listOf("--target=$tripleWithApi") + extraLinkerArgs.get()
        arguments += "--extra-cflags=${cFlags.joinToString(" ")}"
        arguments += "--extra-ldflags=${ldFlags.joinToString(" ")}"
        if (androidMediaCodec.get() && (hardwareDecoding.get() || hardwareEncoding.get())) {
            arguments += listOf("--enable-jni", "--enable-mediacodec")
            if (!hardwareDecoding.get()) androidMediaCodecDecoders.forEach { arguments += "--disable-decoder=$it" }
            if (!hardwareEncoding.get()) androidMediaCodecEncoders.forEach { arguments += "--disable-encoder=$it" }
        } else {
            arguments += listOf("--disable-jni", "--disable-mediacodec")
        }
        if (enableAvailableSystemFeatures.get()) arguments += "--enable-zlib"
    }

    private fun configureApple(arguments: MutableList<String>) {
        require(System.getProperty("os.name").lowercase().contains("mac")) {
            "Apple FFmpeg targets require macOS and Xcode."
        }
        val sdk = sdkName.get()
        val sdkPath = commandOutput("xcrun", "--sdk", sdk, "--show-sdk-path")
        val triple = targetTriple.get()
        arguments += listOf(
            "--target-os=darwin",
            "--arch=${architecture.get()}",
            "--enable-cross-compile",
            "--sysroot=$sdkPath",
            "--cc=xcrun --sdk $sdk clang",
            "--ar=xcrun --sdk $sdk ar",
            "--nm=xcrun --sdk $sdk nm",
            "--ranlib=xcrun --sdk $sdk ranlib",
            "--strip=xcrun --sdk $sdk strip",
            "--enable-static",
            "--disable-shared",
        )
        if (targetName.get().startsWith("watchos")) arguments += "--disable-asm"
        val cFlags = listOf("-target", triple, "-isysroot", sdkPath) + extraCompilerArgs.get()
        val ldFlags = listOf("-target", triple, "-isysroot", sdkPath) + extraLinkerArgs.get()
        arguments += "--extra-cflags=${cFlags.joinToString(" ")}"
        arguments += "--extra-ldflags=${ldFlags.joinToString(" ")}"

        val watch = targetName.get().startsWith("watchos")
        if (!watch && appleVideoToolbox.get() && (hardwareDecoding.get() || hardwareEncoding.get())) {
            arguments += "--enable-videotoolbox"
            if (!hardwareDecoding.get()) appleVideoToolboxDecoders.forEach { arguments += "--disable-hwaccel=$it" }
            if (!hardwareEncoding.get()) appleVideoToolboxEncoders.forEach { arguments += "--disable-encoder=$it" }
        } else {
            arguments += "--disable-videotoolbox"
        }
        if (!watch && appleAudioToolbox.get() && (hardwareDecoding.get() || hardwareEncoding.get())) {
            arguments += "--enable-audiotoolbox"
            if (!hardwareDecoding.get()) appleAudioToolboxDecoders.forEach { arguments += "--disable-decoder=$it" }
            if (!hardwareEncoding.get()) appleAudioToolboxEncoders.forEach { arguments += "--disable-encoder=$it" }
        } else {
            arguments += "--disable-audiotoolbox"
        }
        if (enableAvailableSystemFeatures.get()) {
            arguments += listOf(
                "--enable-bzlib", "--enable-iconv", "--enable-zlib",
                "--extra-libs=-liconv",
            )
        }
    }

    private fun configureJvm(arguments: MutableList<String>) {
        val os = targetName.get().substringBefore('-')
        arguments += listOf("--disable-static", "--enable-shared")
        when (os) {
            "macos" -> {
                require(System.getProperty("os.name").lowercase().contains("mac")) {
                    "macOS FFmpeg targets require a macOS host with Xcode"
                }
                val sdkPath = commandOutput("xcrun", "--sdk", "macosx", "--show-sdk-path")
                arguments += listOf(
                    "--target-os=darwin",
                    "--arch=${architecture.get()}",
                    "--enable-cross-compile",
                    "--sysroot=$sdkPath",
                    "--cc=xcrun --sdk macosx clang",
                    "--ar=xcrun --sdk macosx ar",
                    "--nm=xcrun --sdk macosx nm",
                    "--ranlib=xcrun --sdk macosx ranlib",
                    "--strip=xcrun --sdk macosx strip",
                    "--install-name-dir=@rpath",
                )
                if (architecture.get().startsWith("x86")) arguments += "--disable-x86asm"
                val cFlags = listOf(
                    "-target", targetTriple.get(), "-isysroot", sdkPath,
                ) + extraCompilerArgs.get()
                val ldFlags = listOf(
                    "-target", targetTriple.get(), "-isysroot", sdkPath,
                ) + extraLinkerArgs.get()
                arguments += "--extra-cflags=${cFlags.joinToString(" ")}"
                arguments += "--extra-ldflags=${ldFlags.joinToString(" ")}"
                if (appleVideoToolbox.get() && (hardwareDecoding.get() || hardwareEncoding.get())) {
                    arguments += "--enable-videotoolbox"
                } else arguments += "--disable-videotoolbox"
                if (appleAudioToolbox.get() && (hardwareDecoding.get() || hardwareEncoding.get())) {
                    arguments += "--enable-audiotoolbox"
                } else arguments += "--disable-audiotoolbox"
                if (enableAvailableSystemFeatures.get()) {
                    arguments += listOf(
                        "--enable-bzlib", "--enable-iconv", "--enable-zlib",
                        "--extra-libs=-liconv",
                    )
                }
            }
            "linux" -> {
                arguments += listOf(
                    "--target-os=linux", "--arch=${architecture.get()}", "--disable-vaapi",
                    "--disable-vdpau", "--disable-vulkan", "--disable-opencl",
                )
                if (architecture.get().startsWith("x86")) arguments += "--disable-x86asm"
            }
            "windows" -> arguments += listOf("--target-os=mingw32", "--arch=x86_64", "--disable-x86asm")
            else -> error("Unsupported JVM native host: $os")
        }
        if (os != "macos" && extraCompilerArgs.get().isNotEmpty()) {
            arguments += "--extra-cflags=${extraCompilerArgs.get().joinToString(" ")}"
        }
        if (os != "macos" && extraLinkerArgs.get().isNotEmpty()) {
            arguments += "--extra-ldflags=${extraLinkerArgs.get().joinToString(" ")}"
        }
    }

    private fun addComponentFlags(arguments: MutableList<String>, type: String, values: Set<String>) {
        values.sorted().forEach { arguments += "--enable-$type=$it" }
    }

    private fun verifyInstalledArtifacts(install: File) {
        val include = install.resolve("include")
        require(include.isDirectory && include.walkTopDown().any { it.isFile && it.extension == "h" }) {
            "FFmpeg did not install public headers for ${targetName.get()}"
        }
        val extension = when (targetKind.get()) {
            "android" -> "so"
            "apple" -> "a"
            "jvm" -> when (targetName.get().substringBefore('-')) {
                "macos" -> "dylib"
                "windows" -> "dll"
                else -> "so"
            }
            else -> error("Unsupported target kind: ${targetKind.get()}")
        }
        val missing = ffmpegLibraries.filterNot { library ->
            val exact = install.resolve("lib/lib$library.$extension")
            exact.isFile || install.resolve("lib").listFiles().orEmpty().any {
                it.isFile && it.name.startsWith("lib$library") && it.extension == extension
            }
        }
        require(missing.isEmpty()) {
            "FFmpeg did not install expected libraries for ${targetName.get()}: ${missing.joinToString()}"
        }
    }

    private fun reproducibleEnvironment(): Map<String, String> = mapOf(
        "LC_ALL" to "C",
        "LANG" to "C",
        "TZ" to "UTC",
        "ZERO_AR_DATE" to "1",
    )

    private fun copyLicences(source: File, install: File) {
        val destination = install.resolve("share/licenses/ffmpeg")
        destination.mkdirs()
        listOf("COPYING.LGPLv2.1", "COPYING.LGPLv3", "COPYING.GPLv2", "COPYING.GPLv3", "LICENSE.md")
            .map(source::resolve)
            .filter(File::isFile)
            .forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }
        install.resolve("DISCLAIMER.txt").writeText(
            """
            This locally generated FFmpeg binary has not been reviewed for licence, patent,
            export-control, app-store, or redistribution compliance. The builder and any
            downstream distributor are responsible for the exact configuration and use.
            """.trimIndent() + "\n",
        )
    }

    private fun writeMetadata(
        source: File,
        install: File,
        arguments: List<String>,
        initialAssessment: LicenseAssessment,
    ) {
        val configHeader = workDirectory.get().asFile.resolve("config.h")
        val configuredNonfree = configHeader.takeIf(File::isFile)?.readText()?.contains("#define CONFIG_NONFREE 1") == true
        val configuredGpl = configHeader.takeIf(File::isFile)?.readText()?.contains("#define CONFIG_GPL 1") == true
        val assessment = when {
            configuredNonfree -> initialAssessment.copy(mode = "nonfree", suffix = "-nonfree", redistributable = false)
            configuredGpl && !initialAssessment.mode.startsWith("gpl") -> initialAssessment.copy(mode = "gpl", suffix = "-gpl")
            else -> initialAssessment
        }
        val revision = runCatching { commandOutput("git", "rev-parse", "HEAD", workingDirectory = source) }
            .getOrDefault("unknown")
        val dirty = runCatching { commandOutput("git", "status", "--porcelain", workingDirectory = source).isNotEmpty() }
            .getOrDefault(false)
        val toolchain = when (targetKind.get()) {
            "apple" -> runCatching { commandOutput("xcodebuild", "-version") }.getOrDefault("unknown")
            "android" -> File(androidNdkDirectory.get()).resolve("source.properties").takeIf(File::isFile)?.readText()?.trim() ?: "unknown"
            else -> runCatching { commandOutput("cc", "--version").lineSequence().first() }.getOrDefault("unknown")
        }
        val artifacts = install.walkTopDown()
            .filter { it.isFile && it.name != "build-manifest.json" }
            .sortedBy { it.relativeTo(install).invariantSeparatorsPath }
            .map { it.relativeTo(install).invariantSeparatorsPath to sha256(it) }
            .toList()
        install.resolve("build-manifest.json").writeText(buildString {
            appendLine("{")
            appendLine("  \"ffmpegRevision\": \"${revision.jsonEscaped()}\",")
            appendLine("  \"sourceDirty\": $dirty,")
            appendLine("  \"profile\": \"${profileName.get().jsonEscaped()}\",")
            appendLine("  \"profileInheritance\": [${profileInheritance.get().joinToString(", ") { "\"${it.jsonEscaped()}\"" }}],")
            appendLine("  \"target\": \"${targetName.get().jsonEscaped()}\",")
            appendLine("  \"licenceAssessment\": \"${assessment.mode}\",")
            appendLine("  \"redistributable\": ${assessment.redistributable},")
            val dependencies = arguments.asSequence()
                .filter { it.startsWith("--enable-lib") }
                .map { it.removePrefix("--enable-").substringBefore('=') }
                .distinct()
                .sorted()
                .toList()
            appendLine("  \"externalDependencies\": [${dependencies.joinToString(", ") { "\"${it.jsonEscaped()}\"" }}],")
            val knownDependencies = arguments.asSequence()
                .filter {
                    it.startsWith("--enable-lib") ||
                        it in setOf("--enable-bzlib", "--enable-iconv", "--enable-zlib")
                }
                .map { it.removePrefix("--enable-").substringBefore('=') }
                .distinct()
                .sorted()
                .toList()
            appendLine("  \"dependencies\": [${knownDependencies.joinToString(", ") { "\"${it.jsonEscaped()}\"" }}],")
            appendLine("  \"toolchain\": \"${toolchain.jsonEscaped()}\",")
            appendLine("  \"configureArguments\": [")
            arguments.forEachIndexed { index, argument ->
                append("    \"").append(argument.jsonEscaped()).append('"')
                if (index != arguments.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"artifacts\": {")
            artifacts.forEachIndexed { index, (path, hash) ->
                append("    \"").append(path.jsonEscaped()).append("\": \"").append(hash).append('"')
                if (index != artifacts.lastIndex) append(',')
                appendLine()
            }
            appendLine("  }")
            appendLine("}")
        })
    }

    private companion object {
        val androidMediaCodecDecoders = listOf(
            "aac_mediacodec", "amrnb_mediacodec", "amrwb_mediacodec", "av1_mediacodec",
            "h264_mediacodec", "hevc_mediacodec", "mp3_mediacodec", "mpeg2_mediacodec",
            "mpeg4_mediacodec", "vp8_mediacodec", "vp9_mediacodec",
        )
        val androidMediaCodecEncoders = listOf(
            "av1_mediacodec", "h264_mediacodec", "hevc_mediacodec", "mpeg4_mediacodec", "vp8_mediacodec",
        )
        val appleVideoToolboxDecoders = listOf(
            "av1_videotoolbox", "h263_videotoolbox", "h264_videotoolbox", "hevc_videotoolbox",
            "mpeg1_videotoolbox", "mpeg2_videotoolbox", "mpeg4_videotoolbox", "prores_videotoolbox",
            "prores_raw_videotoolbox", "vp9_videotoolbox",
        )
        val appleVideoToolboxEncoders = listOf(
            "h264_videotoolbox", "hevc_videotoolbox", "prores_videotoolbox",
        )
        val appleAudioToolboxDecoders = listOf(
            "aac_at", "ac3_at", "adpcm_ima_qt_at", "alac_at", "amr_nb_at", "eac3_at", "gsm_ms_at",
            "ilbc_at", "mp1_at", "mp2_at", "mp3_at", "pcm_alaw_at", "pcm_mulaw_at", "qdmc_at", "qdm2_at",
        )
        val appleAudioToolboxEncoders = listOf("aac_at", "alac_at", "ilbc_at", "pcm_alaw_at", "pcm_mulaw_at")
    }
}
