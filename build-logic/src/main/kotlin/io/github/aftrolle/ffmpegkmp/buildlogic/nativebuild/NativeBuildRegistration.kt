package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.Properties

object NativeBuildRegistration {
    fun inherit(parent: FfmpegNativeBuildExtension, child: FfmpegNativeBuildExtension) {
        child.defaultProfile.convention(parent.defaultProfile)
        child.sourceDirectory.convention(parent.sourceDirectory)
        child.jobs.convention(parent.jobs)
        conventionFrom(parent.common, child.common)
        parent.profiles.forEach { source ->
            val target = child.profiles.maybeCreate(source.name)
            conventionFrom(source, target)
            target.parentProfile.convention(source.parentProfile)
            child.android.profiles.maybeCreate(source.name)
            child.apple.profiles.maybeCreate(source.name)
            child.jvm.profiles.maybeCreate(source.name)
            child.wasm.profiles.maybeCreate(source.name)
        }
    }

    fun register(project: Project, extension: FfmpegNativeBuildExtension) {
        when (project.name) {
            "android" -> registerAndroid(project, extension)
            "apple" -> registerApple(project, extension)
            "jvm" -> registerJvm(project, extension)
            "wasm" -> registerWasm(project, extension)
            else -> project.logger.warn("No FFmpeg native-build family registered for ${project.path}")
        }
    }

