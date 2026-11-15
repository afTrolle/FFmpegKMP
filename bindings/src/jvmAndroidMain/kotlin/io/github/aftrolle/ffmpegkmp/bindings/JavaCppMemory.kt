// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

import org.bytedeco.javacpp.Pointer

@InternalFFmpegKmpApi
public actual fun configureNativeMemory(processBytes: Long?, trackedBytes: Long?): Unit = JavaCppMemory.configure(processBytes, trackedBytes)

/** JavaCPP reads its limits once, as `Pointer` initializes, so they are set as system properties before the bridge loads. */
internal object JavaCppMemory {
    private const val ProcessProperty = "org.bytedeco.javacpp.maxPhysicalBytes"
    private const val TrackedProperty = "org.bytedeco.javacpp.maxBytes"

    private var requested: Map<String, Long> = emptyMap()

    fun configure(processBytes: Long?, trackedBytes: Long?) = synchronized(this) {
        requested = listOfNotNull(processBytes?.let { ProcessProperty to it }, trackedBytes?.let { TrackedProperty to it }).toMap()
        requested.forEach { (property, bytes) -> System.setProperty(property, bytes.toString()) }
        if (JavaCppBridgeLoader.loaded) verify()
    }

    fun beforeLoad() = synchronized(this) {
        if (System.getProperty(ProcessProperty) == null) defaultProcessMemoryBytes?.let { System.setProperty(ProcessProperty, it.toString()) }
    }

    fun verify() = synchronized(this) {
        val applied = mapOf(ProcessProperty to Pointer.maxPhysicalBytes(), TrackedProperty to Pointer.maxBytes())
        val missed = requested.filter { (property, bytes) -> applied[property] != bytes }
        check(missed.isEmpty()) {
            "The native runtime loaded with memory limits $applied, not $missed: configure FFmpegKMP before its first use"
        }
    }
}
