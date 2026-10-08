# Public library modules

`core` is the shared FFmpegKMP runtime base. `ffmpeg`, `ffprobe`, `filters`,
`codec`, `player`, and `ffplay` layer the public API on top without exposing
binding details.

- `core` owns process-wide FIFO scheduling, observable sessions, structured
  events, results, cancellation, and Okio `FileHandle`/`Source`/`Sink`
  ownership. File handles provide random access; streams remain non-seekable.
- `ffmpeg` owns raw arguments, deterministic command tokenization, and the
  ordered command DSL.
- `ffprobe` owns queries plus forward-compatible typed JSON models.
- `filters` owns the filter graph AST and compilation to `-filter_complex`.
- `codec` owns the Compose-free frame model (`VideoFrame`, whose memory FFmpeg
  owns, and its `FrameFormat`), frame-accurate pull decoding (`VideoDecoder`)
  for exporters, and the types decoders and players share: the media source
  (`MediaSource`, `ContentProtection`), the decoder choice and threads
  (`DecoderPreference`, `DecoderKind`, `DecoderThreads`), and the stream
  description (`VideoInfo`, `HdrType` and the HDR metadata types). The decoder
  runs outside the command FIFO.
- `player` owns audio decoding (`AudioDecoder`) and playback (`AudioPlayer`)
  with live per-track and master `AudioLevel`s and track selection. It uses the
  bridge's libav-based engine, not fftools, so it does not join the FIFO.
- `ffplay` owns Compose video playback (`FFplayPlayer`, `FFplaySurface`) on its
  own engine, with audio played through `player` and slaved to the video clock,
  and draws `codec` frames with `VideoFrame.toImageBitmap()`, or through one reused bitmap per
  source with `FrameImage`. Its `ComposeFrameRenderer`
  draws Compose content into `codec` frames for an export. It depends on
  `codec` and keeps FFplay names for the `codec` types as typealiases
  (`FFplaySource`, `FFplayVideoInfo`, …).
