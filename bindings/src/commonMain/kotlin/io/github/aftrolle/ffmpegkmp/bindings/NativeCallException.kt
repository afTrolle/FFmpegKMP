// SPDX-License-Identifier: LGPL-2.1-or-later
package io.github.aftrolle.ffmpegkmp.bindings

/** A bridge call that failed with [errorCode], an FFmpeg `AVERROR` or a [NativePlayerError] code. */
@InternalFFmpegKmpApi
public abstract class NativeCallException(
    message: String,
    public val errorCode: Int,
) : IllegalStateException(message)

/** Throws the exception [failure] makes when [result], a bridge call's return code, is an error. */
@InternalFFmpegKmpApi
public inline fun requireNative(result: Int, action: String, failure: (message: String, errorCode: Int) -> NativeCallException) {
    if (result < 0) throw failure("Could not $action (error $result)", result)
}
