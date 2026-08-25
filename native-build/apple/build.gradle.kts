plugins {
    id("ffmpegkmp.native-build")
}

description = "Reproducible FFmpeg builds using Apple toolchains"

ffmpegNativeBuild {
    apple {
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
