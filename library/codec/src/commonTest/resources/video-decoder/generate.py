#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regenerates the VideoDecoder fixtures in this directory.

Usage: FFMPEG=/path/to/ffmpeg python3 generate.py [hdr]

With `hdr`, only hdr10-pq.mp4 and hlg.mp4 are regenerated.

FFMPEG must be an ffmpeg CLI built from this repository's pinned FFmpeg (the build has no lavfi
device, so every clip is encoded from PNG frames written here). hdr10-pq.mp4, hlg.mp4 and the -h264
clips need VideoToolbox, so run this on macOS.

Every SDR frame carries its index twice: as a 12-bit code of 16x16 black and white cells in the
top 32 rows (read by the tests, least significant bit first, left to right then top to bottom),
and as decimal digits below it for people.
"""
import os
import struct
import subprocess
import sys
import tempfile
import zlib

WIDTH, HEIGHT = 96, 64
CELL = 16
BITS = (WIDTH // CELL) * (32 // CELL)
FONT = {
    "0": ["111", "101", "101", "101", "111"],
    "1": ["010", "110", "010", "010", "111"],
    "2": ["111", "001", "111", "100", "111"],
    "3": ["111", "001", "111", "001", "111"],
    "4": ["101", "101", "111", "001", "001"],
    "5": ["111", "100", "111", "001", "111"],
    "6": ["111", "100", "111", "101", "111"],
    "7": ["111", "001", "010", "010", "010"],
    "8": ["111", "101", "111", "101", "111"],
    "9": ["111", "101", "111", "001", "111"],
}

# Frame durations of vfr.mp4 in milliseconds, cycled: they must match VideoDecoderSystemTest.
VFR_DURATIONS_MS = [40, 100, 20, 60, 250, 33, 17]
VFR_FRAMES = 28


def chunk(kind, data):
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def png(path, rows, bit_depth=8, extra_chunks=b""):
    header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, bit_depth, 2, 0, 0, 0)
    raw = b"".join(b"\x00" + row for row in rows)
    with open(path, "wb") as file:
        file.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + extra_chunks)
        file.write(chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def numbered_frame(path, index):
    pixels = [[0] * WIDTH for _ in range(HEIGHT)]
    for bit in range(BITS):
        if index >> bit & 1:
            x0, y0 = (bit % (WIDTH // CELL)) * CELL, (bit // (WIDTH // CELL)) * CELL
            for y in range(y0, y0 + CELL):
                for x in range(x0, x0 + CELL):
                    pixels[y][x] = 255
    for position, digit in enumerate(f"{index:04d}"):
        for row, bits in enumerate(FONT[digit]):
            for column, on in enumerate(bits):
                for dy in range(4):
                    for dx in range(4):
                        x = 8 + position * 20 + column * 4 + dx
                        y = 38 + row * 4 + dy
                        pixels[y][x] = 255 if on == "1" else 64
    png(path, [bytes(value for value in row for _ in range(3)) for row in pixels])


def pq_code(nits):
    m1, m2 = 2610 / 16384, 2523 / 32
    c1, c2, c3 = 3424 / 4096, 2413 / 128, 2392 / 128
    y = (nits / 10000) ** m1
    return ((c1 + c2 * y) / (1 + c3 * y)) ** m2


# ffmpeg's swscale spans limited-range luma by 219 * 257 in 16 bits where BT.2100 has 219 * 256, so it puts 10-bit
# white on 943 rather than 940. The HDR PNGs are scaled by the ratio, luma and chroma sharing it, so that the P010
# ffmpeg writes carries BT.2100's codes; check_codes() reads them back.
SWSCALE_10_BIT = 256 / 257


def hdr_frame(path):
    # Left half: 100 nit grey. Right half: a 1000 nit highlight. Full-range 16-bit PQ RGB, BT.2020.
    left = round(pq_code(100) * SWSCALE_10_BIT * 65535)
    right = round(pq_code(1000) * SWSCALE_10_BIT * 65535)
    row = b"".join(struct.pack(">HHH", *(3 * [left if x < WIDTH // 2 else right])) for x in range(WIDTH))
    cicp = chunk(b"cICP", bytes([9, 16, 0, 1]))
    # BT.2020 primaries and D65 in 0.00002 units, then 1000 and 0.0001 nits in 0.0001 units.
    mdcv = chunk(
        b"mDCV",
        struct.pack(">8H", 35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450)
        + struct.pack(">II", 1000 * 10000, 1),
    )
    clli = chunk(b"cLLI", struct.pack(">II", 1000 * 10000, 400 * 10000))
    png(path, [row] * HEIGHT, bit_depth=16, extra_chunks=cicp + mdcv + clli)


def hlg_frame(path):
    # Full-range 16-bit HLG RGB, BT.2020. Top: 75% grey (203 nits on a 1000 nit display) on the left and
    # peak white on the right. Bottom: 75% BT.2020 green on the left, outside sRGB, and 75% red on the right.
    patches = [[(0.75, 0.75, 0.75), (1.0, 1.0, 1.0)], [(0.0, 0.75, 0.0), (0.75, 0.0, 0.0)]]
    rows = []
    for y in range(HEIGHT):
        colours = patches[0 if y < HEIGHT // 2 else 1]
        rows.append(b"".join(
            struct.pack(">HHH", *(round(value * SWSCALE_10_BIT * 65535) for value in colours[0 if x < WIDTH // 2 else 1]))
            for x in range(WIDTH)))
    png(path, rows, bit_depth=16, extra_chunks=chunk(b"cICP", bytes([9, 18, 0, 1])))


def run(*arguments):
    subprocess.run([os.environ["FFMPEG"], "-hide_banner", "-loglevel", "error", "-y", *arguments], check=True)


def check_codes(path, expected):
    """Checks the first frame's 10-bit Y, Cb and Cr at each (x, y) against BT.2100's limited-range codes."""
    raw = subprocess.run(
        [os.environ["FFMPEG"], "-hide_banner", "-loglevel", "error", "-i", path, "-frames:v", "1",
         "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-"],
        check=True, capture_output=True).stdout
    chroma = WIDTH * HEIGHT * 2
    for (x, y), rgb in expected.items():
        luma = 0.2627 * rgb[0] + 0.6780 * rgb[1] + 0.0593 * rgb[2]
        want = (64 + 876 * luma, 512 + 896 * (rgb[2] - luma) / 1.8814, 512 + 896 * (rgb[0] - luma) / 1.4746)
        chroma_index = (y // 2) * (WIDTH // 2) + x // 2
        got = (
            struct.unpack_from("<H", raw, (y * WIDTH + x) * 2)[0],
            struct.unpack_from("<H", raw, chroma + chroma_index * 2)[0],
            struct.unpack_from("<H", raw, chroma + chroma // 4 + chroma_index * 2)[0],
        )
        for component, (value, target) in enumerate(zip(got, want)):
            assert abs(value - target) <= 2, f"{path} ({x}, {y}) component {component}: {value}, BT.2100 has {target:.1f}"


def mpeg4(output, *inputs):
    # Keyframes every 12 frames and B-frames, so seeks cross GOPs and frames are reordered.
    run(*inputs, "-c:v", "mpeg4", "-q:v", "5", "-g", "12", "-bf", "2", "-pix_fmt", "yuv420p", output)


def h264(source, output, *timing):
    # MediaCodec has hardware H.264 decoders everywhere, which the Android Surface tests need; FFmpeg skips software ones.
    run("-i", source, "-c:v", "h264_videotoolbox", "-profile:v", "main", "-b:v", "600k", "-g", "12", "-bf", "0",
        "-pix_fmt", "yuv420p", "-tag:v", "avc1", *timing, "-an", output)


def boxes(data, start=0, end=None):
    offset, end = start, len(data) if end is None else end
    while offset < end:
        size, kind = struct.unpack(">I4s", data[offset:offset + 8])
        yield kind, offset, size
        offset += size


def faststart(source, output):
    """Moves the moov box ahead of mdat, like -movflags +faststart, shifting the chunk offsets."""
    with open(source, "rb") as file:
        data = bytearray(file.read())
    top = {kind: (offset, size) for kind, offset, size in boxes(data)}
    moov_offset, moov_size = top[b"moov"]
    mdat_offset = top[b"mdat"][0]
    assert mdat_offset < moov_offset, "already faststart"
    moov = data[moov_offset:moov_offset + moov_size]

    def shift(start, end):
        for kind, offset, size in boxes(moov, start, end):
            if kind in (b"trak", b"mdia", b"minf", b"stbl"):
                shift(offset + 8, offset + size)
            elif kind == b"stco":
                count = struct.unpack(">I", moov[offset + 12:offset + 16])[0]
                for entry in range(offset + 16, offset + 16 + 4 * count, 4):
                    struct.pack_into(">I", moov, entry, struct.unpack(">I", moov[entry:entry + 4])[0] + moov_size)
            elif kind == b"co64":
                raise ValueError("co64 offsets are not handled")

    shift(8, moov_size)
    with open(output, "wb") as file:
        file.write(data[:mdat_offset] + moov + data[mdat_offset:moov_offset] + data[moov_offset + moov_size:])


def main():
    out = os.path.dirname(os.path.abspath(__file__))
    with tempfile.TemporaryDirectory() as work:
        for index in range(320):
            numbered_frame(os.path.join(work, f"f{index:04d}.png"), index)
        frames = os.path.join(work, "f%04d.png")
        for fps in (24, 30, 60):
            mpeg4(os.path.join(out, f"cfr-{fps}.mp4"), "-framerate", str(fps), "-i", frames, "-frames:v", str(5 * fps))

        playlist = os.path.join(work, "vfr.txt")
        with open(playlist, "w") as file:
            for index in range(VFR_FRAMES):
                file.write(f"file 'f{index:04d}.png'\noption framerate 1000\n")
                file.write(f"duration {VFR_DURATIONS_MS[index % len(VFR_DURATIONS_MS)] / 1000}\n")
        mpeg4(
            os.path.join(out, "vfr.mp4"),
            "-f", "concat", "-safe", "0", "-i", playlist,
            "-fps_mode", "passthrough", "-enc_time_base", "1/1000", "-video_track_timescale", "1000",
        )

        # Index first, so a stall or a cut after the header leaves an openable input.
        faststart(os.path.join(out, "cfr-30.mp4"), os.path.join(out, "cfr-30-faststart.mp4"))

        for name in ("cfr-24", "cfr-30"):
            h264(os.path.join(out, f"{name}.mp4"), os.path.join(out, f"{name}-h264.mp4"))
        # Hardware decoders have a minimum size (96x96 on Qualcomm), so the Android device tests use cfr-30 padded
        # to 128x128, with the frame code still in the top-left corner.
        h264(os.path.join(out, "cfr-30.mp4"), os.path.join(out, "cfr-30-h264-128.mp4"), "-vf", "pad=128:128:0:0:black")
        h264(os.path.join(out, "vfr.mp4"), os.path.join(out, "vfr-h264.mp4"),
             "-fps_mode", "passthrough", "-enc_time_base", "1/1000", "-video_track_timescale", "1000")

        short = os.path.join(work, "short.mp4")
        mpeg4(short, "-framerate", "30", "-i", frames, "-frames:v", "30")
        run("-display_rotation", "90", "-i", short, "-c", "copy", os.path.join(out, "rotated-90.mp4"))

        hdr(out, work)


def hdr(out, work):
    for index in range(10):
        hdr_frame(os.path.join(work, f"h{index:04d}.png"))
    run(
        "-framerate", "10", "-i", os.path.join(work, "h%04d.png"),
        "-c:v", "hevc_videotoolbox", "-profile:v", "main10", "-pix_fmt", "p010le", "-b:v", "400k",
        "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
        "-color_range", "tv", "-tag:v", "hvc1", os.path.join(out, "hdr10-pq.mp4"),
    )
    grey = lambda nits: (pq_code(nits),) * 3
    check_codes(os.path.join(out, "hdr10-pq.mp4"), {(12, 32): grey(100), (84, 32): grey(1000)})
    hlg(out, work)


def hlg(out, work):
    for index in range(10):
        hlg_frame(os.path.join(work, f"g{index:04d}.png"))
    run(
        "-framerate", "10", "-i", os.path.join(work, "g%04d.png"),
        "-c:v", "hevc_videotoolbox", "-profile:v", "main10", "-pix_fmt", "p010le", "-b:v", "400k",
        "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc",
        "-color_range", "tv", "-tag:v", "hvc1", os.path.join(out, "hlg.mp4"),
    )
    check_codes(os.path.join(out, "hlg.mp4"), {
        (12, 16): (0.75, 0.75, 0.75), (84, 16): (1.0, 1.0, 1.0), (12, 48): (0.0, 0.75, 0.0), (84, 48): (0.75, 0.0, 0.0),
    })


if __name__ == "__main__":
    if sys.argv[1:] == ["hdr"]:
        with tempfile.TemporaryDirectory() as scratch:
            sys.exit(hdr(os.path.dirname(os.path.abspath(__file__)), scratch))
    sys.exit(main())
