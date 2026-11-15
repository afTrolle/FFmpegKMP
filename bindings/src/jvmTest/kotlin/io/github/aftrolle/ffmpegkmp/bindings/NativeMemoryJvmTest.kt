// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.bytedeco.javacpp.Pointer

class NativeMemoryJvmTest {
    @Test
    fun limitsTheLoadedRuntimeAlreadyHasAreAccepted() {
        JavaCppBridgeLoader.load()
        configureNativeMemory(Pointer.maxPhysicalBytes(), Pointer.maxBytes())
        configureNativeMemory(null, null)
    }

    @Test
    fun limitsSetAfterTheRuntimeLoadedFailLoudly() {
        JavaCppBridgeLoader.load()
        val property = "org.bytedeco.javacpp.maxPhysicalBytes"
        val before = System.getProperty(property)
        try {
            assertFailsWith<IllegalStateException> { configureNativeMemory(Pointer.maxPhysicalBytes() + 1, null) }
        } finally {
            if (before == null) System.clearProperty(property) else System.setProperty(property, before)
            configureNativeMemory(null, null)
        }
    }
}
