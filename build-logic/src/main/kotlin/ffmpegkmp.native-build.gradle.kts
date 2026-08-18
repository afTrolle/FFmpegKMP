import io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild.FfmpegNativeBuildExtension
import io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild.NativeBuildRegistration

plugins {
    id("ffmpegkmp.project")
}

val ffmpegNativeBuild = extensions.create<FfmpegNativeBuildExtension>("ffmpegNativeBuild")
val sharedConfiguration = parent?.extensions?.findByType(FfmpegNativeBuildExtension::class.java)
    ?: error("${project.path} requires the :native-build shared configuration project")

NativeBuildRegistration.inherit(sharedConfiguration, ffmpegNativeBuild)

afterEvaluate {
    NativeBuildRegistration.register(project, ffmpegNativeBuild)
}
