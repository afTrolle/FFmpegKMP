// SPDX-License-Identifier: Apache-2.0
package io.github.aftrolle.ffmpegkmp.ffprobe

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

public sealed class JsonBackedProbeValue(public open val raw: JsonObject) {
    protected fun string(name: String): String? = raw[name].probeString()
    protected fun int(name: String): Int? = raw[name].probeInt()
    protected fun long(name: String): Long? = raw[name].probeLong()
    protected fun double(name: String): Double? = raw[name].probeDouble()
    protected fun boolean(name: String): Boolean? = raw[name].probeBoolean()
    protected fun tags(): Map<String, String> = raw["tags"].probeStringMap()
}

@Serializable(with = MediaInformationSerializer::class)
public data class MediaInformation(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val format: ProbeFormat? get() = raw.obj("format")?.let(::ProbeFormat)
    public val streams: List<ProbeStream> get() = raw.objects("streams", ::ProbeStream)
    public val chapters: List<ProbeChapter> get() = raw.objects("chapters", ::ProbeChapter)
    public val programs: List<ProbeProgram> get() = raw.objects("programs", ::ProbeProgram)
    public val streamGroups: List<ProbeStreamGroup> get() = raw.objects("stream_groups", ::ProbeStreamGroup)
    public val packets: List<ProbePacket> get() = raw.objects("packets", ::ProbePacket)
    public val frames: List<ProbeFrame> get() = raw.objects("frames", ::ProbeFrame)
    public val pixelFormats: List<ProbePixelFormat> get() = raw.objects("pixel_formats", ::ProbePixelFormat)
    public val programVersion: ProbeProgramVersion? get() = raw.obj("program_version")?.let(::ProbeProgramVersion)
    public val libraryVersions: List<ProbeLibraryVersion> get() = raw.objects("library_versions", ::ProbeLibraryVersion)
    public val error: ProbeError? get() = raw.obj("error")?.let(::ProbeError)
}

public object MediaInformationSerializer : JsonObjectBackedSerializer<MediaInformation>(::MediaInformation)

@Serializable(with = ProbeFormatSerializer::class)
public data class ProbeFormat(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val filename: String? get() = string("filename")
    public val streamCount: Int? get() = int("nb_streams")
    public val programCount: Int? get() = int("nb_programs")
    public val formatName: String? get() = string("format_name")
    public val formatLongName: String? get() = string("format_long_name")
    public val startTimeSeconds: Double? get() = double("start_time")
    public val durationSeconds: Double? get() = double("duration")
    public val sizeBytes: Long? get() = long("size")
    public val bitRate: Long? get() = long("bit_rate")
    public val probeScore: Int? get() = int("probe_score")
    public val tags: Map<String, String> get() = tags()
}

public object ProbeFormatSerializer : JsonObjectBackedSerializer<ProbeFormat>(::ProbeFormat)

@Serializable(with = ProbeStreamSerializer::class)
public data class ProbeStream(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val index: Int? get() = int("index")
    public val codecName: String? get() = string("codec_name")
    public val codecLongName: String? get() = string("codec_long_name")
    public val profile: String? get() = string("profile")
    public val codecType: String? get() = string("codec_type")
    public val codecTag: String? get() = string("codec_tag_string") ?: string("codec_tag")
    public val width: Int? get() = int("width")
    public val height: Int? get() = int("height")
    public val codedWidth: Int? get() = int("coded_width")
    public val codedHeight: Int? get() = int("coded_height")
    public val pixelFormat: String? get() = string("pix_fmt")
    public val sampleAspectRatio: String? get() = string("sample_aspect_ratio")
    public val displayAspectRatio: String? get() = string("display_aspect_ratio")
    public val level: Int? get() = int("level")
    public val colorRange: String? get() = string("color_range")
    public val colorSpace: String? get() = string("color_space")
    public val colorTransfer: String? get() = string("color_transfer")
    public val colorPrimaries: String? get() = string("color_primaries")

    /** True for HDR transfer characteristics: PQ (smpte2084) or HLG (arib-std-b67). */
    public val isHdrTransfer: Boolean
        get() = colorTransfer.equals("smpte2084", ignoreCase = true) ||
            colorTransfer.equals("arib-std-b67", ignoreCase = true)
    public val chromaLocation: String? get() = string("chroma_location")
    public val fieldOrder: String? get() = string("field_order")
    public val frameRate: String? get() = string("r_frame_rate")
    public val averageFrameRate: String? get() = string("avg_frame_rate")
    public val timeBase: String? get() = string("time_base")
    public val startPts: Long? get() = long("start_pts")
    public val startTimeSeconds: Double? get() = double("start_time")
    public val durationTs: Long? get() = long("duration_ts")
    public val durationSeconds: Double? get() = double("duration")
    public val bitRate: Long? get() = long("bit_rate")
    public val maxBitRate: Long? get() = long("max_bit_rate")
    public val frameCount: Long? get() = long("nb_frames")
    public val readFrameCount: Long? get() = long("nb_read_frames")
    public val readPacketCount: Long? get() = long("nb_read_packets")
    public val sampleFormat: String? get() = string("sample_fmt")
    public val sampleRate: Int? get() = int("sample_rate")
    public val channels: Int? get() = int("channels")
    public val channelLayout: String? get() = string("channel_layout")
    public val bitsPerSample: Int? get() = int("bits_per_sample")
    public val disposition: ProbeDisposition get() = ProbeDisposition(raw.obj("disposition") ?: JsonObject(emptyMap()))
    public val tags: Map<String, String> get() = tags()
    public val sideData: List<ProbeSideData> get() = raw.array("side_data_list").mapNotNull { it as? JsonObject }.map(::probeSideData)
}

