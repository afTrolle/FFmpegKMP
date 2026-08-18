plugins {
    id("ffmpegkmp.native-build")
}

description = "Reproducible FFmpeg builds using Emscripten"

ffmpegNativeBuild {
    wasm {
        // Browser builds deliberately avoid host devices, sockets, and pthreads.
        // The generated static archives are linked by the later Wasm bindings stage.
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
