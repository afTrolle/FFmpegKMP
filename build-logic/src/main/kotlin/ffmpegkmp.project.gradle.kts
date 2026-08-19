plugins {
    base
}

group = "io.github.aftrolle.ffmpegkmp"
version = providers.gradleProperty("ffmpegkmp.version").orElse("0.1.0-SNAPSHOT").get()
