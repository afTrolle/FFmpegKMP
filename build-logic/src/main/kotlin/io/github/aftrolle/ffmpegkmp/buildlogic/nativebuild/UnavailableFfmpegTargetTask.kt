package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "This task only reports an unavailable native toolchain")
abstract class UnavailableFfmpegTargetTask : DefaultTask() {
    @get:Input abstract val machine: Property<String>
    @get:Input abstract val reason: Property<String>

    @TaskAction
    fun reportUnavailableTarget() {
        logger.warn("FFmpeg target ${machine.get()} was not built: ${reason.get()}")
    }
}
