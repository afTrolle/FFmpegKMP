package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import java.io.OutputStream
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/** Whether the host's `pkg-config` resolves a package; false when pkg-config is missing. */
abstract class PkgConfigPackageExists : ValueSource<Boolean, PkgConfigPackageExists.Parameters> {
    interface Parameters : ValueSourceParameters {
        val packageName: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Boolean = runCatching {
        execOperations.exec {
            commandLine("pkg-config", "--exists", parameters.packageName.get())
            isIgnoreExitValue = true
            standardOutput = OutputStream.nullOutputStream()
            errorOutput = OutputStream.nullOutputStream()
        }.exitValue == 0
    }.getOrDefault(false)
}
