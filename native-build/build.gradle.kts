import java.security.MessageDigest

plugins {
    id("ffmpegkmp.native-build-config")
}

ffmpegNativeBuild {
    defaultProfile.set("standard")
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("ffmpeg"))
    jobs.set(providers.gradleProperty("ffmpegkmp.jobs").map(String::toInt).orElse(Runtime.getRuntime().availableProcessors()))

    common {
        // fftools objects are embedded into libffmpegkmp_bridge; no executable is packaged.
        buildPrograms.set(true)
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
            enableAvailableSystemFeatures.set(true)
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

val verifyPinnedFfplaySource = tasks.register("verifyPinnedFfplaySource") {
    group = "verification"
    description = "Requires an explicit FFplay port review when the pinned source or SDL surface changes"

    val ffplaySource = rootProject.layout.projectDirectory.file("ffmpeg/fftools/ffplay.c")
    val expectedSymbols = layout.projectDirectory.file("bridge/ffplay-sdl-symbols.txt")
    inputs.file(ffplaySource)
    inputs.file(expectedSymbols)

    doLast {
        val sourceBytes = ffplaySource.asFile.readBytes()
        val actualHash = MessageDigest.getInstance("SHA-256")
            .digest(sourceBytes)
            .joinToString("") { "%02x".format(it) }
        val expectedHash = "eb93ecaca9658caddda5adf1d291c1bbe0014f0ab28f09ea97dfd68ea25943eb"
        check(actualHash == expectedHash) {
            "fftools/ffplay.c changed ($actualHash). Review the per-player port and update its pinned hash."
        }

        val symbolPattern = Regex("\\bSDL_[A-Za-z0-9_]+\\b")
        val actualSymbols = symbolPattern.findAll(sourceBytes.decodeToString())
            .map { it.value }
            .toSortedSet()
        val reviewedSymbols = expectedSymbols.asFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toSortedSet()
        check(actualSymbols == reviewedSymbols) {
            val added = actualSymbols - reviewedSymbols
            val removed = reviewedSymbols - actualSymbols
            "FFplay SDL surface changed. Added=$added, removed=$removed. Review MiniSDL before updating the snapshot."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPinnedFfplaySource)
}