    private fun registerAndroid(project: Project, extension: FfmpegNativeBuildExtension) {
        extension.android.apiLevel.convention(project.catalogVersion("android-minSdk").toInt())
        extension.android.ndkVersion.convention(project.catalogVersion("android-ndk"))
        extension.android.abis.convention(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        extension.android.ndkDirectory.convention(resolveNdkDirectory(project, extension.android.ndkVersion))
        val version = ffmpegVersion(extension)
        val profileAssemblies = mutableMapOf<String, TaskProvider<*>>()

        val aomRequired = extension.profiles.any { profile ->
            extension.android.abis.get().any { abi ->
                "libaom" in resolve(extension, profile.name, extension.android, abi).thirdPartyLibraries
            }
        }
        val aomTasks = if (!aomRequired) {
            emptyMap()
        } else {
            extension.android.abis.get().filter { it in androidTargets }.associateWith { abi ->
                project.tasks.register(
                    "buildAomAndroid${targetTaskSuffix(abi)}",
                    ThirdPartyCmakeBuildTask::class.java,
                ) {
                    group = "ffmpeg native build"
                    description = "Builds libaom for Android $abi"
                    libraryName.set("aom")
                    sourceDirectory.set(project.rootProject.layout.projectDirectory.dir("third_party/aom"))
                    workDirectory.set(project.layout.projectDirectory.dir("work/deps/aom/$abi"))
                    installDirectory.set(project.layout.projectDirectory.dir("out/deps/aom/$abi"))
                    androidNdkDirectory.set(extension.android.ndkDirectory)
                    androidApiLevel.set(extension.android.apiLevel)
                    androidAbi.set(abi)
                    jobs.set(extension.jobs)
                    cmakeArgs.set(
                        listOf(
                            "-DENABLE_DOCS=0", "-DENABLE_TESTS=0", "-DENABLE_TESTDATA=0",
                            "-DENABLE_TOOLS=0", "-DENABLE_EXAMPLES=0",
                        ) + if (abi.startsWith("x86")) {
                            // Skips x86 assembly, avoiding a host NASM requirement; these ABIs are emulator-only.
                            listOf("-DAOM_TARGET_CPU=generic")
                        } else {
                            emptyList()
                        },
                    )
                }
            }
        }

        extension.profiles.forEach { profile ->
            val profileSuffix = profileTaskSuffix(profile.name)
            val buildTasks = linkedMapOf<String, TaskProvider<FfmpegBuildTask>>()
            androidTargets.filterKeys { it in extension.android.abis.get() }.forEach { (abi, spec) ->
                val targetSuffix = targetTaskSuffix(abi)
                val resolved = resolve(extension, profile.name, extension.android, abi)
                val task = project.tasks.register(
                    "buildFfmpeg${profileSuffix}${targetSuffix}",
                    FfmpegBuildTask::class.java,
                ) {
                    group = "ffmpeg native build"
                    description = "Builds FFmpeg ${profile.name} for Android $abi"
                    applyCommonTaskInputs(project, extension, resolved, profile.name, abi, "android")
                    architecture.set(spec.architecture)
                    targetTriple.set(spec.triple)
                    androidApiLevel.set(extension.android.apiLevel)
                    androidNdkDirectory.set(extension.android.ndkDirectory)
                    require(resolved.thirdPartyLibraries.all { it == "libaom" }) {
                        "Unsupported Android third-party FFmpeg libraries: ${resolved.thirdPartyLibraries - "libaom"}"
                    }
                    if ("libaom" in resolved.thirdPartyLibraries) {
                        val aom = requireNotNull(aomTasks[abi]) {
                            "No libaom build task was registered for Android ABI $abi"
                        }
                        dependsOn(aom)
                        dependenciesInstallDirectory.set(aom.flatMap(ThirdPartyCmakeBuildTask::installDirectory))
                    }
                }
                buildTasks[abi] = task
            }

            val resolved = resolve(extension, profile.name, extension.android, "arm64-v8a")
            val licence = assessLicense(resolved.extraConfigureArgs)
            val aar = project.tasks.register("packageFfmpeg${profileSuffix}Aar", AndroidAarTask::class.java) {
                group = "ffmpeg native build"
                description = "Packages the FFmpeg ${profile.name} Android AAR"
                dependsOn(buildTasks.values)
                installDirectories.from(buildTasks.values.map { it.flatMap(FfmpegBuildTask::installDirectory) })
                abiDirectories.set(project.provider {
                    buildTasks.mapValues { (_, task) ->
                        task.flatMap(FfmpegBuildTask::installDirectory).get().asFile.absolutePath
                    }
                })
                profileName.set(profile.name)
                ffmpegVersion.set(version)
                apiLevel.set(extension.android.apiLevel)
                ndkMajor.set(extension.android.ndkVersion.map { it.substringBefore('.').toInt() })
                outputAar.set(version.map { ffmpegVersion ->
                    project.layout.projectDirectory.file(
                        "out/${profile.name}/ffmpeg-android-n$ffmpegVersion-${profile.name}${licence.suffix}.aar",
                    )
                })
            }
            profileAssemblies[profile.name] = project.tasks.register("assembleFfmpeg$profileSuffix") {
                group = "ffmpeg native build"
                description = "Assembles the FFmpeg ${profile.name} Android binary package"
                dependsOn(aar)
            }
        }
        registerFamilyLifecycle(project, extension, profileAssemblies)
    }

    private fun registerApple(project: Project, extension: FfmpegNativeBuildExtension) {
        extension.apple.iosDeploymentTarget.convention(project.catalogVersion("apple-iosDeployment"))
        extension.apple.macosDeploymentTarget.convention(project.catalogVersion("apple-macosDeployment"))
        extension.apple.tvosDeploymentTarget.convention(project.catalogVersion("apple-tvosDeployment"))
        extension.apple.watchosDeploymentTarget.convention(project.catalogVersion("apple-watchosDeployment"))
        if (!System.getProperty("os.name").contains("mac", ignoreCase = true)) {
            registerUnavailableApple(project, extension)
            return
        }

        val profileAssemblies = mutableMapOf<String, TaskProvider<*>>()

        extension.profiles.forEach { profile ->
            val profileSuffix = profileTaskSuffix(profile.name)
            val buildTasks = linkedMapOf<String, TaskProvider<FfmpegBuildTask>>()
            appleTargets(extension).forEach { spec ->
                val resolved = resolve(extension, profile.name, extension.apple, spec.name)
                val targetSuffix = targetTaskSuffix(spec.name)
                project.tasks.register(
                    "prepareFfmpeg${profileSuffix}${targetSuffix}Headers",
                    FfmpegBuildTask::class.java,
                ) {
                    group = "ffmpeg native build"
                    description = "Configures FFmpeg ${profile.name} headers for ${spec.name} bindings"
                    applyCommonTaskInputs(project, extension, resolved, profile.name, spec.name, "apple")
                    workDirectory.set(project.layout.projectDirectory.dir("header-work/${profile.name}/${spec.name}"))
                    installDirectory.set(project.layout.projectDirectory.dir("headers/${profile.name}/${spec.name}"))
                    buildRuntime.set(false)
                    architecture.set(spec.architecture)
                    targetTriple.set(spec.triple)
                    sdkName.set(spec.sdk)
                    deploymentTarget.set(spec.deployment)
                }
                val task = project.tasks.register(
                    "buildFfmpeg${profileSuffix}${targetSuffix}",
                    FfmpegBuildTask::class.java,
                ) {
                    group = "ffmpeg native build"
                    description = "Builds FFmpeg ${profile.name} for ${spec.name}"
                    applyCommonTaskInputs(project, extension, resolved, profile.name, spec.name, "apple")
                    architecture.set(spec.architecture)
                    targetTriple.set(spec.triple)
                    sdkName.set(spec.sdk)
                    deploymentTarget.set(spec.deployment)
                }
                buildTasks[spec.name] = task
            }
            val xcframeworks = project.tasks.register(
                "packageFfmpeg${profileSuffix}Xcframeworks",
                AppleXcframeworkTask::class.java,
            ) {
                group = "ffmpeg native build"
                description = "Creates FFmpeg ${profile.name} Apple XCFrameworks"
                dependsOn(buildTasks.values)
                installDirectories.from(buildTasks.values.map { it.flatMap(FfmpegBuildTask::installDirectory) })
                targetDirectories.set(project.provider {
                    buildTasks.mapValues { (_, task) ->
                        task.flatMap(FfmpegBuildTask::installDirectory).get().asFile.absolutePath
                    }
                })
                profileName.set(profile.name)
                outputDirectory.set(project.layout.projectDirectory.dir("out/${profile.name}/xcframework"))
            }
            profileAssemblies[profile.name] = project.tasks.register("assembleFfmpeg$profileSuffix") {
                group = "ffmpeg native build"
                description = "Assembles the FFmpeg ${profile.name} Apple binary packages"
                dependsOn(xcframeworks)
            }
        }
        registerFamilyLifecycle(project, extension, profileAssemblies)
    }

    private fun registerUnavailableApple(project: Project, extension: FfmpegNativeBuildExtension) {
        val reason = "Apple binaries require a macOS host with Xcode"
        val profileAssemblies = mutableMapOf<String, TaskProvider<*>>()

        extension.profiles.forEach { profile ->
            val profileSuffix = profileTaskSuffix(profile.name)
            appleTargets(extension).forEach { spec ->
                project.tasks.register(
                    "prepareFfmpeg${profileSuffix}${targetTaskSuffix(spec.name)}Headers",
                    UnavailableFfmpegTargetTask::class.java,
                ) {
                    group = "ffmpeg native build"
                    description = "Reports why FFmpeg ${profile.name} headers cannot be prepared for ${spec.name} on this host"
                    machine.set(spec.name)
                    this.reason.set(reason)
                }
                project.tasks.register(
                    "buildFfmpeg${profileSuffix}${targetTaskSuffix(spec.name)}",
                    UnavailableFfmpegTargetTask::class.java,
                ) {
                    group = "ffmpeg native build"
                    description = "Reports why FFmpeg ${profile.name} cannot be built for ${spec.name} on this host"
                    machine.set(spec.name)
                    this.reason.set(reason)
                }
            }

            val unavailablePackage = project.tasks.register(
                "packageFfmpeg${profileSuffix}Xcframeworks",
                UnavailableFfmpegTargetTask::class.java,
            ) {
                group = "ffmpeg native build"
                description = "Reports why FFmpeg ${profile.name} Apple XCFrameworks cannot be packaged on this host"
                machine.set("Apple XCFrameworks")
                this.reason.set(reason)
            }
            profileAssemblies[profile.name] = project.tasks.register("assembleFfmpeg$profileSuffix") {
                group = "ffmpeg native build"
                description = "Skips FFmpeg ${profile.name} Apple binary packages on unsupported hosts"
                dependsOn(unavailablePackage)
            }
        }
        registerFamilyLifecycle(project, extension, profileAssemblies)
    }

    private fun registerJvm(project: Project, extension: FfmpegNativeBuildExtension) {
        extension.jvm.machines.convention(setOf("current"))
        extension.jvm.macosDeploymentTarget.convention(project.catalogVersion("apple-macosDeployment"))
        val host = hostTarget(extension.jvm.macosDeploymentTarget.get())
        val machines = requestedJvmMachines(extension, host)
        val profileAssemblies = mutableMapOf<String, TaskProvider<*>>()
        extension.profiles.forEach { profile ->
            val profileSuffix = profileTaskSuffix(profile.name)
            val buildTasks = mutableListOf<TaskProvider<out Task>>()
            machines.forEach { machine ->
                val targetSuffix = targetTaskSuffix(machine.name)
                val taskName = "buildFfmpeg${profileSuffix}${targetSuffix}"
                val headerTaskName = "prepareFfmpeg${profileSuffix}${targetSuffix}Headers"
                val unavailable = unavailableJvmReason(machine, host)
                val task = if (unavailable == null) {
                    val resolved = resolve(extension, profile.name, extension.jvm, machine.name)
                    project.tasks.register(headerTaskName, FfmpegBuildTask::class.java) {
                        group = "ffmpeg native build"
                        description = "Configures FFmpeg ${profile.name} headers for ${machine.name} bindings"
                        applyCommonTaskInputs(project, extension, resolved, profile.name, machine.name, "jvm")
                        workDirectory.set(project.layout.projectDirectory.dir("header-work/${profile.name}/${machine.name}"))
                        installDirectory.set(project.layout.projectDirectory.dir("headers/${profile.name}/${machine.name}"))
                        buildRuntime.set(false)
                        architecture.set(machine.architecture)
                        targetTriple.set(machine.targetTriple)
                        deploymentTarget.set(machine.deploymentTarget)
                    }
                    project.tasks.register(taskName, FfmpegBuildTask::class.java) {
                        group = "ffmpeg native build"
                        description = "Builds FFmpeg ${profile.name} shared libraries for ${machine.name}"
                        applyCommonTaskInputs(project, extension, resolved, profile.name, machine.name, "jvm")
                        architecture.set(machine.architecture)
                        targetTriple.set(machine.targetTriple)
                        deploymentTarget.set(machine.deploymentTarget)
                    }
                } else {
                    project.tasks.register(headerTaskName, UnavailableFfmpegTargetTask::class.java) {
                        group = "ffmpeg native build"
                        description = "Reports why FFmpeg ${profile.name} headers cannot be prepared for ${machine.name} on this host"
                        this.machine.set(machine.name)
                        reason.set(unavailable)
                    }
                    project.tasks.register(taskName, UnavailableFfmpegTargetTask::class.java) {
                        group = "ffmpeg native build"
                        description = "Reports why FFmpeg ${profile.name} cannot be built for ${machine.name} on this host"
                        this.machine.set(machine.name)
                        reason.set(unavailable)
                    }
                }
                buildTasks += task
            }
            profileAssemblies[profile.name] = project.tasks.register("assembleFfmpeg$profileSuffix") {
                group = "ffmpeg native build"
                description = "Assembles FFmpeg ${profile.name} shared libraries for configured JVM machines"
                dependsOn(buildTasks)
            }
        }
        registerFamilyLifecycle(project, extension, profileAssemblies)
    }

    private fun registerWasm(project: Project, extension: FfmpegNativeBuildExtension) {
        extension.wasm.emscriptenDirectory.convention(resolveEmscriptenDirectory(project))
        val profileAssemblies = mutableMapOf<String, TaskProvider<*>>()

        extension.profiles.forEach { profile ->
            val profileSuffix = profileTaskSuffix(profile.name)
            val target = "wasm32"
            val resolved = resolve(extension, profile.name, extension.wasm, target)
            val buildTask = project.tasks.register(
                "buildFfmpeg${profileSuffix}${targetTaskSuffix(target)}",
                FfmpegBuildTask::class.java,
            ) {
                group = "ffmpeg native build"
                description = "Builds FFmpeg ${profile.name} static libraries for browser Wasm"
                applyCommonTaskInputs(project, extension, resolved, profile.name, target, "wasm")
                architecture.set("wasm")
                targetTriple.set("wasm32-unknown-emscripten")
                emscriptenDirectory.set(extension.wasm.emscriptenDirectory)
            }
            profileAssemblies[profile.name] = project.tasks.register("assembleFfmpeg$profileSuffix") {
                group = "ffmpeg native build"
                description = "Assembles FFmpeg ${profile.name} static libraries for browser Wasm"
                dependsOn(buildTask)
            }
        }
        registerFamilyLifecycle(project, extension, profileAssemblies)
    }

    private fun registerFamilyLifecycle(
        project: Project,
        extension: FfmpegNativeBuildExtension,
        profileAssemblies: Map<String, TaskProvider<*>>,
    ) {
        project.tasks.register("assembleAllFfmpegProfiles") {
            group = "ffmpeg native build"
            description = "Assembles every configured FFmpeg profile for ${project.name}"
            dependsOn(profileAssemblies.values)
        }
        project.tasks.named("assemble").configure {
            dependsOn(project.provider {
                profileAssemblies[extension.defaultProfile.get()]
                    ?: error("Unknown default FFmpeg profile: ${extension.defaultProfile.get()}")
            })
        }
        project.tasks.named("clean", Delete::class.java).configure {
            delete(
                project.layout.projectDirectory.dir("work"),
                project.layout.projectDirectory.dir("out"),
                project.layout.projectDirectory.dir("header-work"),
                project.layout.projectDirectory.dir("headers"),
            )
        }
    }

    private fun FfmpegBuildTask.applyCommonTaskInputs(
        project: Project,
        extension: FfmpegNativeBuildExtension,
        resolved: MutableResolvedOptions,
        profile: String,
        target: String,
        kind: String,
    ) {
        sourceDirectory.set(extension.sourceDirectory)
        bridgeSourceDirectory.set(project.rootProject.layout.projectDirectory.dir("native-build/bridge"))
        workDirectory.set(project.layout.projectDirectory.dir("work/$profile/$target"))
        installDirectory.set(project.layout.projectDirectory.dir("out/$profile/$target"))
        profileName.set(profile)
        targetName.set(target)
        targetKind.set(kind)
        profileInheritance.set(profileInheritance(extension, profile))
        jobs.set(extension.jobs)
        buildPrograms.set(resolved.buildPrograms)
        buildDocumentation.set(resolved.buildDocumentation)
        externalAutodetect.set(resolved.externalAutodetect)
        network.set(resolved.network)
        devices.set(resolved.devices)
        enableAvailableSystemFeatures.set(resolved.enableAvailableSystemFeatures)
        disableEverything.set(resolved.disableEverything)
        hardwareDecoding.set(resolved.hardwareDecoding)
        hardwareEncoding.set(resolved.hardwareEncoding)
        androidMediaCodec.set(resolved.androidMediaCodec)
        appleVideoToolbox.set(resolved.appleVideoToolbox)
        appleAudioToolbox.set(resolved.appleAudioToolbox)
        encoders.set(resolved.encoders)
        decoders.set(resolved.decoders)
        muxers.set(resolved.muxers)
        demuxers.set(resolved.demuxers)
        parsers.set(resolved.parsers)
        protocols.set(resolved.protocols)
        filters.set(resolved.filters)
        inputDevices.set(resolved.inputDevices)
        outputDevices.set(resolved.outputDevices)
        hardwareAccelerators.set(resolved.hardwareAccelerators)
        thirdPartyLibraries.set(resolved.thirdPartyLibraries)
        extraConfigureArgs.set(resolved.extraConfigureArgs)
        extraCompilerArgs.set(resolved.extraCompilerArgs)
        extraLinkerArgs.set(resolved.extraLinkerArgs)
    }

    private fun resolve(
        extension: FfmpegNativeBuildExtension,
        profileName: String,
        platform: FfmpegPlatformOptions,
        targetName: String,
    ): MutableResolvedOptions {
        val result = MutableResolvedOptions()
        result.overlay(extension.common)
        val visited = mutableSetOf<String>()
        fun applyProfile(name: String) {
            check(visited.add(name)) { "Cyclic FFmpeg profile inheritance involving $name" }
            val profile = extension.profiles.findByName(name) ?: error("Unknown FFmpeg profile: $name")
            profile.parentProfile.orNull?.let(::applyProfile)
            result.overlay(profile)
        }
        applyProfile(profileName)
        result.overlay(platform.common)
        platform.profiles.findByName(profileName)?.let(result::overlay)
        platform.targets.findByName(targetName)?.let(result::overlay)
        return result
    }

    private fun profileInheritance(
        extension: FfmpegNativeBuildExtension,
        profileName: String,
    ): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(name: String) {
            check(visited.add(name)) { "Cyclic FFmpeg profile inheritance involving $name" }
            val profile = extension.profiles.findByName(name) ?: error("Unknown FFmpeg profile: $name")
            profile.parentProfile.orNull?.let(::visit)
            result += name
        }
        visit(profileName)
        return result
    }

