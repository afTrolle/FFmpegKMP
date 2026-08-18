@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("ffmpegkmp.project")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
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

extensions.configure<KotlinMultiplatformExtension> {
    android {
        namespace = androidNamespace.get()
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
