package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "XCFrameworks contain locally built FFmpeg binaries")
abstract class AppleXcframeworkTask : DefaultTask() {
    @get:Inject protected abstract val execOperations: ExecOperations
    @get:Inject protected abstract val fileSystemOperations: FileSystemOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val installDirectories: ConfigurableFileCollection

    @get:Input abstract val targetDirectories: MapProperty<String, String>
    @get:Input abstract val profileName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun createXcframeworks() {
        require(System.getProperty("os.name").lowercase().contains("mac")) {
            "XCFramework assembly requires macOS and Xcode."
        }
        val installs = targetDirectories.get().mapValues { File(it.value) }
        val required = listOf(
            "iosArm64", "iosSimulatorArm64", "macosArm64", "tvosArm64", "tvosSimulatorArm64",
            "watchosArm32", "watchosArm64", "watchosDeviceArm64", "watchosSimulatorArm64",
        )
        required.forEach { target -> require(installs[target]?.isDirectory == true) { "Missing Apple install tree: $target" } }

        val output = outputDirectory.get().asFile
        val staging = temporaryDir.resolve(profileName.get())
        fileSystemOperations.delete { delete(output, staging) }
        output.mkdirs()
        staging.mkdirs()

        (ffmpegLibraries + "ffmpegkmp_bridge").forEach { library ->
            val watchFat = staging.resolve("watchos/lib$library.a")
            watchFat.parentFile.mkdirs()
            execOperations.exec {
                commandLine(
                    "xcrun", "lipo", "-create",
                    installs.getValue("watchosArm32").resolve("lib/lib$library.a"),
                    installs.getValue("watchosArm64").resolve("lib/lib$library.a"),
                    installs.getValue("watchosDeviceArm64").resolve("lib/lib$library.a"),
                    "-output", watchFat,
                )
            }
            val destination = output.resolve("lib$library.xcframework")
            val arguments = mutableListOf("xcodebuild", "-create-xcframework")
            listOf(
                "iosArm64", "iosSimulatorArm64", "macosArm64", "tvosArm64", "tvosSimulatorArm64",
            ).forEach { target ->
                val install = installs.getValue(target)
                arguments += listOf(
                    "-library", install.resolve("lib/lib$library.a").absolutePath,
                    "-headers", install.resolve("include").absolutePath,
                )
            }
            arguments += listOf(
                "-library", watchFat.absolutePath,
                "-headers", installs.getValue("watchosDeviceArm64").resolve("include").absolutePath,
                "-library", installs.getValue("watchosSimulatorArm64").resolve("lib/lib$library.a").absolutePath,
                "-headers", installs.getValue("watchosSimulatorArm64").resolve("include").absolutePath,
                "-output", destination.absolutePath,
            )
            execOperations.exec { commandLine(arguments) }
        }

        val canonical = installs.getValue("iosArm64")
        canonical.resolve("DISCLAIMER.txt").takeIf(File::isFile)
            ?.copyTo(output.resolve("DISCLAIMER.txt"), overwrite = true)
        installs.forEach { (target, install) ->
            install.resolve("build-manifest.json").takeIf(File::isFile)?.let { manifest ->
                val destination = output.resolve("manifests/$target.json")
                destination.parentFile.mkdirs()
                manifest.copyTo(destination, overwrite = true)
            }
        }
        canonical.resolve("share/licenses").takeIf(File::isDirectory)?.copyRecursively(
            output.resolve("licenses"),
            overwrite = true,
        )
    }
}
