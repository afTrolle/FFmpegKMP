plugins {
    id("ffmpegkmp.native-build")
}

description = "Reproducible FFmpeg builds for JVM desktop hosts"

ffmpegNativeBuild {
    jvm {
        macosDeploymentTarget.set("11.0")
        machines.addAll(
            "current",
            "macos-arm64",
            "macos-x64",
            "linux-arm64",
            "linux-x64",
            "windows-x64",
        )

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
