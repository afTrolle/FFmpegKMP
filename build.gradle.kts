plugins {
    id("ffmpegkmp.project")
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")

val verifyMavenPublicationScope = tasks.register("verifyMavenPublicationScope") {
    group = "verification"
    description = "Verifies that only bindings and public library modules can publish Maven artifacts"
    doLast {
        val allowed = setOf(
            ":bindings",
            ":library:core",
            ":library:ffmpeg",
            ":library:ffprobe",
            ":library:filters",
        )
        val publishingProjects = allprojects
            .filter { it.pluginManager.hasPlugin("com.vanniktech.maven.publish") }
            .map { it.path }
            .toSet()
        check(publishingProjects == allowed) {
            "Unexpected Maven publication scope. Expected $allowed but found $publishingProjects"
        }
    }
}

subprojects {
    plugins.withId("maven-publish") {
        tasks.matching { it.name.startsWith("publish") }.configureEach {
            dependsOn(verifyMavenPublicationScope)
        }
    }
}

tasks.register("assembleNativeBinaries") {
    group = "ffmpeg native build"
    description = "Assembles the selected FFmpeg profile for Android, Apple, JVM, and browser Wasm"
    dependsOn(selectedNativeProfile.map { profile ->
        val suffix = profile.split('-', '_').joinToString("") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        listOf(
            ":native-build:android:assembleFfmpeg$suffix",
            ":native-build:apple:assembleFfmpeg$suffix",
            ":native-build:jvm:assembleFfmpeg$suffix",
            ":native-build:wasm:assembleFfmpeg$suffix",
        )
    })
}

val sampleRuntimeTasks = mapOf(
    "Android" to ":samples:android:prepareFFmpegKmpRuntime",
    "Desktop" to ":samples:desktop:prepareFFmpegKmpRuntime",
    "Ios" to ":samples:ios:prepareFFmpegKmpRuntime",
    "Web" to ":samples:web:stageFFmpegKmpWasmRuntime",
)

sampleRuntimeTasks.forEach { (platform, taskPath) ->
    tasks.register("assemble${platform}SampleBinaries") {
        group = "ffmpeg sample"
        description = "Builds and stages the local native runtime used by the ${platform.lowercase()} sample"
        dependsOn(taskPath)
    }
}

tasks.register("assembleSampleBinaries") {
    group = "ffmpeg sample"
    description = "Builds and stages all local native runtimes used by the sample applications"
    dependsOn(sampleRuntimeTasks.keys.map { "assemble${it}SampleBinaries" })
}