    private fun conventionFrom(source: FfmpegBuildOptions, target: FfmpegBuildOptions) {
        target.buildPrograms.convention(source.buildPrograms)
        target.buildDocumentation.convention(source.buildDocumentation)
        target.externalAutodetect.convention(source.externalAutodetect)
        target.network.convention(source.network)
        target.devices.convention(source.devices)
        target.enableAvailableSystemFeatures.convention(source.enableAvailableSystemFeatures)
        target.disableEverything.convention(source.disableEverything)
        target.hardwareAcceleration.decoding.convention(source.hardwareAcceleration.decoding)
        target.hardwareAcceleration.encoding.convention(source.hardwareAcceleration.encoding)
        target.hardwareAcceleration.androidMediaCodec.convention(source.hardwareAcceleration.androidMediaCodec)
        target.hardwareAcceleration.appleVideoToolbox.convention(source.hardwareAcceleration.appleVideoToolbox)
        target.hardwareAcceleration.appleAudioToolbox.convention(source.hardwareAcceleration.appleAudioToolbox)
        target.encoders.convention(source.encoders)
        target.decoders.convention(source.decoders)
        target.muxers.convention(source.muxers)
        target.demuxers.convention(source.demuxers)
        target.parsers.convention(source.parsers)
        target.protocols.convention(source.protocols)
        target.filters.convention(source.filters)
        target.inputDevices.convention(source.inputDevices)
        target.outputDevices.convention(source.outputDevices)
        target.hardwareAccelerators.convention(source.hardwareAccelerators)
        target.thirdPartyLibraries.convention(source.thirdPartyLibraries)
        target.extraConfigureArgs.convention(source.extraConfigureArgs)
        target.extraCompilerArgs.convention(source.extraCompilerArgs)
        target.extraLinkerArgs.convention(source.extraLinkerArgs)
    }

