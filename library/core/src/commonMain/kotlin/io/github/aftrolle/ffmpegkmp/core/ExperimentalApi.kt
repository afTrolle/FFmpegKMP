// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

/**
 * Marks API that is new and not yet measured on devices: it works as documented, and its shape or
 * its behaviour may still change in a minor release.
 */
@RequiresOptIn(
    message = "This FFmpegKMP API is experimental and may change in a minor release.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalFFmpegKmpApi
