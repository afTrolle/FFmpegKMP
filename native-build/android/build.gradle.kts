plugins {
    id("ffmpegkmp.native-build")
}

description = "Reproducible FFmpeg builds using the Android NDK"

ffmpegNativeBuild {
    android {
        apiLevel.set(24)
        ndkVersion.set("30.0.15729638")
        abis.addAll("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        profiles.named("standard") {
            hardwareAcceleration.androidMediaCodec.set(true)
            thirdPartyLibraries.add("libaom")
            enableAvailableSystemFeatures.set(true)
        }
        profiles.named("full") {
            hardwareAcceleration.androidMediaCodec.set(true)
            thirdPartyLibraries.add("libaom")
        }
    }
}