public object ProbeStreamSerializer : JsonObjectBackedSerializer<ProbeStream>(::ProbeStream)

public data class ProbeDisposition(public override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val default: Boolean? get() = boolean("default")
    public val dub: Boolean? get() = boolean("dub")
    public val original: Boolean? get() = boolean("original")
    public val comment: Boolean? get() = boolean("comment")
    public val lyrics: Boolean? get() = boolean("lyrics")
    public val karaoke: Boolean? get() = boolean("karaoke")
    public val forced: Boolean? get() = boolean("forced")
    public val hearingImpaired: Boolean? get() = boolean("hearing_impaired")
    public val visualImpaired: Boolean? get() = boolean("visual_impaired")
    public val cleanEffects: Boolean? get() = boolean("clean_effects")
    public val attachedPicture: Boolean? get() = boolean("attached_pic")
    public val timedThumbnails: Boolean? get() = boolean("timed_thumbnails")
    public val captions: Boolean? get() = boolean("captions")
    public val descriptions: Boolean? get() = boolean("descriptions")
    public val metadata: Boolean? get() = boolean("metadata")
    public val dependent: Boolean? get() = boolean("dependent")
    public val stillImage: Boolean? get() = boolean("still_image")
}

@Serializable(with = ProbeChapterSerializer::class)
public data class ProbeChapter(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val id: Long? get() = long("id")
    public val timeBase: String? get() = string("time_base")
    public val start: Long? get() = long("start")
    public val startTimeSeconds: Double? get() = double("start_time")
    public val end: Long? get() = long("end")
    public val endTimeSeconds: Double? get() = double("end_time")
    public val tags: Map<String, String> get() = tags()
}

public object ProbeChapterSerializer : JsonObjectBackedSerializer<ProbeChapter>(::ProbeChapter)

@Serializable(with = ProbeProgramSerializer::class)
public data class ProbeProgram(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val id: Int? get() = int("program_id")
    public val number: Int? get() = int("program_num")
    public val pmtPid: Int? get() = int("pmt_pid")
    public val pcrPid: Int? get() = int("pcr_pid")
    public val streamCount: Int? get() = int("nb_streams")
    public val tags: Map<String, String> get() = tags()
    public val streams: List<ProbeStream> get() = raw.objects("streams", ::ProbeStream)
}

public object ProbeProgramSerializer : JsonObjectBackedSerializer<ProbeProgram>(::ProbeProgram)

@Serializable(with = ProbeStreamGroupSerializer::class)
public data class ProbeStreamGroup(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val index: Int? get() = int("index")
    public val id: String? get() = string("id")
    public val type: String? get() = string("type")
    public val streamCount: Int? get() = int("nb_streams")
    public val tags: Map<String, String> get() = tags()
    public val streams: List<ProbeStream> get() = raw.objects("streams", ::ProbeStream)
    public val components: List<JsonObject> get() = raw.array("components").mapNotNull { it as? JsonObject }
}

