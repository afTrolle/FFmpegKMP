# Samples

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
Browser callers use worker virtual paths and transfer `Source`/`Sink` mounts.
Android application builds can consume the ignored runtime AAR produced by
`:bindings:assembleJavaCppAndroidRuntime`.
