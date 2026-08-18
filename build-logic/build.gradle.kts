plugins {
    `kotlin-dsl`
}

group = "io.github.aftrolle.ffmpegkmp.buildlogic"

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}
