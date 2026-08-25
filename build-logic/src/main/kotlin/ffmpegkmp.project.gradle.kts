import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    base
}

val releaseTagPattern = Regex(
    """^v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?)$""",
)

fun normalizedReleaseTag(tag: String): String? =
    releaseTagPattern.matchEntire(tag.trim())?.groupValues?.get(1)

fun gitOutput(vararg arguments: String): String? = providers.exec {
    commandLine("git", *arguments)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().takeIf(String::isNotEmpty)

val derivedVersion = if (project == rootProject) {
    val githubReleaseVersion = providers.environmentVariable("GITHUB_REF_TYPE").orNull
        ?.takeIf { it == "tag" }
        ?.let { providers.environmentVariable("GITHUB_REF_NAME").orNull }
        ?.let(::normalizedReleaseTag)
    val exactGitVersion = gitOutput("tag", "--points-at", "HEAD", "--sort=-version:refname")
        ?.lineSequence()
        ?.mapNotNull(::normalizedReleaseTag)
        ?.firstOrNull()
    val nearestGitVersion = gitOutput(
        "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "--match", "[0-9]*", "HEAD",
    )?.let(::normalizedReleaseTag)
    val gitWorkTreeIsClean = gitOutput("status", "--porcelain", "--untracked-files=no") == null
    githubReleaseVersion
        ?: exactGitVersion?.takeIf { gitWorkTreeIsClean }
        ?: nearestGitVersion?.let { "$it-SNAPSHOT" }
        ?: "0.1.0-SNAPSHOT"
} else {
    rootProject.version.toString()
}

group = "io.github.aftrolle.ffmpegkmp"
version = providers.gradleProperty("ffmpegkmp.version").orElse(derivedVersion).get()

if (project == rootProject) {
    tasks.register("printVersion") {
        group = "help"
        description = "Prints the Maven version derived from an override or the current Git tag"
        doLast { logger.quiet(project.version.toString()) }
    }
}

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
