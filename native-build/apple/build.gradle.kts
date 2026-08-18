plugins {
    id("ffmpegkmp.native-build")
}

description = "Reproducible FFmpeg builds using Apple toolchains"

ffmpegNativeBuild {
    apple {
        iosDeploymentTarget.set("15.0")
        macosDeploymentTarget.set("11.0")
        tvosDeploymentTarget.set("15.0")
        watchosDeploymentTarget.set("8.0")

        profiles.named("standard") {
            hardwareAcceleration.appleVideoToolbox.set(true)
            hardwareAcceleration.appleAudioToolbox.set(true)
        }
        profiles.named("full") {
            hardwareAcceleration.appleVideoToolbox.set(true)
            hardwareAcceleration.appleAudioToolbox.set(true)
        }
    }
}
