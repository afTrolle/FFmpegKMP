// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.core

@RequiresOptIn(
    message = "This API connects FFmpegKMP library modules and is not intended for applications.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalFFmpegKmpApi
