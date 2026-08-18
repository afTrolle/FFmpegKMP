import io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild.FfmpegNativeBuildExtension

plugins {
    id("ffmpegkmp.project")
}

extensions.create<FfmpegNativeBuildExtension>("ffmpegNativeBuild")
