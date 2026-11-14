# Shared video fixtures

`big-buck-bunny-1s.mp4` is the small baseline H.264 test clip used by command tests and as the
compressed-frame source for the color-metadata fixtures below.

`playback-color-patches-1s.mp4` is a clean, deterministic one-second MPEG-2 clip used by the
compiled-player lifecycle, scheduling, seeking, frame-drop, and mounted-I/O tests. Its fixed color
patches keep those tests independent of decoder recovery from the damaged baseline sample.

`playback-gop-6s.mp4` and `playback-one-gop-6s.mp4` are six seconds of 64x64 MPEG-2 at 25 fps for
the player's catch-up tests: the first has a keyframe every 12 frames (0.48 s), the second only its
first frame. They are generated with:

```shell
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=25 -t 6 -c:v mpeg2video -g 12 -bf 0 -q:v 4 playback-gop-6s.mp4
ffmpeg -f lavfi -i testsrc2=size=64x64:rate=25 -t 6 -c:v mpeg2video -g 1000 -sc_threshold 1000000000 \
  -bf 0 -q:v 4 playback-one-gop-6s.mp4
```

`hardware-h264.mp4` is the same clean image sequence transcoded to H.264. It is reserved for
hardware-decoder acceptance tests because VideoToolbox must produce a real hardware frame; merely
advertising a hardware pixel format does not satisfy that test.

The color fixtures retain that clip's compressed frames and replace the H.264 VUI color fields with
FFmpeg's `h264_metadata` bitstream filter. They provide deterministic container-independent metadata:

| Fixture | Primaries | Transfer | Matrix | Expected classification |
| --- | --- | --- | --- | --- |
| `sdr-bt709.mp4` | BT.709 (1) | BT.709 (1) | BT.709 (1) | SDR |
| `sdr-display-p3.mp4` | Display P3 (12) | BT.709 (1) | BT.709 (1) | SDR wide gamut |
| `hdr10-pq.mp4` | BT.2020 (9) | SMPTE ST 2084/PQ (16) | BT.2020 NCL (9) | HDR10 |
| `hdr-hlg.mp4` | BT.2020 (9) | ARIB STD-B67/HLG (18) | BT.2020 NCL (9) | HLG |

Regenerate a fixture from the baseline with the standard FFmpegKMP bridge using this shape:

```shell
ffmpeg -i big-buck-bunny-1s.mp4 -map 0:v:0 -an -c:v copy \
  -bsf:v h264_metadata=colour_primaries=9:transfer_characteristics=16:matrix_coefficients=9 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc \
  -movflags +write_colr hdr10-pq.mp4
```

These fixtures validate color-metadata propagation and capability negotiation. They do not claim
that the reused eight-bit image samples are reference HDR luminance values; separate high-bit-depth
fixtures are required for F16 and tone-mapping pixel validation.
