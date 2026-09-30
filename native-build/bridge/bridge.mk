# SPDX-License-Identifier: LGPL-2.1-or-later
include ffbuild/config.mak

# Kotlin/Native Apple runtimes hand out CVPixelBuffers; the JVM bridge does not link CoreVideo.
FFMPEGKMP_PIXEL_BUFFER ?= 0
DECODER_CFLAGS = $(if $(and $(filter 1,$(FFMPEGKMP_PIXEL_BUFFER)),$(CONFIG_VIDEOTOOLBOX)),-DFFMPEGKMP_PIXEL_BUFFER=1)

.PHONY: ffmpegkmp-bridge
ffmpegkmp-bridge:
	$(CC) $(CFLAGS) -std=c11 -DFFMPEGKMP_EMBEDDED_FFTOOLS=$(FFMPEGKMP_EMBEDDED_FFTOOLS) -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpegkmp_bridge.c -o ffmpegkmp_bridge.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpegkmp_player.c -o ffmpegkmp_player.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffplaykmp_core.c -o ffplaykmp_core.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffplaykmp_player.c -o ffplaykmp_player.o
	$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) $(DECODER_CFLAGS) -c $(BRIDGE_SOURCE)/ffmpegkmp_decoder.c -o ffmpegkmp_decoder.o
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpeg_entry.c -o ffmpegkmp_ffmpeg_entry.o)
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffprobe_entry.c -o ffmpegkmp_ffprobe_entry.o)
	$(AR) rcs $(BRIDGE_INSTALL)/lib/libffmpegkmp_bridge.a ffmpegkmp_bridge.o ffmpegkmp_player.o ffplaykmp_core.o ffplaykmp_player.o ffmpegkmp_decoder.o $(if $(FFTOOLS_OBJECTS),ffmpegkmp_ffmpeg_entry.o ffmpegkmp_ffprobe_entry.o $(FFTOOLS_OBJECTS))
	cp $(BRIDGE_SOURCE)/ffmpegkmp_bridge.h $(BRIDGE_INSTALL)/include/ffmpegkmp_bridge.h
	cp $(BRIDGE_SOURCE)/ffplaykmp_player.h $(BRIDGE_INSTALL)/include/ffplaykmp_player.h
	cp $(BRIDGE_SOURCE)/ffmpegkmp_decoder.h $(BRIDGE_INSTALL)/include/ffmpegkmp_decoder.h
