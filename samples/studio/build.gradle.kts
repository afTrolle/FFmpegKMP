@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

description = "Shared Compose Multiplatform multi-clip editor sample"

kotlin {
    android {
        namespace = "io.github.aftrolle.ffmpegkmp.samples.studio"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    jvm()

    wasmJs {
        browser()
    }

    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()
    listOf(iosArm64, iosSimulatorArm64).forEach { target ->
        target.binaries.framework {
            baseName = "FFmpegKMPStudio"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":library:core"))
            implementation(project(":library:ffmpeg"))
            implementation(project(":library:ffprobe"))
            implementation(project(":library:filters"))
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
