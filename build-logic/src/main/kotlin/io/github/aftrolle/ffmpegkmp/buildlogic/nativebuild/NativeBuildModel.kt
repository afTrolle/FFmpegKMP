package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class HardwareAccelerationOptions {
    abstract val decoding: Property<Boolean>
    abstract val encoding: Property<Boolean>
    abstract val androidMediaCodec: Property<Boolean>
    abstract val appleVideoToolbox: Property<Boolean>
    abstract val appleAudioToolbox: Property<Boolean>
}

abstract class FfmpegBuildOptions {
    @get:Inject
    protected abstract val objects: ObjectFactory

    abstract val buildPrograms: Property<Boolean>
    abstract val buildDocumentation: Property<Boolean>
    abstract val externalAutodetect: Property<Boolean>
    abstract val network: Property<Boolean>
    abstract val devices: Property<Boolean>
    abstract val enableAvailableSystemFeatures: Property<Boolean>
    abstract val disableEverything: Property<Boolean>

    abstract val encoders: SetProperty<String>
    abstract val decoders: SetProperty<String>
    abstract val muxers: SetProperty<String>
    abstract val demuxers: SetProperty<String>
    abstract val parsers: SetProperty<String>
    abstract val protocols: SetProperty<String>
    abstract val filters: SetProperty<String>
    abstract val inputDevices: SetProperty<String>
    abstract val outputDevices: SetProperty<String>
    abstract val hardwareAccelerators: SetProperty<String>

    /** External FFmpeg libraries (e.g. "libaom") built from sources pinned under third_party/. */
    abstract val thirdPartyLibraries: SetProperty<String>
    abstract val extraConfigureArgs: ListProperty<String>
    abstract val extraCompilerArgs: ListProperty<String>
    abstract val extraLinkerArgs: ListProperty<String>

    val hardwareAcceleration: HardwareAccelerationOptions by lazy {
        objects.newInstance(HardwareAccelerationOptions::class.java)
    }

    fun hardwareAcceleration(action: Action<in HardwareAccelerationOptions>) {
        action.execute(hardwareAcceleration)
    }

    fun disableEverything() {
        disableEverything.set(true)
    }
}

abstract class FfmpegProfile @Inject constructor(private val profileName: String) :
    FfmpegBuildOptions(), Named {
    abstract val parentProfile: Property<String>

    override fun getName(): String = profileName

    fun extendsFrom(profileName: String) {
        parentProfile.set(profileName)
    }
}

abstract class FfmpegTargetOptions @Inject constructor(private val targetName: String) :
    FfmpegBuildOptions(), Named {
    override fun getName(): String = targetName
}

abstract class FfmpegPlatformOptions {
    @get:Inject
    protected abstract val objects: ObjectFactory

    val common: FfmpegBuildOptions = objects.newInstance(FfmpegBuildOptions::class.java)
    val profiles: NamedDomainObjectContainer<FfmpegProfile> =
        objects.domainObjectContainer(FfmpegProfile::class.java)
    val targets: NamedDomainObjectContainer<FfmpegTargetOptions> =
        objects.domainObjectContainer(FfmpegTargetOptions::class.java)

    fun common(action: Action<in FfmpegBuildOptions>) {
        action.execute(common)
    }

    fun profiles(action: Action<in NamedDomainObjectContainer<FfmpegProfile>>) {
        action.execute(profiles)
    }

    fun targets(action: Action<in NamedDomainObjectContainer<FfmpegTargetOptions>>) {
        action.execute(targets)
    }
}

abstract class AndroidBuildOptions : FfmpegPlatformOptions() {
    abstract val apiLevel: Property<Int>
    abstract val ndkVersion: Property<String>
    abstract val ndkDirectory: Property<String>
    abstract val abis: SetProperty<String>
}

abstract class AppleBuildOptions : FfmpegPlatformOptions() {
    abstract val iosDeploymentTarget: Property<String>
    abstract val macosDeploymentTarget: Property<String>
    abstract val tvosDeploymentTarget: Property<String>
    abstract val watchosDeploymentTarget: Property<String>
}

abstract class JvmBuildOptions : FfmpegPlatformOptions() {
    abstract val machines: SetProperty<String>
    abstract val macosDeploymentTarget: Property<String>
}

abstract class WasmBuildOptions : FfmpegPlatformOptions() {
    /** Directory containing emcc, emconfigure, and the other Emscripten tools. */
    abstract val emscriptenDirectory: Property<String>
}

abstract class FfmpegNativeBuildExtension @Inject constructor(objects: ObjectFactory) {
    abstract val defaultProfile: Property<String>
    abstract val sourceDirectory: Property<String>
    abstract val jobs: Property<Int>

