@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

package io.github.aftrolle.ffmpegkmp.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

/** Configures the Apple targets and native test linkage shared by library conventions. */
fun Project.configureLibraryAppleTargets(includeTvosAndWatchos: Boolean) {
    val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
    val nativeProfileTaskSuffix = selectedNativeProfile.get()
        .split('-', '_')
        .joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    val appleRuntimeLibraries = listOf(
        "ffmpegkmp_bridge",
        "avdevice",
        "avfilter",
        "avformat",
        "avcodec",
        "swresample",
        "swscale",
        "avutil",
    )

    extensions.configure<KotlinMultiplatformExtension> {
        val appleTargets = buildList {
            add(iosArm64())
            add(iosSimulatorArm64())
            add(macosArm64())

            if (includeTvosAndWatchos) {
                add(tvosArm64())
                add(tvosSimulatorArm64())
                add(watchosArm32())
                add(watchosArm64())
                add(watchosDeviceArm64())
                add(watchosSimulatorArm64())
            }
        }

        appleTargets.forEach { appleTarget ->
            val install = rootProject.layout.projectDirectory.dir(
                "native-build/apple/out/${selectedNativeProfile.get()}/${appleTarget.name}",
            )
            val systemFrameworks = buildList {
                addAll(listOf("CoreFoundation", "CoreMedia", "CoreVideo"))
                if (!appleTarget.name.startsWith("watchos")) {
                    addAll(listOf("AudioToolbox", "VideoToolbox", "AVFoundation"))
                }
            }
            val testLinkerOptions = buildList {
                add("-L${install.dir("lib").asFile.absolutePath}")
                appleRuntimeLibraries.forEach { library -> add("-l$library") }
                addAll(listOf("-lz", "-lbz2", "-liconv"))
                systemFrameworks.forEach { framework ->
                    add("-framework")
                    add(framework)
                }
            }

            // Published KLIBs remain declaration-only. Tests link the separately
            // generated runtime at the same final boundary as an application.
            appleTarget.binaries.getTest(NativeBuildType.DEBUG).apply {
                // Kotlin 2.4.10 native caches do not retain the transitive cinterop
                // stubs used by :bindings. Disable the cache only for these final
                // test executables so their C stubs and the runtime are linked
                // together. Remove this when the Kotlin/Native linker handles the
                // transitive cinterop archive correctly.
                disableNativeCache(
                    version = DisableCacheInKotlinVersion.`2_4_10`,
                    reason = "Apple tests link transitive FFmpeg cinterop stubs at the final binary boundary",
                )
                linkerOpts(testLinkerOptions)
                linkTaskProvider.configure {
                    dependsOn(
                        ":native-build:apple:buildFfmpeg${nativeProfileTaskSuffix}" +
                            appleTarget.name.replaceFirstChar(Char::titlecase),
                    )
                }
            }
        }
    }

    tasks.named("commonTestAllTargets").configure {
        dependsOn(
            "iosSimulatorArm64Test",
            "macosArm64Test",
            "compileTestKotlinIosArm64",
        )
        if (includeTvosAndWatchos) {
            dependsOn(
                "compileTestKotlinTvosArm64",
                "compileTestKotlinTvosSimulatorArm64",
                "compileTestKotlinWatchosArm32",
                "compileTestKotlinWatchosArm64",
                "compileTestKotlinWatchosDeviceArm64",
                "compileTestKotlinWatchosSimulatorArm64",
            )
        }
    }
}
