package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The AAR contains locally built FFmpeg binaries")
abstract class AndroidAarTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val installDirectories: ConfigurableFileCollection

    @get:Input abstract val abiDirectories: MapProperty<String, String>
    @get:Input abstract val profileName: Property<String>
    @get:Input abstract val ffmpegVersion: Property<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Input abstract val ndkMajor: Property<Int>

    @get:OutputFile abstract val outputAar: RegularFileProperty

    @TaskAction
    fun packageAar() {
        val installs = abiDirectories.get().mapValues { File(it.value) }
        require(installs.isNotEmpty()) { "No Android FFmpeg install trees were provided" }
        installs.forEach { (abi, directory) ->
            require(directory.resolve("include").isDirectory) { "Missing FFmpeg headers for $abi at $directory" }
            ffmpegLibraries.forEach { library ->
                require(directory.resolve("lib/lib$library.so").isFile) {
                    "Missing lib$library.so for $abi at $directory"
                }
            }
        }
        verifyPublicHeaders(installs)

        val output = outputAar.get().asFile
        output.parentFile.mkdirs()
        val entries = linkedMapOf<String, ByteArray>()
        entries["AndroidManifest.xml"] = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="io.github.aftrolle.ffmpegkmp.nativebuild">
                <uses-sdk android:minSdkVersion="${apiLevel.get()}" />
            </manifest>
        """.trimIndent().toByteArray()
        entries["classes.jar"] = emptyZip()
        entries["prefab/prefab.json"] = """
            {
              "schema_version": 2,
              "name": "ffmpeg",
              "version": "${ffmpegVersion.get().jsonEscaped()}",
              "dependencies": []
            }
        """.trimIndent().toByteArray()

        val canonical = installs["arm64-v8a"] ?: installs.values.first()
        canonical.resolve("DISCLAIMER.txt").takeIf(File::isFile)?.let {
            entries["META-INF/ffmpeg/DISCLAIMER.txt"] = it.readBytes()
        }
        installs.forEach { (abi, directory) ->
            directory.resolve("build-manifest.json").takeIf(File::isFile)?.let {
                entries["META-INF/ffmpeg/manifests/$abi.json"] = it.readBytes()
            }
        }
        canonical.resolve("share/licenses/ffmpeg").takeIf(File::isDirectory)?.walkTopDown()
            ?.filter(File::isFile)
            ?.forEach { file ->
                entries["META-INF/licenses/ffmpeg/${file.name}"] = file.readBytes()
            }

        installs.forEach { (abi, directory) ->
            ffmpegLibraries.forEach { library ->
                val binary = directory.resolve("lib/lib$library.so")
                entries["jni/$abi/${binary.name}"] = binary.readBytes()
                entries["prefab/modules/$library/libs/android.$abi/abi.json"] = """
                    {
                      "abi": "$abi",
                      "api": ${apiLevel.get()},
                      "ndk": ${ndkMajor.get()},
                      "stl": "none",
                      "static": false
                    }
                """.trimIndent().toByteArray()
                entries["prefab/modules/$library/libs/android.$abi/${binary.name}"] = binary.readBytes()
            }
        }

        ffmpegLibraries.forEach { library ->
            val exports = prefabDependencies.getValue(library)
                .joinToString(", ") { "\":$it\"" }
            entries["prefab/modules/$library/module.json"] =
                "{\"export_libraries\": [$exports]}".toByteArray()
            canonical.resolve("include").walkTopDown().filter(File::isFile).forEach { header ->
                val relative = header.relativeTo(canonical.resolve("include")).invariantSeparatorsPath
                entries["prefab/modules/$library/include/$relative"] = header.readBytes()
            }
        }

        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            entries.toSortedMap().forEach { (path, bytes) ->
                val entry = ZipEntry(path).apply { time = 0L }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun verifyPublicHeaders(installs: Map<String, File>) {
        val canonicalEntry = installs.entries.first()
        val canonical = headerHashes(canonicalEntry.value.resolve("include"))
        installs.entries.drop(1).forEach { (abi, directory) ->
            val candidate = headerHashes(directory.resolve("include"))
            require(candidate == canonical) {
                "FFmpeg public headers differ between ${canonicalEntry.key} and $abi; cannot create one safe Prefab include tree."
            }
        }
    }

    private fun headerHashes(directory: File): Map<String, String> = directory.walkTopDown()
        .filter(File::isFile)
        .associate { it.relativeTo(directory).invariantSeparatorsPath to sha256(it) }

    private fun emptyZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { }
        return output.toByteArray()
    }

    private companion object {
        val prefabDependencies = mapOf(
            "avcodec" to listOf("swresample", "avutil"),
            "avdevice" to listOf("avfilter", "avformat", "avcodec", "swscale", "swresample", "avutil"),
            "avfilter" to listOf("avformat", "avcodec", "swscale", "swresample", "avutil"),
            "avformat" to listOf("avcodec", "swresample", "avutil"),
            "avutil" to emptyList(),
            "swresample" to listOf("avutil"),
            "swscale" to listOf("avutil"),
        )
    }
}