    private fun resolveNdkDirectory(project: Project, version: Provider<String>): Provider<String> =
        project.providers.gradleProperty("ffmpegkmp.android.ndkDir").orElse(
            project.provider {
                val localProperties = project.rootProject.file("local.properties")
                val properties = Properties()
                if (localProperties.isFile) localProperties.inputStream().use(properties::load)
                val sdk = properties.getProperty("sdk.dir")
                    ?: System.getenv("ANDROID_SDK_ROOT")
                    ?: System.getenv("ANDROID_HOME")
                    ?: defaultAndroidSdkDirectory()
                File(sdk, "ndk/${version.get()}").absolutePath
            },
        )

    private fun resolveEmscriptenDirectory(project: Project): Provider<String> =
        project.providers.gradleProperty("ffmpegkmp.wasm.emscriptenDir").orElse(
            project.providers.environmentVariable("EMSCRIPTEN").orElse(
                project.providers.environmentVariable("EMSDK")
                    .map { File(it, "upstream/emscripten").absolutePath }
                    .orElse(
                        project.providers.environmentVariable("PATH").orElse("")
                            .zip(project.providers.systemProperty("os.name")) { path, os -> path to os }
                            .zip(project.providers.systemProperty("user.home")) { (path, os), home ->
                                discoverEmscriptenDirectory(path, os, home)
                            },
                    ),
            ),
        )

