plugins {
    id("ffmpegkmp.native-build-config")
}

ffmpegNativeBuild {
    defaultProfile.set("standard")
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("ffmpeg").asFile.absolutePath)
    jobs.set(providers.gradleProperty("ffmpegkmp.jobs").map(String::toInt).orElse(Runtime.getRuntime().availableProcessors()))

    common {
        buildPrograms.set(false)
        buildDocumentation.set(false)
        externalAutodetect.set(false)
        network.set(true)
        devices.set(true)
        enableAvailableSystemFeatures.set(false)
        disableEverything.set(false)
        hardwareAcceleration {
            decoding.set(false)
            encoding.set(false)
            androidMediaCodec.set(false)
            appleVideoToolbox.set(false)
            appleAudioToolbox.set(false)
        }
    }

    profiles {
        create("min") {
            network.set(false)
            devices.set(false)
            hardwareAcceleration {
                decoding.set(false)
                encoding.set(false)
            }
        }

        create("standard") {
            extendsFrom("min")
            network.set(true)
            hardwareAcceleration {
                decoding.set(true)
                encoding.set(true)
            }
        }

        create("full") {
            extendsFrom("standard")
            devices.set(true)
            enableAvailableSystemFeatures.set(true)
        }
    }
}
