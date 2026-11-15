// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffmpegkmp_event_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_io_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_platform_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_platform_video_frame_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_snapshot
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_state_callback
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_video_frame
import io.github.aftrolle.ffmpegkmp.bindings.generated.bridge.ffplaykmp_video_frame_callback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer

/**
 * One native function per callback type for the whole process, each finding its receiver by the
 * [JavaCppCallbacks.Registration.opaque] the native side passes back. JavaCPP backs every callback
 * class with ten native functions and hands later instances a null one without a word, so a
 * callback object per player, decoder or writer leaves the eleventh deaf to its native side.
 */
internal object JavaCppCallbacks {
    private val ids = AtomicLong()
    private val states = ConcurrentHashMap<Long, (ffplaykmp_snapshot) -> Unit>()
    private val videoFrames = ConcurrentHashMap<Long, (ffplaykmp_video_frame) -> Int>()
    private val platformFrames = ConcurrentHashMap<Long, (ffplaykmp_platform_video_frame) -> Int>()
    private val mounts = ConcurrentHashMap<Long, () -> MountTable>()
    private val events = ConcurrentHashMap<Long, (kind: Int, level: Int, data: BytePointer, size: Long) -> Unit>()

    val state: ffplaykmp_state_callback by lazy {
        JavaCppBridgeLoader.load()
        object : ffplaykmp_state_callback() {
            override fun call(opaque: Pointer?, snapshot: ffplaykmp_snapshot?) {
                if (snapshot != null) states[opaque.id]?.invoke(snapshot)
            }
        }
    }

    val videoFrame: ffplaykmp_video_frame_callback by lazy {
        JavaCppBridgeLoader.load()
        object : ffplaykmp_video_frame_callback() {
            override fun call(opaque: Pointer?, frame: ffplaykmp_video_frame?): Int =
                frame?.let { videoFrames[opaque.id]?.invoke(it) } ?: 0
        }
    }

    val platformFrame: ffplaykmp_platform_video_frame_callback by lazy {
        JavaCppBridgeLoader.load()
        object : ffplaykmp_platform_video_frame_callback() {
            override fun call(opaque: Pointer?, frame: ffplaykmp_platform_video_frame?): Int =
                frame?.let { platformFrames[opaque.id]?.invoke(it) } ?: 0
        }
    }

    val io: ffplaykmp_io_callback by lazy {
        JavaCppBridgeLoader.load()
        object : ffplaykmp_io_callback() {
            override fun call(opaque: Pointer?, resourceId: Long, operation: Int, offset: Long, data: BytePointer?, size: Long): Long =
                (mounts[opaque.id]?.invoke() ?: MountTable.Empty).dispatch(resourceId, operation, offset, data, size)
        }
    }

    val event: ffmpegkmp_event_callback by lazy {
        JavaCppBridgeLoader.load()
        object : ffmpegkmp_event_callback() {
            override fun call(opaque: Pointer?, kind: Int, level: Int, data: BytePointer?, size: Long) {
                if (data != null) events[opaque.id]?.invoke(kind, level, data, size)
            }
        }
    }

    /** Receives the calls the native side makes with [Registration.opaque]; close it once that side is freed. */
    fun register(
        state: ((ffplaykmp_snapshot) -> Unit)? = null,
        videoFrame: ((ffplaykmp_video_frame) -> Int)? = null,
        platformFrame: ((ffplaykmp_platform_video_frame) -> Int)? = null,
        mounts: (() -> MountTable)? = null,
        event: ((kind: Int, level: Int, data: BytePointer, size: Long) -> Unit)? = null,
    ): Registration {
        val id = ids.incrementAndGet()
        state?.let { states[id] = it }
        videoFrame?.let { videoFrames[id] = it }
        platformFrame?.let { platformFrames[id] = it }
        mounts?.let { this.mounts[id] = it }
        event?.let { events[id] = it }
        return Registration(id)
    }

    class Registration internal constructor(private val id: Long) : AutoCloseable {
        val opaque: Pointer = OpaqueId(id)

        override fun close() {
            states.remove(id)
            videoFrames.remove(id)
            platformFrames.remove(id)
            mounts.remove(id)
            events.remove(id)
        }
    }

    private class OpaqueId(id: Long) : Pointer() {
        init {
            address = id
        }
    }

    private val Pointer?.id: Long get() = this?.address() ?: 0L
}
