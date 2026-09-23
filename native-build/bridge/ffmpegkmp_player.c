// SPDX-License-Identifier: LGPL-2.1-or-later
/*
 * Audio playback engine: demuxes one input, decodes any subset of its audio
 * tracks, resamples each to the host's output format and mixes them with live
 * per-track and master gains into interleaved float PCM. See ffmpegkmp_bridge.h
 * for the API and its threading contract.
 */
#include "ffmpegkmp_bridge.h"

#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/audio_fifo.h"
#include "libavutil/channel_layout.h"
#include "libswresample/swresample.h"

/* Memory bound on decoded audio one track may queue while another starves.
 * Past it the mix stops waiting and plays the starved track as silence. */
#define FFMPEGKMP_PLAYER_MAX_BUFFERED_SECONDS 10

#define FFMPEGKMP_PLAYER_IO_BUFFER 65536

typedef struct player_track {
    int stream_index;
    const AVCodec *codec;
    char language[64];
    char title[256];
    int is_default;

    atomic_int requested_enabled;
    atomic_uint gain_bits;

    /* Decode state, owned by the thread that calls read/seek. */
    int active;
    AVCodecContext *decoder;
    SwrContext *resampler;
    AVChannelLayout resampler_layout;
    int resampler_format;
    int resampler_rate;
    /* Mixed into the output. A track can decode without being mixed: see
     * clock_track. */
    int mixed;
    AVAudioFifo *fifo;
    /* Silence owed ahead of the FIFO's first sample, for a track whose audio
     * starts after the playback position. Kept as a count, not queued zeros. */
    int64_t pending_silence;
    int aligned;
} player_track;

struct ffmpegkmp_player {
    AVFormatContext *format;
    int output_rate;
    int output_channels;
    AVChannelLayout output_layout;
    int64_t start_time_us;

    player_track *tracks;
    int track_count;
    /* With every track disabled, this one keeps decoding unmixed so silence
     * still follows the input's real timeline (container durations are often
     * estimates) and re-enabling it is seamless. -1 when nothing is decodable. */
    int clock_track;

    atomic_uint master_gain_bits;
    atomic_int abort_requested;

    /* Custom input (ffmpegkmp_player_open_io); NULL when opened by URL. */
    AVIOContext *io;
    ffmpegkmp_io_callback io_callback;
    void *io_opaque;
    int64_t io_resource;
    int64_t io_position;

    /* Read/seek thread state. */
    AVPacket *packet;
    AVFrame *frame;
    uint8_t *convert_buffer;
    int convert_capacity;
    float *mix_buffer;
    int mix_capacity;
    int demux_eof;
    int64_t position_samples;
    int64_t base_us;
};

