@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
        // Config
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
