import org.gradle.api.tasks.Sync

plugins {
    id("ffmpegkmp.project")
}

description = "iOS launcher sources for the FFmpegKMP Studio framework"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val selectedNativeProfileTaskSuffix = selectedNativeProfile.map { profile ->
    profile.split('-', '_').joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
val nativeProfileTaskSuffix = selectedNativeProfileTaskSuffix.get()
val appleRuntimeLibraries = listOf(
    "libffmpegkmp_bridge.a",
    "libavdevice.a",
    "libavfilter.a",
    "libavformat.a",
    "libavcodec.a",
    "libswresample.a",
    "libswscale.a",
    "libavutil.a",
)

tasks.register<Sync>("prepareFFmpegKmpRuntime") {
    group = "ffmpeg sample"
    description = "Builds and stages the local FFmpeg static runtime used by the iOS sample"
    dependsOn(
        ":native-build:apple:buildFfmpeg${nativeProfileTaskSuffix}IosArm64",
        ":native-build:apple:buildFfmpeg${nativeProfileTaskSuffix}IosSimulatorArm64",
    )

    listOf(
        "iphoneos" to "iosArm64",
        "iphonesimulator" to "iosSimulatorArm64",
    ).forEach { (sdk, target) ->
        val install = selectedNativeProfile.map { profile ->
            rootProject.layout.projectDirectory.dir("native-build/apple/out/$profile/$target")
        }
        from(install.map { it.dir("lib") }) {
            include(appleRuntimeLibraries)
            into(sdk)
        }
        from(install.map { it.file("build-manifest.json") }) {
            rename { "build-manifest-$target.json" }
            into("metadata")
        }
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("metadata")
    }
    into(layout.buildDirectory.dir("generated/ffmpegkmp-runtime"))
}