static unsigned float_bits(float value) {
    unsigned bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static float bits_float(unsigned bits) {
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

static int interrupt_requested(void *opaque) {
    ffmpegkmp_player *player = opaque;
    return atomic_load(&player->abort_requested);
}

static void copy_tag(AVDictionary *metadata, const char *key, char *out, size_t capacity) {
    const AVDictionaryEntry *entry = av_dict_get(metadata, key, NULL, 0);
    snprintf(out, capacity, "%s", entry ? entry->value : "");
}

static void deactivate_track(player_track *track) {
    avcodec_free_context(&track->decoder);
    swr_free(&track->resampler);
    av_channel_layout_uninit(&track->resampler_layout);
    if (track->fifo) {
        av_audio_fifo_free(track->fifo);
        track->fifo = NULL;
    }
    track->active = 0;
    track->aligned = 0;
    track->pending_silence = 0;
}

static int activate_track(ffmpegkmp_player *player, player_track *track) {
    AVStream *stream = player->format->streams[track->stream_index];
    int result;
    if (!track->codec)
        return AVERROR_DECODER_NOT_FOUND;
    track->decoder = avcodec_alloc_context3(track->codec);
    if (!track->decoder)
        return AVERROR(ENOMEM);
    if ((result = avcodec_parameters_to_context(track->decoder, stream->codecpar)) < 0) {
        deactivate_track(track);
        return result;
    }
    track->decoder->pkt_timebase = stream->time_base;
    if ((result = avcodec_open2(track->decoder, track->codec, NULL)) < 0) {
        deactivate_track(track);
        return result;
    }
    track->fifo = av_audio_fifo_alloc(AV_SAMPLE_FMT_FLT, player->output_channels, player->output_rate);
    if (!track->fifo) {
        deactivate_track(track);
        return AVERROR(ENOMEM);
    }
    track->active = 1;
    /* Packets of a newly enabled track arrive from the current demux position;
     * the first frame's timestamp aligns it against the playback position. */
    track->aligned = 0;
    return 0;
}

static void apply_track_requests(ffmpegkmp_player *player) {
    int any_requested = 0;
    /* One snapshot per call, so concurrent toggles can't split the decision. */
    for (int i = 0; i < player->track_count; i++) {
        player->tracks[i].mixed = atomic_load(&player->tracks[i].requested_enabled);
        any_requested |= player->tracks[i].mixed;
    }
    for (int i = 0; i < player->track_count; i++) {
        player_track *track = &player->tracks[i];
        int wanted = track->mixed || (!any_requested && i == player->clock_track);
        if (wanted && !track->active) {
            if (activate_track(player, track) < 0) {
                atomic_store(&track->requested_enabled, 0);
                track->mixed = 0;
                /* An undecodable clock track would otherwise be retried on every read. */
                if (i == player->clock_track)
                    player->clock_track = -1;
            }
        } else if (!wanted && track->active) {
            deactivate_track(track);
        }
        player->format->streams[track->stream_index]->discard =
                track->active ? AVDISCARD_DEFAULT : AVDISCARD_ALL;
    }
}

static int ensure_convert_capacity(ffmpegkmp_player *player, int samples) {
    int bytes = samples * player->output_channels * (int) sizeof(float);
    if (bytes <= player->convert_capacity)
        return 0;
    uint8_t *buffer = av_realloc(player->convert_buffer, bytes);
    if (!buffer)
        return AVERROR(ENOMEM);
    player->convert_buffer = buffer;
    player->convert_capacity = bytes;
    return 0;
}

static int configure_resampler(ffmpegkmp_player *player, player_track *track, const AVFrame *frame) {
    AVChannelLayout input_layout = { 0 };
    int result;
    if (track->resampler &&
            track->resampler_format == frame->format &&
            track->resampler_rate == frame->sample_rate &&
            !av_channel_layout_compare(&track->resampler_layout, &frame->ch_layout))
        return 0;

    swr_free(&track->resampler);
    av_channel_layout_uninit(&track->resampler_layout);
    if (frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC)
        av_channel_layout_default(&input_layout, frame->ch_layout.nb_channels);
    else if ((result = av_channel_layout_copy(&input_layout, &frame->ch_layout)) < 0)
        return result;

    result = swr_alloc_set_opts2(
            &track->resampler,
            &player->output_layout, AV_SAMPLE_FMT_FLT, player->output_rate,
            &input_layout, frame->format, frame->sample_rate,
            0, NULL);
    av_channel_layout_uninit(&input_layout);
    if (result < 0 || (result = swr_init(track->resampler)) < 0) {
        swr_free(&track->resampler);
        return result;
    }
    track->resampler_format = frame->format;
    track->resampler_rate = frame->sample_rate;
    return av_channel_layout_copy(&track->resampler_layout, &frame->ch_layout);
}

static int64_t playback_position_us(const ffmpegkmp_player *player) {
    return player->base_us + av_rescale(player->position_samples, AV_TIME_BASE, player->output_rate);
}

static int track_queued(const player_track *track) {
    return (int) FFMIN(track->pending_silence + av_audio_fifo_size(track->fifo), INT32_MAX);
}

/* Resamples one decoded frame (or flushes the resampler when frame is NULL)
 * into the track's FIFO, trimming or padding the first frame after an open,
 * seek or enable so the track lines up with the playback position. */
static int queue_frame(ffmpegkmp_player *player, player_track *track, const AVFrame *frame) {
    int input_samples = frame ? frame->nb_samples : 0;
    int result;
    if (frame && (result = configure_resampler(player, track, frame)) < 0)
        return result;
    if (!track->resampler)
        return 0;

    /* Where this frame lands relative to the end of what the track already
     * owes the mix: positive means a gap to fill with silence, negative means
     * leading samples from before the playback position (after a seek). */
    int64_t offset = 0;
    if (!track->aligned && frame) {
        if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
            AVStream *stream = player->format->streams[track->stream_index];
            int64_t frame_us = av_rescale_q(frame->best_effort_timestamp, stream->time_base, AV_TIME_BASE_Q) -
                    player->start_time_us;
            int64_t queued_until_us = playback_position_us(player) +
                    av_rescale(track_queued(track), AV_TIME_BASE, player->output_rate);
            offset = av_rescale(frame_us - queued_until_us, player->output_rate, AV_TIME_BASE);
        }
        if (offset > 0)
            track->pending_silence += offset;
    }

    int capacity = swr_get_out_samples(track->resampler, input_samples);
    if (capacity <= 0)
        return 0;
    if ((result = ensure_convert_capacity(player, capacity)) < 0)
        return result;
    uint8_t *output[1] = { player->convert_buffer };
    int converted = swr_convert(
            track->resampler,
            output, capacity,
            frame ? (const uint8_t **) frame->extended_data : NULL, input_samples);
    if (converted <= 0)
        return converted;

    int skip = offset < 0 ? (int) FFMIN(-offset, converted) : 0;
    if (frame && skip < converted)
        track->aligned = 1;
    if (skip >= converted)
        return 0;

    void *planes[1] = { player->convert_buffer + (size_t) skip * player->output_channels * sizeof(float) };
    result = av_audio_fifo_write(track->fifo, planes, converted - skip);
    return result < 0 ? result : 0;
}

static int drain_decoder(ffmpegkmp_player *player, player_track *track) {
    int result;
    while ((result = avcodec_receive_frame(track->decoder, player->frame)) >= 0) {
        result = queue_frame(player, track, player->frame);
        av_frame_unref(player->frame);
        if (result < 0)
            return result;
    }
    return result == AVERROR(EAGAIN) || result == AVERROR_EOF ? 0 : result;
}

static int io_read(void *opaque, uint8_t *buffer, int size) {
    ffmpegkmp_player *player = opaque;
    int64_t count;
    /* Custom I/O bypasses the interrupt callback, so honour abort here. */
    if (atomic_load(&player->abort_requested))
        return AVERROR_EXIT;
    count = player->io_callback(
            player->io_opaque, player->io_resource, FFMPEGKMP_IO_READ,
            player->io_position, buffer, (uint64_t) size);
    if (count < 0 || count > size)
        return AVERROR(EIO);
    if (count == 0)
        return AVERROR_EOF;
    player->io_position += count;
    return (int) count;
}

static int64_t io_seek(void *opaque, int64_t offset, int whence) {
    ffmpegkmp_player *player = opaque;
    int64_t size;
    switch (whence & ~AVSEEK_FORCE) {
    case AVSEEK_SIZE:
        return player->io_callback(player->io_opaque, player->io_resource, FFMPEGKMP_IO_SIZE, 0, NULL, 0);
    case SEEK_SET:
        player->io_position = offset;
        break;
    case SEEK_CUR:
        player->io_position += offset;
        break;
    case SEEK_END:
        size = player->io_callback(player->io_opaque, player->io_resource, FFMPEGKMP_IO_SIZE, 0, NULL, 0);
        if (size < 0)
            return AVERROR(ESPIPE);
        player->io_position = size + offset;
        break;
    default:
        return AVERROR(EINVAL);
    }
    return player->io_position;
}

static player_track *track_for_stream(ffmpegkmp_player *player, int stream_index) {
    for (int i = 0; i < player->track_count; i++) {
        if (player->tracks[i].stream_index == stream_index)
            return player->tracks[i].active ? &player->tracks[i] : NULL;
    }
    return NULL;
}

static int finish_input(ffmpegkmp_player *player) {
    int result;
    player->demux_eof = 1;
    for (int i = 0; i < player->track_count; i++) {
        player_track *track = &player->tracks[i];
        if (!track->active)
            continue;
        avcodec_send_packet(track->decoder, NULL);
        if ((result = drain_decoder(player, track)) < 0)
            return result;
        if ((result = queue_frame(player, track, NULL)) < 0)
            return result;
    }
    return 0;
}

/* Reads one packet and feeds it to its track's decoder. */
static int pump(ffmpegkmp_player *player) {
    int result = av_read_frame(player->format, player->packet);
    if (result == AVERROR_EOF)
        return finish_input(player);
    if (result < 0)
        return result;
    player_track *track = track_for_stream(player, player->packet->stream_index);
    if (track) {
        result = avcodec_send_packet(track->decoder, player->packet);
        /* A corrupt packet costs one packet of audio, not the whole playback. */
        if (result >= 0 || result == AVERROR_INVALIDDATA)
            result = drain_decoder(player, track);
    }
    av_packet_unref(player->packet);
    return result == AVERROR_INVALIDDATA ? 0 : result;
}

static ffmpegkmp_player *player_create(int output_sample_rate, int output_channels, int *result) {
    ffmpegkmp_player *player;
    *result = AVERROR(EINVAL);
    if (output_sample_rate <= 0 || output_channels <= 0 || output_channels > 8)
        return NULL;
    player = av_mallocz(sizeof(*player));
    if (!player) {
        *result = AVERROR(ENOMEM);
        return NULL;
    }
    player->output_rate = output_sample_rate;
    player->output_channels = output_channels;
    av_channel_layout_default(&player->output_layout, output_channels);
    atomic_init(&player->master_gain_bits, float_bits(1.0f));
    atomic_init(&player->abort_requested, 0);
    player->format = avformat_alloc_context();
    if (!player->format) {
        av_free(player);
        *result = AVERROR(ENOMEM);
        return NULL;
    }
    player->format->interrupt_callback.callback = interrupt_requested;
    player->format->interrupt_callback.opaque = player;
    *result = 0;
    return player;
}

/* Probes the opened input and sets up its audio tracks. */
static int player_prepare(ffmpegkmp_player *player, const char *url) {
    int result;
    if ((result = avformat_open_input(&player->format, url, NULL, NULL)) < 0)
        return result;
    if ((result = avformat_find_stream_info(player->format, NULL)) < 0)
        return result;
    player->start_time_us = player->format->start_time == AV_NOPTS_VALUE ? 0 : player->format->start_time;

    for (unsigned i = 0; i < player->format->nb_streams; i++) {
        if (player->format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
            player->track_count++;
    }
    player->tracks = av_calloc(FFMAX(player->track_count, 1), sizeof(*player->tracks));
    player->packet = av_packet_alloc();
    player->frame = av_frame_alloc();
    if (!player->tracks || !player->packet || !player->frame)
        return AVERROR(ENOMEM);

    int best = av_find_best_stream(player->format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    player->clock_track = -1;
    for (unsigned i = 0, track_index = 0; i < player->format->nb_streams; i++) {
        AVStream *stream = player->format->streams[i];
        if (stream->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
            stream->discard = AVDISCARD_ALL;
            continue;
        }
        player_track *track = &player->tracks[track_index++];
        track->stream_index = (int) i;
        track->codec = avcodec_find_decoder(stream->codecpar->codec_id);
        track->is_default = (stream->disposition & AV_DISPOSITION_DEFAULT) != 0;
        copy_tag(stream->metadata, "language", track->language, sizeof(track->language));
        copy_tag(stream->metadata, "title", track->title, sizeof(track->title));
        atomic_init(&track->gain_bits, float_bits(1.0f));
        /* Start with the track FFmpeg itself would pick; hosts enable others. */
        atomic_init(&track->requested_enabled, (int) i == best);
        if (track->codec && ((int) i == best || player->clock_track < 0))
            player->clock_track = (int) (track - player->tracks);
        stream->discard = AVDISCARD_ALL;
    }
    apply_track_requests(player);
    return 0;
}

ffmpegkmp_player *ffmpegkmp_player_open(
        const char *url,
        int output_sample_rate,
        int output_channels,
        int *error) {
    int result = AVERROR(EINVAL);
    ffmpegkmp_player *player = url ? player_create(output_sample_rate, output_channels, &result) : NULL;
    if (player && (result = player_prepare(player, url)) < 0) {
        ffmpegkmp_player_close(player);
        player = NULL;
    }
    if (error)
        *error = player ? 0 : result;
    return player;
}

ffmpegkmp_player *ffmpegkmp_player_open_io(
        ffmpegkmp_io_callback callback,
        void *opaque,
        int64_t resource_id,
        int output_sample_rate,
        int output_channels,
        int *error) {
    int result = AVERROR(EINVAL);
    ffmpegkmp_player *player = callback ? player_create(output_sample_rate, output_channels, &result) : NULL;
    if (player) {
        uint8_t *buffer = av_malloc(FFMPEGKMP_PLAYER_IO_BUFFER);
        player->io_callback = callback;
        player->io_opaque = opaque;
        player->io_resource = resource_id;
        player->io = buffer ? avio_alloc_context(
                buffer, FFMPEGKMP_PLAYER_IO_BUFFER, 0, player, io_read, NULL, io_seek) : NULL;
        if (!player->io) {
            av_free(buffer);
            result = AVERROR(ENOMEM);
        } else {
            player->format->pb = player->io;
            player->format->flags |= AVFMT_FLAG_CUSTOM_IO;
            result = player_prepare(player, "");
        }
        if (result < 0) {
            ffmpegkmp_player_close(player);
            player = NULL;
        }
    }
    if (error)
        *error = player ? 0 : result;
    return player;
}

void ffmpegkmp_player_abort(ffmpegkmp_player *player) {
    if (player)
        atomic_store(&player->abort_requested, 1);
}

void ffmpegkmp_player_close(ffmpegkmp_player *player) {
    if (!player)
        return;
    for (int i = 0; i < player->track_count && player->tracks; i++)
        deactivate_track(&player->tracks[i]);
    av_freep(&player->tracks);
    av_packet_free(&player->packet);
    av_frame_free(&player->frame);
    av_freep(&player->convert_buffer);
    av_freep(&player->mix_buffer);
    av_channel_layout_uninit(&player->output_layout);
    avformat_close_input(&player->format);
    if (player->io) {
        av_freep(&player->io->buffer);
        avio_context_free(&player->io);
    }
    av_free(player);
}

int ffmpegkmp_player_track_count(const ffmpegkmp_player *player) {
    return player ? player->track_count : 0;
}

static const player_track *track_at(const ffmpegkmp_player *player, int track) {
    return player && track >= 0 && track < player->track_count ? &player->tracks[track] : NULL;
}

int ffmpegkmp_player_track_channels(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info ? player->format->streams[info->stream_index]->codecpar->ch_layout.nb_channels : AVERROR(EINVAL);
}

int ffmpegkmp_player_track_sample_rate(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info ? player->format->streams[info->stream_index]->codecpar->sample_rate : AVERROR(EINVAL);
}

const char *ffmpegkmp_player_track_codec(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info ? avcodec_get_name(player->format->streams[info->stream_index]->codecpar->codec_id) : "";
}

const char *ffmpegkmp_player_track_language(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info ? info->language : "";
}

const char *ffmpegkmp_player_track_title(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info ? info->title : "";
}

int ffmpegkmp_player_track_is_default(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info ? info->is_default : 0;
}

int ffmpegkmp_player_track_is_decodable(const ffmpegkmp_player *player, int track) {
    const player_track *info = track_at(player, track);
    return info && info->codec ? 1 : 0;
}

int ffmpegkmp_player_track_enabled(const ffmpegkmp_player *player, int track) {
    player_track *info = (player_track *) track_at(player, track);
    return info ? atomic_load(&info->requested_enabled) : 0;
}

int ffmpegkmp_player_set_track_enabled(ffmpegkmp_player *player, int track, int enabled) {
    player_track *info = (player_track *) track_at(player, track);
    if (!info)
        return AVERROR(EINVAL);
    if (enabled && !info->codec)
        return AVERROR_DECODER_NOT_FOUND;
    atomic_store(&info->requested_enabled, enabled ? 1 : 0);
    return 0;
}

int ffmpegkmp_player_set_track_gain(ffmpegkmp_player *player, int track, float gain) {
    player_track *info = (player_track *) track_at(player, track);
    if (!info || !(gain >= 0.0f))
        return AVERROR(EINVAL);
    atomic_store(&info->gain_bits, float_bits(gain));
    return 0;
}

int ffmpegkmp_player_set_master_gain(ffmpegkmp_player *player, float gain) {
    if (!player || !(gain >= 0.0f))
        return AVERROR(EINVAL);
    atomic_store(&player->master_gain_bits, float_bits(gain));
    return 0;
}

int64_t ffmpegkmp_player_duration_us(const ffmpegkmp_player *player) {
    return player && player->format->duration != AV_NOPTS_VALUE ? player->format->duration : -1;
}

int64_t ffmpegkmp_player_position_us(const ffmpegkmp_player *player) {
    return player ? playback_position_us(player) : 0;
}

int ffmpegkmp_player_seek(ffmpegkmp_player *player, int64_t position_us) {
    int result;
    if (!player || position_us < 0)
        return AVERROR(EINVAL);
    /* Apply pending track changes first so a track enabled just before the
     * seek is demuxed from the new position instead of joining late. */
    apply_track_requests(player);
    result = avformat_seek_file(
            player->format, -1,
            INT64_MIN, player->start_time_us + position_us, player->start_time_us + position_us,
            0);
    if (result < 0)
        return result;
    for (int i = 0; i < player->track_count; i++) {
        player_track *track = &player->tracks[i];
        if (!track->active)
            continue;
        avcodec_flush_buffers(track->decoder);
        /* Drop the resampler's buffered tail so no pre-seek audio leaks out. */
        swr_free(&track->resampler);
        av_channel_layout_uninit(&track->resampler_layout);
        av_audio_fifo_reset(track->fifo);
        track->pending_silence = 0;
        track->aligned = 0;
    }
    player->demux_eof = 0;
    player->base_us = position_us;
    player->position_samples = 0;
    return 0;
}

int ffmpegkmp_player_read(ffmpegkmp_player *player, float *pcm, int frames) {
    int result;
    if (!player || !pcm || frames <= 0)
        return AVERROR(EINVAL);
    apply_track_requests(player);

    const int channels = player->output_channels;
    const int max_buffered = player->output_rate * FFMPEGKMP_PLAYER_MAX_BUFFERED_SECONDS;
    int active = 0;
    int fewest = INT32_MAX;
    int most = 0;
    int decoded_most = 0;
    for (;;) {
        active = 0;
        fewest = INT32_MAX;
        most = 0;
        decoded_most = 0;
        for (int i = 0; i < player->track_count; i++) {
            if (!player->tracks[i].active)
                continue;
            int size = track_queued(&player->tracks[i]);
            active++;
            fewest = FFMIN(fewest, size);
            most = FFMAX(most, size);
            decoded_most = FFMAX(decoded_most, av_audio_fifo_size(player->tracks[i].fifo));
        }
        /* active == 0 only when nothing is decodable (the clock track keeps
         * decoding otherwise); demuxing on would just run the input to EOF. */
        if (active == 0 || player->demux_eof || fewest >= frames || decoded_most >= max_buffered)
            break;
        if (atomic_load(&player->abort_requested))
            return AVERROR_EXIT;
        if ((result = pump(player)) < 0)
            return result;
    }

    int available;
    if (active == 0) {
        /* Nothing decodable to keep time by: fall back to the reported duration. */
        int64_t duration = ffmpegkmp_player_duration_us(player);
        int64_t remaining = duration < 0 ? 0 :
                av_rescale(duration - playback_position_us(player), player->output_rate, AV_TIME_BASE);
        available = (int) FFMAX(0, FFMIN(remaining, frames));
    } else {
        available = FFMIN(frames, player->demux_eof || decoded_most >= max_buffered ? most : fewest);
    }
    if (available <= 0)
        return 0;

    memset(pcm, 0, (size_t) available * channels * sizeof(float));
    if (player->mix_capacity < available) {
        float *buffer = av_realloc(player->mix_buffer, (size_t) available * channels * sizeof(float));
        if (!buffer)
            return AVERROR(ENOMEM);
        player->mix_buffer = buffer;
        player->mix_capacity = available;
    }
    for (int i = 0; i < player->track_count; i++) {
        player_track *track = &player->tracks[i];
        if (!track->active)
            continue;
        int silent = (int) FFMIN(track->pending_silence, available);
        track->pending_silence -= silent;
        if (!track->mixed) {
            av_audio_fifo_drain(track->fifo, available - silent);
            continue;
        }
        void *planes[1] = { player->mix_buffer };
        int count = av_audio_fifo_read(track->fifo, planes, available - silent);
        if (count < 0)
            return count;
        /* A starved track played short; realign it on its next frame instead of
         * letting it run late from here on. */
        if (count < available - silent && !player->demux_eof)
            track->aligned = 0;
        const float gain = bits_float(atomic_load(&track->gain_bits));
        float *destination = pcm + (size_t) silent * channels;
        const int values = count * channels;
        for (int v = 0; v < values; v++)
            destination[v] += player->mix_buffer[v] * gain;
    }

    const float master = bits_float(atomic_load(&player->master_gain_bits));
    const int values = available * channels;
    for (int v = 0; v < values; v++) {
        float sample = pcm[v] * master;
        pcm[v] = sample > 1.0f ? 1.0f : sample < -1.0f ? -1.0f : sample;
    }
    player->position_samples += available;
    return available;
}
