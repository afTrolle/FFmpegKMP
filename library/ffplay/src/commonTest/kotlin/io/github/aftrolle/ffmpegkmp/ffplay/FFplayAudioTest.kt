// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.aftrolle.ffmpegkmp.ffplay

import io.github.aftrolle.ffmpegkmp.core.AudioLevel
import io.github.aftrolle.ffmpegkmp.player.AudioTrackInfo
import io.github.aftrolle.ffmpegkmp.player.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class FFplayAudioTest {
    @Test
    fun playPressedWhileAudioIsOpeningStillStartsTheAudio() = runTest {
        val audio = FakeAudio()
        val opened = CompletableDeferred<FFplayAudioOutput?>()
        val opening = CompletableDeferred<Unit>()
        val player = audioPlayer { opening.complete(Unit); opened.await() }
        player.attachOutput(CanvasOutput())

        val preparing = async { player.prepare(FFplaySource("movie.mp4")) }
        opening.await()
        player.play()
        assertEquals(FFplayState.PLAYING, player.snapshot.value.state)
        assertEquals(0, audio.plays, "audio is not open yet")

        opened.complete(audio)
        preparing.await()

        assertEquals(1, audio.plays)
        assertEquals(listOf(Duration.ZERO), audio.seeks)
        player.close()
    }

    @Test
    fun audioThatFinishesOpeningAfterCloseIsClosedInsteadOfLeaked() = runTest {
        val audio = FakeAudio()
        val opened = CompletableDeferred<FFplayAudioOutput?>()
        val opening = CompletableDeferred<Unit>()
        val player = audioPlayer { opening.complete(Unit); opened.await() }

        val preparing = async { runCatching { player.prepare(FFplaySource("movie.mp4")) } }
        opening.await()
        player.close()
        opened.complete(audio)
        preparing.await()

        assertTrue(audio.closed)
        assertEquals(0, audio.plays)
    }

    @Test
    fun audioFollowsTheVideoEngineStateTransitions() = runTest {
        val audio = FakeAudio()
        val player = audioPlayer { audio }
        val output = CanvasOutput()
        player.attachOutput(output)
        player.prepare(FFplaySource("movie.mp4"))
        assertTrue(player.audio.value.available)

        audio.log.clear()
        player.play()
        assertEquals("play", audio.log.last(), "${audio.log}")

        player.pause()
        assertEquals("pause", audio.log.last(), "${audio.log}")

        player.play()
        player.seekTo(4.seconds)
        assertTrue("seek 4s" in audio.log, "${audio.log}")
        assertEquals("play", audio.log.last(), "${audio.log}")

        // Losing the surface stops the video, so the audio must stop with it.
        player.detachOutput(output)
        assertEquals(FFplayState.WAITING_FOR_OUTPUT, player.snapshot.value.state)
        assertEquals("pause", audio.log.last(), "${audio.log}")

        player.stop()
        assertTrue(audio.closed)
        assertEquals(false, player.audio.value.available)
        player.close()
    }

    @Test
    fun levelsCarryOverAndApplyToNewAudio() = runTest {
        val first = FakeAudio()
        val second = FakeAudio()
        val sources = ArrayDeque(listOf(first, second))
        val player = audioPlayer { sources.removeFirst() }
        player.setMuted(true)

        player.prepare(FFplaySource("a.mp4"))
        player.prepare(FFplaySource("b.mp4"))

        assertTrue(first.closed)
        assertEquals(AudioLevel.Muted, second.appliedLevel)
        assertEquals(AudioLevel.Muted, player.audio.value.level)
        player.close()
    }

    @Test
    fun clockReportsFromBeforeASeekAreIgnoredUntilOneNearTheTarget() {
        val audio = FFplayAudio(CoroutineScope(Dispatchers.Default), warn = {}) { null }
        audio.attach(FakeAudio(), FFplaySnapshot(state = FFplayState.PAUSED)) {}

        audio.followEngine(FFplaySnapshot(state = FFplayState.SEEKING, position = 5.seconds))

        assertNull(audio.clockFor(1.seconds), "a pre-seek position must not reach the video clock")
        assertEquals(5_010_000, audio.clockFor(5.seconds + 10.milliseconds))
        assertEquals(1_000_000, audio.clockFor(1.seconds), "once settled, reports pass straight through")
        audio.close()
    }

    private fun audioPlayer(opener: FFplayAudioOpener) =
        FFplayPlayer(FFplayConfiguration(), ::createInMemoryFFplayEngine, opener)
}

private class FakeAudio : FFplayAudioOutput {
    override val tracks = listOf(AudioTrackInfo(0, "aac", "eng", null, 2, 48_000, true, true))
    override val state = MutableStateFlow(PlaybackState.PAUSED)
    override val position = MutableStateFlow(Duration.ZERO)
    override val enabledTracks = MutableStateFlow(setOf(0))
    override val trackLevels = MutableStateFlow(listOf(AudioLevel.Unchanged))
    val log = mutableListOf<String>()
    val plays get() = log.count { it == "play" }
    val seeks get() = log.filter { it.startsWith("seek ") }.map { Duration.parse(it.removePrefix("seek ")) }
    var appliedLevel: AudioLevel = AudioLevel.Unchanged
    var closed = false

    override fun play() {
        log += "play"
    }
    override fun pause() {
        log += "pause"
    }
    override fun seekTo(position: Duration) {
        log += "seek $position"
    }
    override fun setLevel(level: AudioLevel) {
        appliedLevel = level
    }
    override fun setTrackLevel(track: Int, level: AudioLevel) = Unit
    override fun setTrackEnabled(track: Int, enabled: Boolean) = Unit
    override fun selectTracks(selected: Set<Int>) = Unit
    override fun close() {
        closed = true
    }
}

private class CanvasOutput : FFplayVideoOutput {
    override val kind = FFplayRendererKind.COMPOSE_CANVAS
    override val capabilities = FFplayOutputCapabilities()
    override fun submit(frame: FFplayFrame): Boolean = true
    override fun discard() = Unit
}
