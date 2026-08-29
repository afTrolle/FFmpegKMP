// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(io.github.aftrolle.ffmpegkmp.bindings.InternalFFmpegKmpApi::class)

package io.github.aftrolle.ffmpegkmp.bindings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

class BrowserWorkerRequestTest {
    @Test
    fun executionRequestIncludesWorkerProtocolType() {
        val request = NativeExecutionRequest(
            id = 1,
            kind = NativeCommandKind.FFMPEG,
            arguments = listOf("-version"),
        )

        assertEquals("execute", request.toWorkerJson().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun mountedBytesAreTransferredOutsideJson() {
        val request = NativeExecutionRequest(
            id = 2,
            kind = NativeCommandKind.FFMPEG,
            arguments = listOf("-i", "input.bin"),
            mounts = listOf(
                NativeMountedIo(
                    path = "input.bin",
                    resource = NativeSourceResource(Buffer().write(byteArrayOf(0, 1, 2, -1))),
                ),
            ),
        )

        val mountJson = request.toWorkerJson().getValue("mounts").jsonArray.single().jsonObject

        assertFalse("base64" in mountJson)
        assertContentEquals(byteArrayOf(0, 1, 2, -1), request.readMountBytes().single())
    }

    @Test
    fun browserPlayerSnapshotPreservesPlaybackAndColorMetadata() {
        val snapshot =
            "{\"state\":4,\"positionUs\":250000,\"durationUs\":-1,\"queueSerial\":7," +
                "\"outputFlags\":2,\"errorCode\":0,\"videoWidth\":1920,\"videoHeight\":1080," +
                "\"activeDecoder\":2,\"pixelFormat\":23,\"pixelFormatName\":\"nv12\"," +
                "\"bitDepth\":10,\"sarNum\":1,\"sarDen\":1,\"rotation\":90.0," +
                "\"colorPrimaries\":9,\"colorTransfer\":16,\"colorSpace\":9," +
                "\"colorRange\":1,\"chromaLocation\":1,\"hdrType\":1," +
                "\"masteringHasPrimaries\":0,\"masteringHasLuminance\":1," +
                "\"masteringRedX\":0.0,\"masteringRedY\":0.0,\"masteringGreenX\":0.0," +
                "\"masteringGreenY\":0.0,\"masteringBlueX\":0.0,\"masteringBlueY\":0.0," +
                "\"masteringWhiteX\":0.0,\"masteringWhiteY\":0.0," +
                "\"masteringMinLuminance\":0.005,\"masteringMaxLuminance\":1000.0," +
                "\"contentLightPresent\":1,\"maxContentLightLevel\":1000," +
                "\"maxFrameAverageLightLevel\":400,\"droppedFrames\":3}"

        val result = snapshot.toBrowserNativePlayerSnapshot()

        assertEquals(NativePlayerState.PLAYING, result.state)
        assertNull(result.durationUs)
        assertEquals(7u, result.queueSerial)
        assertEquals(NativePlayerDecoderKind.SOFTWARE, result.activeDecoder)
        assertEquals(NativePlayerHdrType.HDR10, result.videoInfo?.hdrType)
        assertEquals(1000.0, result.videoInfo?.masteringDisplay?.maxLuminance)
        assertEquals(400, result.videoInfo?.maxFrameAverageLightLevel)
        assertEquals(3L, result.droppedFrames)
    }
}
