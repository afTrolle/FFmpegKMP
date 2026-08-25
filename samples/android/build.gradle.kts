import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose.compiler)
}

description = "Android launcher for the FFmpegKMP Studio sample"

val selectedNativeProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val localRuntimeAar = selectedNativeProfile.map { profile ->
    rootProject.layout.projectDirectory.file(
        "bindings/build/generated/android-runtime/ffmpegkmp-runtime-$profile-local.aar",
    )
}
val prepareFFmpegKmpRuntime = tasks.register<Sync>("prepareFFmpegKmpRuntime") {
    dependsOn(":bindings:assembleJavaCppAndroidRuntime")
    from(localRuntimeAar.map { archive -> zipTree(archive) }) {
        include("jni/**")
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/ffmpegkmp-jni"))
}

android {
    namespace = "io.github.aftrolle.ffmpegkmp.samples.studio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.aftrolle.ffmpegkmp.studio"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets.named("main") {
        jniLibs.directories.add("build/generated/ffmpegkmp-jni")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(prepareFFmpegKmpRuntime)
}

dependencies {
    implementation(project(":samples:studio"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.filekit.dialogs.compose)
}
