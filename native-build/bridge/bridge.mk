# SPDX-License-Identifier: LGPL-2.1-or-later
include ffbuild/config.mak

# Kotlin/Native Apple runtimes pool frames in CVPixelBuffers; the JVM bridge does not link CoreVideo.
FFMPEGKMP_PIXEL_BUFFER ?= 0
DECODER_CFLAGS = $(if $(and $(filter 1,$(FFMPEGKMP_PIXEL_BUFFER)),$(CONFIG_VIDEOTOOLBOX)),-DFFMPEGKMP_PIXEL_BUFFER=1)

# Wrappers that #include an fftools source, replacing its object in FFTOOLS_OBJECTS.
FFTOOLS_BRIDGE_OBJECTS = ffmpegkmp_ffmpeg_entry.o ffmpegkmp_ffprobe_entry.o ffmpegkmp_ffmpeg_opt.o ffmpegkmp_opt_common.o

.PHONY: ffmpegkmp-bridge
ffmpegkmp-bridge:
	$(CC) $(CFLAGS) -std=c11 -DFFMPEGKMP_EMBEDDED_FFTOOLS=$(FFMPEGKMP_EMBEDDED_FFTOOLS) -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpegkmp_bridge.c -o ffmpegkmp_bridge.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpegkmp_player.c -o ffmpegkmp_player.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffplaykmp_core.c -o ffplaykmp_core.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffplaykmp_player.c -o ffplaykmp_player.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) $(DECODER_CFLAGS) -c $(BRIDGE_SOURCE)/ffmpegkmp_decoder.c -o ffmpegkmp_decoder.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) $(DECODER_CFLAGS) -c $(BRIDGE_SOURCE)/ffmpegkmp_frame.c -o ffmpegkmp_frame.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) $(DECODER_CFLAGS) -c $(BRIDGE_SOURCE)/ffmpegkmp_writer.c -o ffmpegkmp_writer.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpegkmp_android_bitmap.c -o ffmpegkmp_android_bitmap.o
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpeg_entry.c -o ffmpegkmp_ffmpeg_entry.o)
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffprobe_entry.c -o ffmpegkmp_ffprobe_entry.o)
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpeg_opt_reset.c -o ffmpegkmp_ffmpeg_opt.o)
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/opt_common_reset.c -o ffmpegkmp_opt_common.o)
	$(AR) rcs $(BRIDGE_INSTALL)/lib/libffmpegkmp_bridge.a ffmpegkmp_bridge.o ffmpegkmp_player.o ffplaykmp_core.o ffplaykmp_player.o ffmpegkmp_decoder.o ffmpegkmp_frame.o ffmpegkmp_writer.o ffmpegkmp_android_bitmap.o $(if $(FFTOOLS_OBJECTS),$(FFTOOLS_BRIDGE_OBJECTS) $(FFTOOLS_OBJECTS))
	cp $(BRIDGE_SOURCE)/ffmpegkmp_bridge.h $(BRIDGE_INSTALL)/include/ffmpegkmp_bridge.h
	cp $(BRIDGE_SOURCE)/ffplaykmp_player.h $(BRIDGE_INSTALL)/include/ffplaykmp_player.h
	cp $(BRIDGE_SOURCE)/ffmpegkmp_decoder.h $(BRIDGE_INSTALL)/include/ffmpegkmp_decoder.h
	cp $(BRIDGE_SOURCE)/ffmpegkmp_frame.h $(BRIDGE_INSTALL)/include/ffmpegkmp_frame.h
	cp $(BRIDGE_SOURCE)/ffmpegkmp_writer.h $(BRIDGE_INSTALL)/include/ffmpegkmp_writer.h