    private fun defaultAndroidSdkDirectory(): String {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> File(home, "Library/Android/sdk").absolutePath
            os.contains("windows") -> File(System.getenv("LOCALAPPDATA") ?: home, "Android/Sdk").absolutePath
            else -> File(home, "Android/Sdk").absolutePath
        }
    }

    private fun ffmpegVersion(extension: FfmpegNativeBuildExtension): Provider<String> =
        extension.sourceDirectory.file("RELEASE").map { release ->
            release.asFile.takeIf(File::isFile)?.readText()?.trim()?.removePrefix("n") ?: "unknown"
        }

    private fun Project.catalogVersion(alias: String): String =
        extensions.getByType(VersionCatalogsExtension::class.java)
            .named("libs")
            .findVersion(alias)
            .orElseThrow { IllegalArgumentException("Missing version catalog entry: $alias") }
            .requiredVersion

    private fun appleTargets(extension: FfmpegNativeBuildExtension): List<AppleTargetSpec> = listOf(
        AppleTargetSpec("iosArm64", "aarch64", "iphoneos", extension.apple.iosDeploymentTarget.map { "arm64-apple-ios$it" }, extension.apple.iosDeploymentTarget),
        AppleTargetSpec("iosSimulatorArm64", "aarch64", "iphonesimulator", extension.apple.iosDeploymentTarget.map { "arm64-apple-ios$it-simulator" }, extension.apple.iosDeploymentTarget),
        AppleTargetSpec("macosArm64", "aarch64", "macosx", extension.apple.macosDeploymentTarget.map { "arm64-apple-macos$it" }, extension.apple.macosDeploymentTarget),
        AppleTargetSpec("tvosArm64", "aarch64", "appletvos", extension.apple.tvosDeploymentTarget.map { "arm64-apple-tvos$it" }, extension.apple.tvosDeploymentTarget),
        AppleTargetSpec("tvosSimulatorArm64", "aarch64", "appletvsimulator", extension.apple.tvosDeploymentTarget.map { "arm64-apple-tvos$it-simulator" }, extension.apple.tvosDeploymentTarget),
        AppleTargetSpec("watchosArm32", "arm", "watchos", extension.apple.watchosDeploymentTarget.map { "armv7k-apple-watchos$it" }, extension.apple.watchosDeploymentTarget),
        AppleTargetSpec("watchosArm64", "aarch64", "watchos", extension.apple.watchosDeploymentTarget.map { "arm64_32-apple-watchos$it" }, extension.apple.watchosDeploymentTarget),
        AppleTargetSpec("watchosDeviceArm64", "aarch64", "watchos", extension.apple.watchosDeploymentTarget.map { "arm64-apple-watchos$it" }, extension.apple.watchosDeploymentTarget),
        AppleTargetSpec("watchosSimulatorArm64", "aarch64", "watchsimulator", extension.apple.watchosDeploymentTarget.map { "arm64-apple-watchos$it-simulator" }, extension.apple.watchosDeploymentTarget),
    )

    private fun hostTarget(macosDeploymentTarget: String): JvmMachineSpec {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val os = when {
            osName.contains("mac") -> "macos"
            osName.contains("linux") -> "linux"
            osName.contains("windows") -> "windows"
            else -> error("Unsupported JVM native host OS: $osName")
        }
        val arch = when (archName) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> error("Unsupported JVM native host architecture: $archName")
        }
        return jvmMachine("$os-${if (arch == "aarch64") "arm64" else "x64"}", macosDeploymentTarget)
    }

    private fun requestedJvmMachines(
        extension: FfmpegNativeBuildExtension,
        host: JvmMachineSpec,
    ): List<JvmMachineSpec> = extension.jvm.machines.get().map { requested ->
        when (requested) {
            "current" -> host
            "current-arm64" -> jvmMachine("${host.os}-arm64", extension.jvm.macosDeploymentTarget.get())
            "current-x64" -> jvmMachine("${host.os}-x64", extension.jvm.macosDeploymentTarget.get())
            else -> jvmMachine(requested, extension.jvm.macosDeploymentTarget.get())
        }
    }.distinctBy(JvmMachineSpec::name)

    private fun jvmMachine(name: String, macosDeploymentTarget: String): JvmMachineSpec {
        val (os, architectureName) = name.split('-', limit = 2).takeIf { it.size == 2 }
            ?: error("Invalid JVM FFmpeg machine '$name'. Use current, current-arm64, current-x64, or <os>-<arch>.")
        val architecture = when (architectureName) {
            "arm64" -> "aarch64"
            "x64" -> "x86_64"
            else -> error("Unsupported JVM FFmpeg architecture '$architectureName' in '$name'")
        }
        require(os in setOf("macos", "linux", "windows")) {
            "Unsupported JVM FFmpeg operating system '$os' in '$name'"
        }
        require(os != "windows" || architectureName == "x64") {
            "Only windows-x64 is supported by the JVM FFmpeg pipeline"
        }
        val deployment = if (os == "macos") macosDeploymentTarget else ""
        val triple = if (os == "macos") {
            "${if (architecture == "aarch64") "arm64" else "x86_64"}-apple-macos$deployment"
        } else ""
        return JvmMachineSpec(name, os, architecture, triple, deployment)
    }

    private fun unavailableJvmReason(machine: JvmMachineSpec, host: JvmMachineSpec): String? = when {
        machine.os == "macos" && host.os == "macos" -> null
        machine.name == host.name -> null
        machine.os == "macos" -> "macOS binaries require a macOS host with Xcode"
        machine.os == "linux" && host.os != "linux" ->
            "Linux cross-builds require a configured Linux compiler and sysroot; run this target on Linux"
        machine.os == "linux" ->
            "Cross-architecture Linux builds require a matching cross compiler and sysroot; run on ${machine.name}"
        machine.os == "windows" && host.os != "windows" ->
            "Windows cross-builds require a configured MinGW-w64 toolchain; run this target on Windows"
        else -> "This machine does not match the current host ${host.name}"
    }

    private data class AndroidTargetSpec(val architecture: String, val triple: String)
    private data class AppleTargetSpec(
        val name: String,
        val architecture: String,
        val sdk: String,
        val triple: Provider<String>,
        val deployment: Provider<String>,
    )
    private data class JvmMachineSpec(
        val name: String,
        val os: String,
        val architecture: String,
        val targetTriple: String,
        val deploymentTarget: String,
    )

    private val androidTargets = linkedMapOf(
        "armeabi-v7a" to AndroidTargetSpec("arm", "armv7a-linux-androideabi"),
        "arm64-v8a" to AndroidTargetSpec("aarch64", "aarch64-linux-android"),
        "x86" to AndroidTargetSpec("x86", "i686-linux-android"),
        "x86_64" to AndroidTargetSpec("x86_64", "x86_64-linux-android"),
    )
}