    val common: FfmpegBuildOptions = objects.newInstance(FfmpegBuildOptions::class.java)
    val profiles: NamedDomainObjectContainer<FfmpegProfile> =
        objects.domainObjectContainer(FfmpegProfile::class.java)
    val android: AndroidBuildOptions = objects.newInstance(AndroidBuildOptions::class.java)
    val apple: AppleBuildOptions = objects.newInstance(AppleBuildOptions::class.java)
    val jvm: JvmBuildOptions = objects.newInstance(JvmBuildOptions::class.java)
    val wasm: WasmBuildOptions = objects.newInstance(WasmBuildOptions::class.java)

    fun common(action: Action<in FfmpegBuildOptions>) = action.execute(common)
    fun profiles(action: Action<in NamedDomainObjectContainer<FfmpegProfile>>) = action.execute(profiles)
    fun android(action: Action<in AndroidBuildOptions>) = action.execute(android)
    fun apple(action: Action<in AppleBuildOptions>) = action.execute(apple)
    fun jvm(action: Action<in JvmBuildOptions>) = action.execute(jvm)
    fun wasm(action: Action<in WasmBuildOptions>) = action.execute(wasm)
}

internal data class MutableResolvedOptions(
    var buildPrograms: Boolean = false,
    var buildDocumentation: Boolean = false,
    var externalAutodetect: Boolean = false,
    var network: Boolean = true,
    var devices: Boolean = true,
    var enableAvailableSystemFeatures: Boolean = false,
    var disableEverything: Boolean = false,
    var hardwareDecoding: Boolean = false,
    var hardwareEncoding: Boolean = false,
    var androidMediaCodec: Boolean = false,
    var appleVideoToolbox: Boolean = false,
    var appleAudioToolbox: Boolean = false,
    val encoders: MutableSet<String> = linkedSetOf(),
    val decoders: MutableSet<String> = linkedSetOf(),
    val muxers: MutableSet<String> = linkedSetOf(),
    val demuxers: MutableSet<String> = linkedSetOf(),
    val parsers: MutableSet<String> = linkedSetOf(),
    val protocols: MutableSet<String> = linkedSetOf(),
    val filters: MutableSet<String> = linkedSetOf(),
    val inputDevices: MutableSet<String> = linkedSetOf(),
    val outputDevices: MutableSet<String> = linkedSetOf(),
    val hardwareAccelerators: MutableSet<String> = linkedSetOf(),
    val thirdPartyLibraries: MutableSet<String> = linkedSetOf(),
    val extraConfigureArgs: MutableList<String> = mutableListOf(),
    val extraCompilerArgs: MutableList<String> = mutableListOf(),
    val extraLinkerArgs: MutableList<String> = mutableListOf(),
)

internal fun MutableResolvedOptions.overlay(options: FfmpegBuildOptions) {
    options.buildPrograms.orNull?.let { buildPrograms = it }
    options.buildDocumentation.orNull?.let { buildDocumentation = it }
    options.externalAutodetect.orNull?.let { externalAutodetect = it }
    options.network.orNull?.let { network = it }
    options.devices.orNull?.let { devices = it }
    options.enableAvailableSystemFeatures.orNull?.let { enableAvailableSystemFeatures = it }
    options.disableEverything.orNull?.let { disableEverything = it }
    options.hardwareAcceleration.decoding.orNull?.let { hardwareDecoding = it }
    options.hardwareAcceleration.encoding.orNull?.let { hardwareEncoding = it }
    options.hardwareAcceleration.androidMediaCodec.orNull?.let { androidMediaCodec = it }
    options.hardwareAcceleration.appleVideoToolbox.orNull?.let { appleVideoToolbox = it }
    options.hardwareAcceleration.appleAudioToolbox.orNull?.let { appleAudioToolbox = it }
    options.encoders.orNull?.let(encoders::addAll)
    options.decoders.orNull?.let(decoders::addAll)
    options.muxers.orNull?.let(muxers::addAll)
    options.demuxers.orNull?.let(demuxers::addAll)
    options.parsers.orNull?.let(parsers::addAll)
    options.protocols.orNull?.let(protocols::addAll)
    options.filters.orNull?.let(filters::addAll)
    options.inputDevices.orNull?.let(inputDevices::addAll)
    options.outputDevices.orNull?.let(outputDevices::addAll)
    options.hardwareAccelerators.orNull?.let(hardwareAccelerators::addAll)
    options.thirdPartyLibraries.orNull?.let(thirdPartyLibraries::addAll)
    options.extraConfigureArgs.orNull?.let(extraConfigureArgs::addAll)
    options.extraCompilerArgs.orNull?.let(extraCompilerArgs::addAll)
    options.extraLinkerArgs.orNull?.let(extraLinkerArgs::addAll)
}
