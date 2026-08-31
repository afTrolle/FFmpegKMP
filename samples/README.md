# Samples

## FFmpegKMP Studio

Studio is a Compose Multiplatform multi-clip montage editor shared by Android,
iOS, JVM desktop, browser Kotlin/JS, and browser Kotlin/Wasm. It demonstrates:

- FileKit multi-video import and platform-native save/download;
- typed FFprobe stream inspection;
- clip ordering, trim, speed, volume, canvas, and quality controls;
- normalized video/audio filter chains and hard-cut concatenation;
- mounted byte I/O, live session logs, cancellation, and MP4 export; and
- one responsive Compose UI in `:samples:studio` with thin platform launchers.

Run the desktop application (the task builds and wires the local JNI runtime):

```shell
./gradlew :samples:desktop:run
```

Build the Android application and its local four-ABI runtime:

```shell
./gradlew :samples:android:assembleDebug
```

Open `ios/FFmpegKMPStudio.xcodeproj` for iOS. Its build phase invokes the
Kotlin framework embed task. For the browser, activate Emscripten first and run:

```shell
./gradlew :samples:web:jsBrowserDevelopmentRun
./gradlew :samples:web:wasmJsBrowserDevelopmentRun
```

The Webpack development server is configured with the cross-origin isolation
headers required by the pthread-enabled FFmpeg Wasm module.

## Minimal API example

Android, desktop, iOS, and web use the same coroutine-facing API. Platform code
only chooses ordinary paths or mounted virtual files:

```kotlin
suspend fun transcode(input: String, output: String) {
    FFmpegClient().use { client ->
        client.enqueue(FFmpegCommand.build {
            overwrite()
            input(input)
            videoCodec("libx264")
            audioCodec("aac")
            output(output)
        }).use { session ->
            val result = session.await()
            check(result.returnCode == 0) { result.errorOutput }
        }
    }
}
```

Native, Android, and desktop callers may pass normal filesystem paths directly.
Mounted files use Okio `FileHandle` for random reads and writes; Okio
`Source` values support non-seekable streams, while Android/JVM `Sink` outputs
are transparently staged and copied after successful commands. Android
additionally accepts integer or Java file descriptors, `ParcelFileDescriptor`,
`AssetFileDescriptor`, content `Uri`, `InputStream`, and `OutputStream`. Browser
mounts are transferred to the worker's random-access protocol registry. Studio
mounts FileKit bytes consistently so Android content URIs and browser files
follow the same shared processing path.
