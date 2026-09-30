// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_bridge.h"
#include "libavformat/avformat.h"

static int ffmpegkmp_av_read_frame(AVFormatContext *context, AVPacket *packet) {
    if (ffmpegkmp_cancel_requested())
        return AVERROR_EXIT;
    return av_read_frame(context, packet);
}

#define main ffmpegkmp_ffprobe_main_impl
#define program_name ffmpegkmp_ffprobe_program_name
#define program_birth_year ffmpegkmp_ffprobe_program_birth_year
#define show_help_default ffmpegkmp_ffprobe_show_help_default
#define exit ffmpegkmp_exit
#define av_read_frame ffmpegkmp_av_read_frame
int ffmpegkmp_ffprobe_main_impl(int argc, char **argv);
#include "fftools/ffprobe.c"
#undef av_read_frame
#undef exit
#undef show_help_default
#undef program_birth_year
#undef program_name
#undef main

int ffmpegkmp_ffprobe_entry(int argc, char **argv);

int ffmpegkmp_ffprobe_entry(int argc, char **argv) {
    do_analyze_frames = do_bitexact = do_count_frames = do_count_packets = 0;
    do_read_frames = do_read_packets = 0;
    do_show_chapters = do_show_error = do_show_format = do_show_frames = 0;
    do_show_packets = do_show_programs = do_show_stream_groups = 0;
    do_show_stream_group_components = do_show_streams = 0;
    do_show_stream_disposition = do_show_stream_group_disposition = 0;
    do_show_data = do_show_program_version = do_show_library_versions = 0;
    do_show_pixel_formats = do_show_pixel_format_flags = 0;
    do_show_pixel_format_components = do_show_log = 0;
    do_show_chapter_tags = do_show_format_tags = do_show_frame_tags = 0;
    do_show_program_tags = do_show_stream_group_tags = do_show_stream_tags = 0;
    do_show_packet_tags = 0;
    show_value_unit = use_value_prefix = use_byte_value_binary_prefix = 0;
    use_value_sexagesimal_format = 0;
    show_private_data = 1;
    show_optional_fields = SHOW_OPTIONAL_FIELDS_AUTO;
    find_stream_info = 1;
    read_intervals_nb = 0;
    /* The CLI assumes a fresh process: -select_streams and the other string
     * options, the -show_entries selections and the cmdutils option dicts
     * outlive main(), and an exit() mid-run skips main's own cleanup. */
    av_freep(&stream_specifier);
    av_freep(&output_format);
    av_freep(&show_data_hash);
    av_freep(&data_dump_format);
    av_freep(&input_filename);
    av_freep(&print_input_filename);
    av_freep(&output_filename);
    av_freep(&read_intervals);
    av_freep(&audio_codec_name);
    av_freep(&data_codec_name);
    av_freep(&subtitle_codec_name);
    av_freep(&video_codec_name);
    iformat = NULL;
    hide_banner = 0;
    for (size_t i = 0; i < FF_ARRAY_ELEMS(selected_entries); i++) {
        selected_entries[i].show_all_entries = 0;
        av_dict_free(&selected_entries[i].entries_to_show);
    }
    clear_log(1);
    uninit_opts();
    return ffmpegkmp_ffprobe_main_impl(argc, argv);
}
