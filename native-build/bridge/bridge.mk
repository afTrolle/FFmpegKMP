# SPDX-License-Identifier: LGPL-2.1-or-later
include ffbuild/config.mak

.PHONY: ffmpegkmp-bridge
ffmpegkmp-bridge:
	$(CC) $(CFLAGS) -std=c11 -DFFMPEGKMP_EMBEDDED_FFTOOLS=$(FFMPEGKMP_EMBEDDED_FFTOOLS) -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpegkmp_bridge.c -o ffmpegkmp_bridge.o
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffmpeg_entry.c -o ffmpegkmp_ffmpeg_entry.o)
	$(if $(FFTOOLS_OBJECTS),$(CC) $(CFLAGS) -std=c11 -I. -I$(SRC_PATH) -I$(BRIDGE_SOURCE) -c $(BRIDGE_SOURCE)/ffprobe_entry.c -o ffmpegkmp_ffprobe_entry.o)
	$(AR) rcs $(BRIDGE_INSTALL)/lib/libffmpegkmp_bridge.a ffmpegkmp_bridge.o $(if $(FFTOOLS_OBJECTS),ffmpegkmp_ffmpeg_entry.o ffmpegkmp_ffprobe_entry.o $(FFTOOLS_OBJECTS))
	cp $(BRIDGE_SOURCE)/ffmpegkmp_bridge.h $(BRIDGE_INSTALL)/include/ffmpegkmp_bridge.h