public object ProbeStreamGroupSerializer : JsonObjectBackedSerializer<ProbeStreamGroup>(::ProbeStreamGroup)

@Serializable(with = ProbePacketSerializer::class)
public data class ProbePacket(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val codecType: String? get() = string("codec_type")
    public val streamIndex: Int? get() = int("stream_index")
    public val pts: Long? get() = long("pts")
    public val ptsTimeSeconds: Double? get() = double("pts_time")
    public val dts: Long? get() = long("dts")
    public val dtsTimeSeconds: Double? get() = double("dts_time")
    public val duration: Long? get() = long("duration")
    public val durationSeconds: Double? get() = double("duration_time")
    public val sizeBytes: Long? get() = long("size")
    public val position: Long? get() = long("pos")
    public val flags: String? get() = string("flags")
    public val data: String? get() = string("data")
    public val sideData: List<ProbeSideData> get() = raw.array("side_data_list").mapNotNull { it as? JsonObject }.map(::probeSideData)
}

public object ProbePacketSerializer : JsonObjectBackedSerializer<ProbePacket>(::ProbePacket)

@Serializable(with = ProbeFrameSerializer::class)
public data class ProbeFrame(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val mediaType: String? get() = string("media_type")
    public val streamIndex: Int? get() = int("stream_index")
    public val keyFrame: Boolean? get() = boolean("key_frame")
    public val pts: Long? get() = long("pts")
    public val ptsTimeSeconds: Double? get() = double("pts_time")
    public val packetDts: Long? get() = long("pkt_dts")
    public val bestEffortTimestamp: Long? get() = long("best_effort_timestamp")
    public val bestEffortTimestampSeconds: Double? get() = double("best_effort_timestamp_time")
    public val duration: Long? get() = long("duration")
    public val durationSeconds: Double? get() = double("duration_time")
    public val packetPosition: Long? get() = long("pkt_pos")
    public val packetSize: Long? get() = long("pkt_size")
    public val width: Int? get() = int("width")
    public val height: Int? get() = int("height")
    public val pixelFormat: String? get() = string("pix_fmt")
    public val pictureType: String? get() = string("pict_type")
    public val interlaced: Boolean? get() = boolean("interlaced_frame")
    public val topFieldFirst: Boolean? get() = boolean("top_field_first")
    public val repeatPicture: Int? get() = int("repeat_pict")
    public val sampleFormat: String? get() = string("sample_fmt")
    public val sampleCount: Int? get() = int("nb_samples")
    public val channels: Int? get() = int("channels")
    public val channelLayout: String? get() = string("channel_layout")
    public val tags: Map<String, String> get() = tags()
    public val sideData: List<ProbeSideData> get() = raw.array("side_data_list").mapNotNull { it as? JsonObject }.map(::probeSideData)
}

public object ProbeFrameSerializer : JsonObjectBackedSerializer<ProbeFrame>(::ProbeFrame)

@Serializable(with = ProbePixelFormatSerializer::class)
public data class ProbePixelFormat(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val name: String? get() = string("name")
    public val componentCount: Int? get() = int("nb_components")
    public val bitsPerPixel: Int? get() = int("bits_per_pixel")
    public val flags: Map<String, Boolean> get() = raw.obj("flags")?.mapValues { it.value.probeBoolean() == true }.orEmpty()
    public val components: List<ProbePixelComponent> get() = raw.objects("components", ::ProbePixelComponent)
}

public object ProbePixelFormatSerializer : JsonObjectBackedSerializer<ProbePixelFormat>(::ProbePixelFormat)

public data class ProbePixelComponent(public override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val index: Int? get() = int("index")
    public val bitDepth: Int? get() = int("bit_depth")
}

@Serializable(with = ProbeProgramVersionSerializer::class)
public data class ProbeProgramVersion(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val version: String? get() = string("version")
    public val copyright: String? get() = string("copyright")
    public val compilerIdentification: String? get() = string("compiler_ident")
    public val configuration: String? get() = string("configuration")
}

public object ProbeProgramVersionSerializer : JsonObjectBackedSerializer<ProbeProgramVersion>(::ProbeProgramVersion)

