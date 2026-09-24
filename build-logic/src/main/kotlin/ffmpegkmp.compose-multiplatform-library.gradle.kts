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
pluginManager.apply("org.jetbrains.compose")
pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

configureLibraryAppleTargets(includeTvosAndWatchos = false)

// Compose UI tests on the web need a webpack-bundled executable to load Skiko (CMP-4906).
extensions.configure<KotlinMultiplatformExtension> {
    js {
        binaries.executable()
    }
    wasmJs {
        binaries.executable()
    }
}

// That JS executable also makes Compose copy its Skiko web runtime into the JS main resources.
// Applications get Skiko from Compose itself, so it must not ship inside this library's klib.
tasks.withType<Jar>().matching { it.name == "jsJar" }.configureEach {
    exclude("skiko.mjs", "skiko.wasm", "skikod8.mjs", "js-reexport-symbols.mjs")
}
