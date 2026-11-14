// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffplay

import kotlin.math.abs
import io.github.aftrolle.ffmpegkmp.core.ExperimentalFFmpegKmpApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where a player's media should be at a display frame, for players that [follow][FFplayPlayer.follow]
 * a clock the app keeps: several players on one clock show the same instant in the same display
 * frame. The app derives each player's position from its one timeline, applying that clip's offset,
 * trim and loop, so clips of different lengths loop independently.
 *
 * Called once per display frame, from the thread that refreshes the player, so it must be fast and
 * thread-safe.
 */
@ExperimentalFFmpegKmpApi
public fun interface FFplayClock {
    /**
     * The media position to show at [frameTime], the display frame's time: in a Compose Canvas the
     * composition's frame time, the same for every player drawn in that frame, so players on one
     * clock show one instant; on a native surface the player's own monotonic clock as it refreshes.
     * Frame times are comparable only within one of these.
     *
     * The frame shown is the one `VideoDecoder.frameAt` returns for the position. A position that
     * advances plays the video, one that stands still holds its frame, and one that goes back, or
     * moves forward well past what the frame time did (further than the player would catch up by
     * decoding), is a jump the player seeks
     * for (a loop that wraps); the frames right after a jump wait for that seek.
     *
     * A position before zero or past the clip's end leaves the clip inactive: the player holds its
     * first or last frame, its audio is silent, and it resumes once the position is back inside.
     *
     * The audio plays while the position advances at about real time, and is silent while it
     * stands still, moves at another rate (the audio is not resampled), is outside the clip, or once
     * display frames have stopped asking.
     */
    public fun positionAt(frameTime: Duration): Duration
}

/**
 * How much further than the frame time a followed position may move before it is a jump the player
 * seeks for rather than decodes on to: the player's own catch-up threshold, about where a seek to
 * the nearest keyframe costs less than decoding the frames in between. A decoder that falls behind a
 * clock moving at its pace is never sought for: the native catch-up skips it forward.
 */
internal val FOLLOW_JUMP: Duration = 500.milliseconds

/** One display frame of a followed clock: the frame time the engine asked [FFplayClock] about, and its answer. */
internal class FFplayClockSample(val frameTime: Duration, val position: Duration)

/** Whether [sample] jumped from [previous]: went back, or moved forward [FOLLOW_JUMP] further than the frame time did. */
internal fun followJumped(previous: FFplayClockSample, sample: FFplayClockSample): Boolean {
    val moved = sample.position - previous.position
    if (moved.isNegative()) return true
    val elapsed = (sample.frameTime - previous.frameTime).coerceAtLeast(Duration.ZERO)
    return moved > elapsed + FOLLOW_JUMP
}

/**
 * Whether a followed clock moves at about real time, for the audio, from the samples one follower
 * sees. The clock stands still once its position has not changed for [FOLLOW_AUDIO_WINDOW] of frame
 * time. Its rate is measured between the frame times where its position changed, at least
 * [FOLLOW_AUDIO_WINDOW] apart, so a timeline snapped to a coarser frame rate than the display's
 * measures its true rate. A jump starts the measurement again, and a second jump within the window
 * is scrubbing, not playback. Not thread-safe: one follower feeds it.
 */
internal class FollowedMotion {
    private val changes = ArrayDeque<FFplayClockSample>()
    private var last: FFplayClockSample? = null
    private var jumpedAt: Duration? = null
    private var scrubbedAt: Duration? = null

    /** Forgets the clock's motion, when following starts again. */
    fun reset() {
        changes.clear()
        last = null
        jumpedAt = null
        scrubbedAt = null
    }

    fun add(sample: FFplayClockSample, jumped: Boolean) {
        val previous = last
        last = sample
        if (jumped) {
            val before = jumpedAt
            if (before != null && sample.frameTime - before < FOLLOW_AUDIO_WINDOW) scrubbedAt = sample.frameTime
            jumpedAt = sample.frameTime
            changes.clear()
            changes.addLast(sample)
            return
        }
        if (previous != null && sample.position == previous.position) return
        changes.addLast(sample)
        // Keep the newest change at least a window before the newest, and nothing older.
        while (changes.size > 2 && sample.frameTime - changes[1].frameTime >= FOLLOW_AUDIO_WINDOW) changes.removeFirst()
    }

    /**
     * True while the position advances at about real time, false while it stands still, scrubs or
     * moves at another rate, and null until a window after following started or after a jump says.
     */
    val atRealTime: Boolean?
        get() {
            val newest = last ?: return null
            scrubbedAt?.let { if (newest.frameTime - it < FOLLOW_AUDIO_WINDOW) return false }
            val change = changes.lastOrNull() ?: return null
            if (newest.frameTime - change.frameTime >= FOLLOW_AUDIO_WINDOW) return false
            val oldest = changes.first()
            val span = change.frameTime - oldest.frameTime
            if (span < FOLLOW_AUDIO_WINDOW) return null
            val rate = (change.position - oldest.position) / span
            return abs(rate - 1.0) <= FOLLOW_AUDIO_RATE_TOLERANCE
        }
}

/** How long a followed clock's motion is judged over for the audio, in frame time: about a lip-sync error. */
internal val FOLLOW_AUDIO_WINDOW: Duration = 100.milliseconds

/** How far from real time a followed clock may move and still be heard; the audio is not resampled. */
internal const val FOLLOW_AUDIO_RATE_TOLERANCE: Double = 0.25

/** How long, in real time, the audio plays on without a followed clock's sample: a surface that stopped drawing silences it. */
internal val FOLLOW_AUDIO_WATCHDOG: Duration = 200.milliseconds
