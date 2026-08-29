@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import io.github.aftrolle.ffmpegkmp.buildlogic.configureLibraryAppleTargets
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Published Compose Multiplatform library convention.
 *
 * Compose UI currently publishes Apple artifacts for iOS and macOS, but not
 * tvOS or watchOS. This convention owns that target set explicitly.
 */
pluginManager.apply("ffmpegkmp.multiplatform-library-base")

configureLibraryAppleTargets(includeTvosAndWatchos = false)

extensions.configure<KotlinMultiplatformExtension> {
    js {
        binaries.executable()
    }
    wasmJs {
        binaries.executable()
    }
}
