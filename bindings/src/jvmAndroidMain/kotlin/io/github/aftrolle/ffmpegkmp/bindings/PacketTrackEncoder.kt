// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import java.nio.ByteBuffer

/** Where a platform encoder's packets go: the writer's packet track. Returns a native error code, negative on failure. */
internal fun interface PacketSink {
    /** Muxes [size] bytes at [data]'s position, shown from [ptsNanos] for [durationNanos] (0 when not known); [extradata] comes with the first packet. */
    fun write(data: ByteBuffer, size: Int, ptsNanos: Long, durationNanos: Long, keyFrame: Boolean, extradata: ByteBuffer?): Int
}

/**
 * An encoder of the platform's own, running beside the writer for one video track whose frames reach
 * it through [NativeEncoderSurface] rather than through FFmpeg. It gives its packets to a [PacketSink]
 * from a thread of its own, once [start]ed.
 */
internal interface PacketTrackEncoder : NativeEncoderSurface {
    /** Starts giving packets; called once the track has become a packet track. */
    fun start()

    /** Ends the input and waits for the encoder's last packet and the end of its stream: a native error code, 0 on success. */
    fun finish(): Int

    /** Frees the encoder; safe from any thread, and more than once. */
    fun release()
}

/** Opens a [PacketTrackEncoder] for a video track [info] describes, or returns null where the platform has none for [config]. */
internal typealias PacketEncoderFactory = (config: NativeVideoEncoderConfig, info: NativeVideoTrackInfo, sink: PacketSink, timeoutMicros: Long) -> PacketTrackEncoder?