@Serializable(with = ProbeLibraryVersionSerializer::class)
public data class ProbeLibraryVersion(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val name: String? get() = string("name")
    public val major: Int? get() = int("major")
    public val minor: Int? get() = int("minor")
    public val micro: Int? get() = int("micro")
    public val version: Long? get() = long("version")
    public val identification: String? get() = string("ident")
}

public object ProbeLibraryVersionSerializer : JsonObjectBackedSerializer<ProbeLibraryVersion>(::ProbeLibraryVersion)

@Serializable(with = ProbeErrorSerializer::class)
public data class ProbeError(override val raw: JsonObject) : JsonBackedProbeValue(raw) {
    public val code: Int? get() = int("code")
    public val message: String? get() = string("string")
}

public object ProbeErrorSerializer : JsonObjectBackedSerializer<ProbeError>(::ProbeError)

public sealed interface ProbeSideData {
    public val type: String?
    public val raw: JsonObject

    public data class DisplayMatrix(
        override val raw: JsonObject,
        val rotationDegrees: Double?,
    ) : ProbeSideData {
        override val type: String? get() = raw["side_data_type"].probeString()
    }

    public data class MasteringDisplayMetadata(override val raw: JsonObject) : ProbeSideData {
        override val type: String? get() = raw["side_data_type"].probeString()
    }

    public data class ContentLightLevel(
        override val raw: JsonObject,
        val maxContent: Int?,
        val maxAverage: Int?,
    ) : ProbeSideData {
        override val type: String? get() = raw["side_data_type"].probeString()
    }

    public data class Timecode(override val raw: JsonObject, val value: String?) : ProbeSideData {
        override val type: String? get() = raw["side_data_type"].probeString()
    }

    public data class Unknown(override val raw: JsonObject) : ProbeSideData {
        override val type: String? get() = raw["side_data_type"].probeString()
    }
}

private fun probeSideData(raw: JsonObject): ProbeSideData = when (raw["side_data_type"].probeString()) {
    "Display Matrix" -> ProbeSideData.DisplayMatrix(raw, raw["rotation"].probeDouble())
    "Mastering display metadata" -> ProbeSideData.MasteringDisplayMetadata(raw)
    "Content light level metadata" -> ProbeSideData.ContentLightLevel(
        raw,
        raw["max_content"].probeInt(),
        raw["max_average"].probeInt(),
    )
    "SMPTE 12-1 timecode" -> ProbeSideData.Timecode(raw, raw["timecode"].probeString())
    else -> ProbeSideData.Unknown(raw)
}

public abstract class JsonObjectBackedSerializer<T>(
    private val factory: (JsonObject) -> T,
) : KSerializer<T> {
    final override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    final override fun deserialize(decoder: Decoder): T =
        factory(decoder.decodeSerializableValue(JsonObject.serializer()))

    final override fun serialize(encoder: Encoder, value: T) {
        val raw = (value as? JsonBackedProbeValue)?.raw
            ?: error("JsonObject-backed probe serializer received an unsupported value")
        encoder.encodeSerializableValue(JsonObject.serializer(), raw)
    }
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun <T> JsonObject.objects(name: String, factory: (JsonObject) -> T): List<T> =
    array(name).mapNotNull { it as? JsonObject }.map(factory)

private fun JsonElement?.probeString(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content.takeUnless { it == "N/A" }
    else -> toString()
}

private fun JsonElement?.probeInt(): Int? = when (this) {
    is JsonPrimitive -> intOrNull ?: content.takeUnless { it == "N/A" }?.toIntOrNull()
    else -> null
}

private fun JsonElement?.probeLong(): Long? = when (this) {
    is JsonPrimitive -> longOrNull ?: content.takeUnless { it == "N/A" }?.toLongOrNull()
    else -> null
}

private fun JsonElement?.probeDouble(): Double? = when (this) {
    is JsonPrimitive -> doubleOrNull ?: content.takeUnless { it == "N/A" }?.toDoubleOrNull()
    else -> null
}

private fun JsonElement?.probeBoolean(): Boolean? = when (this) {
    is JsonPrimitive -> booleanOrNull ?: intOrNull?.let { it != 0 } ?: when (content.lowercase()) {
        "yes", "true" -> true
        "no", "false" -> false
        else -> null
    }
    else -> null
}

private fun JsonElement?.probeStringMap(): Map<String, String> =
    (this as? JsonObject)?.mapNotNull { (key, value) -> value.probeString()?.let { key to it } }?.toMap().orEmpty()
