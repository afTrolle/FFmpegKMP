@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("ffmpegkmp.project")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

val defaultNamespace = "io.github.aftrolle.ffmpegkmp" +
        project.path.replace(':', '.').replace("-", "")
val namespacePropertySuffix = project.path.removePrefix(":").replace(':', '.')
val androidNamespace = providers
    .gradleProperty("ffmpegkmp.android.namespace.$namespacePropertySuffix")
    .orElse(defaultNamespace)
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val androidCompileSdk = versionCatalog.findVersion("android-compileSdk").get().requiredVersion.toInt()
val androidMinSdk = versionCatalog.findVersion("android-minSdk").get().requiredVersion.toInt()
val publicationName = when (project.name) {
    "ffmpeg" -> "FFmpegKMP FFmpeg"
    "ffprobe" -> "FFmpegKMP FFprobe"
    else -> "FFmpegKMP ${project.name.replaceFirstChar(Char::titlecase)}"
}
val unsignedPublicationAudit = providers
    .gradleProperty("ffmpegkmp.unsignedPublicationAudit")
    .map(String::toBoolean)
    .orElse(false)
val runTvosSimulatorTests = providers
    .gradleProperty("ffmpegkmp.runTvosSimulatorTests")
    .map(String::toBoolean)
    .orElse(false)
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
    android {
        namespace = androidNamespace.get()
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk

        withHostTest {}

        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm()

    js {
        browser()
    }

    wasmJs {
        browser()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
        tvosArm64(),
        tvosSimulatorArm64(),
        watchosArm32(),
        watchosArm64(),
        watchosDeviceArm64(),
        watchosSimulatorArm64(),
    ).forEach { appleTarget ->
        val install = rootProject.layout.projectDirectory.dir(
            "native-build/apple/out/${selectedNativeProfile.get()}/${appleTarget.name}",
        )
        val systemFrameworks = buildList {
            addAll(listOf("CoreFoundation", "CoreMedia", "CoreVideo"))
            if (!appleTarget.name.startsWith("watchos")) {
                addAll(listOf("AudioToolbox", "VideoToolbox"))
            }
        }
        val testLinkerOptions = buildList {
            add("-L${install.dir("lib").asFile.absolutePath}")
            appleRuntimeLibraries.forEach { library -> add("-l$library") }
            // Apple pthread symbols are supplied by libSystem. Kotlin/Native
            // invokes ld directly, where the compiler-driver-only -pthread
            // option is invalid.
            addAll(listOf("-lz", "-lbz2", "-liconv"))
            systemFrameworks.forEach { framework ->
                add("-framework")
                add(framework)
            }
        }

        // Maven KLIBs remain declaration-only. Local test executables link the
        // separately generated runtime at the same final boundary as an app.
        appleTarget.binaries.getTest(NativeBuildType.DEBUG).apply {
            linkerOpts(testLinkerOptions)
            linkTaskProvider.configure {
                dependsOn(
                    ":native-build:apple:buildFfmpeg${nativeProfileTaskSuffix}" +
                        appleTarget.name.replaceFirstChar(Char::titlecase),
                )
            }
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }

            if (project.path.startsWith(":library:") && project.path != ":library:core") {
                // Keep media fixtures in one place while making them available to every
                // library module's platform test compilations.
                resources.srcDir(
                    rootProject.layout.projectDirectory.dir("library/core/src/commonTest/resources"),
                )
            }
        }

        getByName("androidDeviceTest").dependencies {
            implementation(versionCatalog.findLibrary("androidx-test-runner").get())
        }
    }
}

tasks.matching { it.name == "tvosSimulatorArm64Test" }.configureEach {
    // The tvOS SDK can be installed without a runnable tvOS simulator runtime.
    // Keep compiling and linking its test executable in allTests, but make
    // simulator execution an explicit opt-in for suitably provisioned hosts.
    onlyIf("-Pffmpegkmp.runTvosSimulatorTests=true was supplied") {
        runTvosSimulatorTests.get()
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (!unsignedPublicationAudit.get()) {
        signAllPublications()
    }
    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name = publicationName
        description.set(providers.provider {
            project.description ?: "Kotlin Multiplatform FFmpeg module ${project.path}"
        })
        inceptionYear = "2026"
        url = "https://github.com/afTrolle/FFmpegKMP"

        licenses {
            license {
                if (project.path == ":bindings") {
                    name = "GNU Lesser General Public License, version 2.1 or later"
                    url = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"
                } else {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "afTrolle"
                name = "Alexander af Trolle"
                url = "https://github.com/afTrolle"
            }
        }
        scm {
            url = "https://github.com/afTrolle/FFmpegKMP"
            connection = "scm:git:https://github.com/afTrolle/FFmpegKMP.git"
            developerConnection = "scm:git:ssh://git@github.com/afTrolle/FFmpegKMP.git"
        }
    }
}

tasks.matching { task ->
    unsignedPublicationAudit.get() && task.name.contains("MavenCentral", ignoreCase = true)
}.configureEach {
    doFirst {
        error(
            "ffmpegkmp.unsignedPublicationAudit may only be used with the local " +
                "releaseCheck repository; Maven Central publication must be signed.",
        )
    }
}

extensions.configure<PublishingExtension> {
    repositories.maven {
        name = "releaseCheck"
        url = rootProject.layout.buildDirectory.dir("release-check-repository").get().asFile.toURI()
    }
}
