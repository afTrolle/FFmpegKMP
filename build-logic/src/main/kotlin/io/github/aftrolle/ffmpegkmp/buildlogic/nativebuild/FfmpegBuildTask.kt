package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
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

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bridgeSourceDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependenciesInstallDirectory: DirectoryProperty

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
    @get:Input abstract val emscriptenDirectory: Property<String>
    @get:Input abstract val jobs: Property<Int>
    @get:Input abstract val buildRuntime: Property<Boolean>

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
    @get:Input abstract val thirdPartyLibraries: SetProperty<String>
    @get:Input abstract val extraConfigureArgs: ListProperty<String>
    @get:Input abstract val extraCompilerArgs: ListProperty<String>
    @get:Input abstract val extraLinkerArgs: ListProperty<String>

    init {
        sdkName.convention("")
        deploymentTarget.convention("")
        targetTriple.convention("")
        androidApiLevel.convention(24)
        androidNdkDirectory.convention("")
        emscriptenDirectory.convention("")
        buildRuntime.convention(true)
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
        thirdPartyLibraries.convention(emptySet())
        profileInheritance.convention(emptyList())
        extraConfigureArgs.convention(emptyList())
        extraCompilerArgs.convention(emptyList())
        extraLinkerArgs.convention(emptyList())
    }

    @TaskAction
    fun buildFfmpeg() {
        val source = sourceDirectory.get().asFile
        require(source.resolve("configure").isFile) {
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

        val preparedSource = prepareSourceWithIoProtocol(source, work)
        val configure = preparedSource.resolve("configure")

        val arguments = mutableListOf<String>()
        arguments += "--prefix=${install.absolutePath}"
        if (!buildPrograms.get()) arguments += "--disable-programs"
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
            "wasm" -> configureWasm(arguments)
            else -> error("Unsupported FFmpeg target kind: ${targetKind.get()}")
        }

        addComponentFlags(arguments, "encoder", encoders.get())
        addComponentFlags(arguments, "decoder", decoders.get())
        addComponentFlags(arguments, "muxer", muxers.get())
        addComponentFlags(arguments, "demuxer", demuxers.get())
        addComponentFlags(arguments, "parser", parsers.get())
        addComponentFlags(arguments, "protocol", protocols.get())
        arguments += "--enable-protocol=ffmpegkmp"
        addComponentFlags(arguments, "filter", filters.get())
        addComponentFlags(arguments, "indev", inputDevices.get())
        addComponentFlags(arguments, "outdev", outputDevices.get())
        addComponentFlags(arguments, "hwaccel", hardwareAccelerators.get())
        addThirdPartyFlags(arguments)
        arguments += extraConfigureArgs.get()

        val assessment = assessLicense(arguments)
        assessment.warnings.forEach { logger.warn("FFmpeg licence warning: $it") }

        val configureLog = work.resolve("configure.log")
        val output = ByteArrayOutputStream()
        val configureInvocation = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("bash", configure.absolutePath) + arguments
        } else {
            listOf(configure.absolutePath) + arguments
        }
        val configureCommand = if (targetKind.get() == "wasm") {
            listOf(emscriptenTool("emconfigure")) + configureInvocation
        } else {
            configureInvocation
        }
        val configureResult = execOperations.exec {
            workingDir(work)
            commandLine(configureCommand)
            standardOutput = output
            errorOutput = output
            configureBuildEnvironment()
            isIgnoreExitValue = true
        }
        configureLog.writeBytes(output.toByteArray())
        if (configureResult.exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            error("FFmpeg configure failed for ${targetName.get()}; see ${configureLog.absolutePath}")
        }
        logger.lifecycle(output.toString(Charsets.UTF_8).lineSequence().take(30).joinToString("\n"))

        if (buildRuntime.get()) {
            execOperations.exec {
                workingDir(work)
                commandLine(
                    makeCommand(
                        "-j${jobs.get()}",
                        "install-libs",
                        "install-headers",
                        "ffmpegkmp-fftools-objects",
                    ),
                )
                configureBuildEnvironment()
            }

            buildBridge(work, install)

            verifyInstalledArtifacts(install)
            copyLicences(source, install)
            writeMetadata(source, install, arguments, assessment)
        } else {
            execOperations.exec {
                workingDir(work)
                commandLine(makeCommand("-j${jobs.get()}", "install-headers"))
                configureBuildEnvironment()
            }
            verifyInstalledHeaders(install)
        }
    }

    private fun prepareSourceWithIoProtocol(source: File, work: File): File {
        val prepared = work.resolve("source")
        fileSystemOperations.sync {
            from(source) { exclude(".git/**") }
            into(prepared)
        }
        fileSystemOperations.copy {
            from(bridgeSourceDirectory.file("ffmpegkmp_protocol.c"))
            into(prepared.resolve("libavformat"))
        }

        val protocols = prepared.resolve("libavformat/protocols.c")
        val protocolMarker = "extern const URLProtocol ff_file_protocol;"
        val protocolText = protocols.readText()
        require(protocolMarker in protocolText) { "Could not locate FFmpeg protocol declaration marker" }
        protocols.writeText(
            protocolText.replace(
                protocolMarker,
                "$protocolMarker\nextern const URLProtocol ff_ffmpegkmp_protocol;",
            ),
        )

        val hls = prepared.resolve("libavformat/hls.c")
        val hlsMarker = "if (av_strstart(proto_name, \"file\", NULL)) {"
        val hlsText = hls.readText()
        require(hlsMarker in hlsText) { "Could not locate FFmpeg HLS file-protocol marker" }
        hls.writeText(
            hlsText.replace(
                hlsMarker,
                "if (av_strstart(proto_name, \"file\", NULL) || " +
                    "av_strstart(proto_name, \"ffmpegkmp\", NULL)) {",
            ),
        )

        val makefile = prepared.resolve("libavformat/Makefile")
        makefile.appendText("\nOBJS-\$(CONFIG_FFMPEGKMP_PROTOCOL) += ffmpegkmp_protocol.o\n")

        // The bridge includes the two CLI main sources through controlled entry
        // wrappers. Build only their supporting objects here: the standalone
        // ffmpeg/ffprobe main objects and executable link steps are not used.
        prepared.resolve("fftools/Makefile").appendText(
            """

            .PHONY: ffmpegkmp-fftools-objects
            ffmpegkmp-fftools-objects: ${'$'}(sort ${'$'}(filter-out fftools/ffmpeg.o fftools/ffprobe.o,${'$'}(filter fftools/%.o,${'$'}(OBJS-ffmpeg) ${'$'}(OBJS-ffprobe))))
            """.trimIndent() + "\n",
        )
        return prepared
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

    private fun configureWasm(arguments: MutableList<String>) {
        val requiredTools = listOf("emcc", "em++", "emar", "emnm", "emranlib", "emconfigure", "emmake")
        requiredTools.forEach { tool ->
            require(commandExists(emscriptenTool(tool))) {
                "Emscripten tool '$tool' was not found. Source emsdk_env.sh or set " +
                    "-Pffmpegkmp.wasm.emscriptenDir=<directory-containing-emcc>."
            }
        }
        arguments += listOf(
            "--target-os=none",
            "--arch=${architecture.get()}",
            "--enable-cross-compile",
            "--cc=${emscriptenTool("emcc")}",
            "--cxx=${emscriptenTool("em++")}",
            "--ar=${emscriptenTool("emar")}",
            "--nm=${emscriptenTool("emnm")}",
            "--ranlib=${emscriptenTool("emranlib")}",
            "--enable-static",
            "--disable-shared",
            "--disable-stripping",
            // The pinned fftools scheduler requires pthreads even when codec-level
            // parallelism is disabled. Emscripten runs these inside the command worker.
            "--enable-pthreads",
            "--disable-w32threads",
            "--disable-os2threads",
            "--disable-runtime-cpudetect",
        )
        val systemFeatureFlags = if (enableAvailableSystemFeatures.get()) {
            arguments += "--enable-zlib"
            listOf("-sUSE_ZLIB=1")
        } else {
            emptyList()
        }
        arguments += "--extra-cflags=${(listOf("-pthread") + systemFeatureFlags + extraCompilerArgs.get()).joinToString(" ")}"
        arguments += "--extra-ldflags=${(listOf("-pthread") + systemFeatureFlags + extraLinkerArgs.get()).joinToString(" ")}"
    }

    private fun makeCommand(vararg arguments: String): List<String> =
        if (targetKind.get() == "wasm") {
            listOf(emscriptenTool("emmake"), "make") + arguments
        } else {
            listOf("make") + arguments
        }

    private fun buildBridge(work: File, install: File) {
        val bridge = bridgeSourceDirectory.get().asFile
        val fftoolsObjects = work.resolve("fftools").walkTopDown()
            .filter { file ->
                file.isFile && file.extension == "o" &&
                    file.name !in setOf("ffmpeg.o", "ffprobe.o", "ffplay.o", "ffplay_renderer.o")
            }
            .map(File::getAbsolutePath)
            .toMutableList()
        // compat/android/binder.o is deliberately NOT linked: it starts a binder thread
        // pool for the standalone CLI, and setting its maximum thread count aborts when
        // the pool is already running inside an app. ffmpegkmp_bridge.c provides an
        // app-safe android_binder_threadpool_init_if_required implementation instead.
        execOperations.exec {
            workingDir(work)
            commandLine(
                makeCommand(
                    "-f", bridge.resolve("bridge.mk").absolutePath,
                    "BRIDGE_SOURCE=${bridge.absolutePath}",
                    "BRIDGE_INSTALL=${install.absolutePath}",
                    "FFMPEGKMP_EMBEDDED_FFTOOLS=${if (fftoolsObjects.isEmpty()) 0 else 1}",
                    "FFTOOLS_OBJECTS=${fftoolsObjects.joinToString(" ")}",
                    "ffmpegkmp-bridge",
                ),
            )
            configureBuildEnvironment()
        }
    }

    private fun ExecSpec.configureBuildEnvironment() {
        environment(reproducibleEnvironment())
        if (targetKind.get() != "apple") return

        // Xcode exports target SDK settings into shell-script build phases.
        // FFmpeg's host tools must still be macOS executables that can run
        // during the cross-build; explicit --cc/--sysroot flags configure the
        // actual Apple target compilers independently.
        listOf(
            "SDKROOT",
            "DYLD_ROOT_PATH",
            "IPHONEOS_DEPLOYMENT_TARGET",
            "MACOSX_DEPLOYMENT_TARGET",
            "TVOS_DEPLOYMENT_TARGET",
            "WATCHOS_DEPLOYMENT_TARGET",
            "PLATFORM_NAME",
            "EFFECTIVE_PLATFORM_NAME",
            "ARCHS",
            "CURRENT_ARCH",
        ).forEach(environment::remove)
    }

    private fun emscriptenTool(name: String): String {
        val directory = emscriptenDirectory.get().trim()
        if (directory.isEmpty()) return name
        val base = File(directory)
        val candidates = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf(base.resolve("$name.bat"), base.resolve("$name.cmd"), base.resolve(name))
        } else {
            listOf(base.resolve(name))
        }
        return candidates.firstOrNull(File::isFile)?.absolutePath ?: candidates.first().absolutePath
    }

    private fun commandExists(command: String): Boolean {
        val file = File(command)
        if (file.isAbsolute || command.contains(File.separatorChar)) return file.isFile
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        val suffixes = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("", ".bat", ".cmd", ".exe")
        } else {
            listOf("")
        }
        return path.any { directory ->
            suffixes.any { suffix -> File(directory, command + suffix).isFile }
        }
    }

    private fun addComponentFlags(arguments: MutableList<String>, type: String, values: Set<String>) {
        values.sorted().forEach { arguments += "--enable-$type=$it" }
    }

    private fun addThirdPartyFlags(arguments: MutableList<String>) {
        val libraries = thirdPartyLibraries.get()
        if (libraries.isEmpty()) return
        require(dependenciesInstallDirectory.isPresent) {
            "thirdPartyLibraries ${libraries.sorted()} require dependenciesInstallDirectory to be set"
        }
        require(commandExists("pkg-config")) {
            "pkg-config is required to locate third-party libraries ${libraries.sorted()}; install it on the host."
        }
        libraries.sorted().forEach { arguments += "--enable-$it" }
        arguments += "--pkg-config-flags=--static"
    }

    private fun pkgConfigEnvironment(): Map<String, String> {
        if (!dependenciesInstallDirectory.isPresent) return emptyMap()
        val pkgconfig = dependenciesInstallDirectory.get().asFile.resolve("lib/pkgconfig")
        // PKG_CONFIG_LIBDIR (not _PATH) so cross builds never resolve host libraries.
        return mapOf("PKG_CONFIG_LIBDIR" to pkgconfig.absolutePath)
    }

    private fun verifyInstalledArtifacts(install: File) {
        verifyInstalledHeaders(install)
        val extension = when (targetKind.get()) {
            "android" -> "so"
            "apple" -> "a"
            "wasm" -> "a"
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
        require(install.resolve("lib/libffmpegkmp_bridge.a").isFile) {
            "FFmpegKMP did not install its command bridge for ${targetName.get()}"
        }
    }

    private fun verifyInstalledHeaders(install: File) {
        val requiredHeaders = listOf(
            "libavcodec/avcodec.h",
            "libavformat/avformat.h",
            "libavutil/avconfig.h",
            "libavutil/avutil.h",
        )
        val missing = requiredHeaders.filterNot { install.resolve("include/$it").isFile }
        require(missing.isEmpty()) {
            "FFmpeg did not install expected headers for ${targetName.get()}: ${missing.joinToString()}"
        }
    }

    private fun reproducibleEnvironment(): Map<String, String> = mapOf(
        "LC_ALL" to "C",
        "LANG" to "C",
        "TZ" to "UTC",
        "ZERO_AR_DATE" to "1",
    ) + pkgConfigEnvironment()

    private fun copyLicences(source: File, install: File) {
        val destination = install.resolve("share/licenses/ffmpeg")
        destination.mkdirs()
        listOf("COPYING.LGPLv2.1", "COPYING.LGPLv3", "COPYING.GPLv2", "COPYING.GPLv3", "LICENSE.md")
            .map(source::resolve)
            .filter(File::isFile)
            .forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }
        if (dependenciesInstallDirectory.isPresent) {
            val dependencyLicenses = dependenciesInstallDirectory.get().asFile.resolve("share/licenses")
            if (dependencyLicenses.isDirectory) {
                fileSystemOperations.copy {
                    from(dependencyLicenses)
                    into(install.resolve("share/licenses"))
                }
            }
        }
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
            "wasm" -> runCatching {
                commandOutput(emscriptenTool("emcc"), "--version").lineSequence().first()
            }.getOrDefault("unknown")
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
