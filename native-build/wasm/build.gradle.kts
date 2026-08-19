plugins {
    id("ffmpegkmp.native-build")
}

description = "Reproducible FFmpeg builds using Emscripten"

ffmpegNativeBuild {
    wasm {
        // Browser builds deliberately avoid host devices and sockets. The pinned
        // fftools scheduler requires pthreads; they remain confined to the worker.
        common {
            network.set(false)
            devices.set(false)
            hardwareAcceleration {
                decoding.set(false)
                encoding.set(false)
            }
        }
    }
}

val selectedProfile = providers.gradleProperty("ffmpegkmp.profile").orElse("standard")
val emscriptenDirectory = providers.gradleProperty("ffmpegkmp.wasm.emscriptenDir").orElse(
    providers.environmentVariable("EMSCRIPTEN").orElse(
        providers.environmentVariable("EMSDK").map { "$it/upstream/emscripten" }.orElse(""),
    ),
)

tasks.register<Exec>("linkFfmpegKmpWorker") {
    group = "ffmpeg native build"
    description = "Links the selected FFmpeg profile and command bridge into an ES-module worker backend"

    val profile = selectedProfile.get()
    val profileSuffix = profile.split('-', '_').joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    dependsOn("buildFfmpeg${profileSuffix}Wasm32")

    val install = layout.projectDirectory.dir("out/$profile/wasm32")
    val output = layout.buildDirectory.file("worker/$profile/ffmpegkmp.mjs")
    val outputFile = output.get().asFile
    val configuredDirectory = emscriptenDirectory.get().trim()
    val emcc = if (configuredDirectory.isEmpty()) "emcc" else "$configuredDirectory/emcc"
    val libraries = listOf(
        "ffmpegkmp_bridge", "avdevice", "avfilter", "avformat",
        "avcodec", "swscale", "swresample", "avutil",
    ).map { install.file("lib/lib$it.a").asFile.absolutePath }
    inputs.dir(install)
    outputs.files(outputFile, outputFile.resolveSibling("ffmpegkmp.wasm"))
    outputs.cacheIf { false }

    doFirst {
        outputFile.parentFile.mkdirs()
    }
    commandLine(
        listOf(emcc) + libraries + listOf(
            "-o", outputFile.absolutePath,
            "-sMODULARIZE=1",
            "-sEXPORT_ES6=1",
            "-sENVIRONMENT=worker",
            "-sFILESYSTEM=1",
            "-sWASM_BIGINT=1",
            "-pthread",
            // fftools uses separate demux/decode/filter/mux scheduler threads.
            // Leave headroom for codec workers so commands cannot exhaust the pool.
            "-sPTHREAD_POOL_SIZE=16",
            "-sINITIAL_MEMORY=67108864",
            "-sALLOW_MEMORY_GROWTH=1",
            "-sALLOW_TABLE_GROWTH=1",
            "-sEXPORTED_FUNCTIONS=['_malloc','_free','_ffmpegkmp_context_create','_ffmpegkmp_context_destroy','_ffmpegkmp_execute','_ffmpegkmp_cancel']",
            "-sEXPORTED_RUNTIME_METHODS=['addFunction','removeFunction','setValue','stringToNewUTF8','FS','HEAPU8']",
            "-sASSERTIONS=1",
        ),
    )
}
