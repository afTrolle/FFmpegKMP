package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Locally built native dependencies must not enter remote Gradle caches")
abstract class ThirdPartyCmakeBuildTask : DefaultTask() {
    @get:Inject
    protected abstract val execOperations: ExecOperations

    @get:Inject
    protected abstract val fileSystemOperations: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val workDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty

    @get:Input abstract val libraryName: Property<String>
    @get:Input abstract val androidNdkDirectory: Property<String>
    @get:Input abstract val androidAbi: Property<String>
    @get:Input abstract val androidApiLevel: Property<Int>
    @get:Input abstract val cmakeArgs: ListProperty<String>
    @get:Input abstract val jobs: Property<Int>

    init {
        cmakeArgs.convention(emptyList())
    }

    @TaskAction
    fun buildLibrary() {
        val source = sourceDirectory.get().asFile
        require(source.resolve("CMakeLists.txt").isFile) {
            "${libraryName.get()} source is not initialized at ${source.absolutePath}; " +
                "run `git submodule update --init` first."
        }
        val ndk = File(androidNdkDirectory.get())
        val toolchainFile = ndk.resolve("build/cmake/android.toolchain.cmake")
        require(toolchainFile.isFile) {
            "Android NDK CMake toolchain was not found at ${toolchainFile.absolutePath}."
        }

        val work = workDirectory.get().asFile
        val install = installDirectory.get().asFile
        require(work.absolutePath.contains("native-build") && install.absolutePath.contains("native-build")) {
            "Refusing to clean native output outside native-build: $work / $install"
        }
        fileSystemOperations.delete { delete(work, install) }
        work.mkdirs()
        install.mkdirs()

        val cmake = resolveCmake()
        execOperations.exec {
            commandLine(
                listOf(
                    cmake,
                    "-S", source.absolutePath,
                    "-B", work.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
                    "-DANDROID_ABI=${androidAbi.get()}",
                    "-DANDROID_PLATFORM=android-${androidApiLevel.get()}",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_INSTALL_PREFIX=${install.absolutePath}",
                    "-DCMAKE_INSTALL_LIBDIR=lib",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                    "-DBUILD_SHARED_LIBS=OFF",
                ) + cmakeArgs.get(),
            )
        }
        execOperations.exec {
            commandLine(cmake, "--build", work.absolutePath, "--target", "install", "-j", jobs.get().toString())
        }

        require(install.resolve("lib/lib${libraryName.get()}.a").isFile) {
            "${libraryName.get()} did not install a static library into ${install.resolve("lib")}"
        }
        copyLicenses(source, install)
    }

    private fun resolveCmake(): String {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, "cmake") }
            .firstOrNull(File::isFile)
        if (onPath != null) return onPath.absolutePath
        // Gradle daemons started from IDEs often miss Homebrew's PATH entries.
        return listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake", "/usr/bin/cmake")
            .firstOrNull { File(it).isFile }
            ?: error("cmake was not found on PATH or in common install locations; install it (e.g. `brew install cmake`).")
    }

    private fun copyLicenses(source: File, install: File) {
        val licenses = install.resolve("share/licenses/${libraryName.get()}")
        licenses.mkdirs()
        listOf("LICENSE", "LICENSE.md", "COPYING", "PATENTS")
            .map(source::resolve)
            .filter(File::isFile)
            .forEach { it.copyTo(licenses.resolve(it.name), overwrite = true) }
    }
}
