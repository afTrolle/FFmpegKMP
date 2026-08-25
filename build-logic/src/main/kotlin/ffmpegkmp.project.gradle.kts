import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    base
}

group = "io.github.aftrolle.ffmpegkmp"
version = providers.gradleProperty("ffmpegkmp.version").orElse("0.1.0-SNAPSHOT").get()

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val jvmToolchainVersion = versionCatalog.findVersion("jvm-toolchain").get().requiredVersion.toInt()
    val jvmBytecodeTarget = versionCatalog.findVersion("jvm-bytecode").get().requiredVersion

    extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(jvmToolchainVersion)
        targets.withType<KotlinJvmTarget>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jvmBytecodeTarget))
        }
    }
}
