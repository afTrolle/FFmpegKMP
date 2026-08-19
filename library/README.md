# Public library modules

`core` is the shared FFmpegKMP runtime base. `ffmpeg`, `ffprobe`, and `filters`
layer the public API on top without exposing binding details.

- `core` owns process-wide FIFO scheduling, observable sessions, structured
  events, results, cancellation, and `Source`/`Sink` ownership.
- `ffmpeg` owns raw arguments, deterministic command tokenization, and the
  ordered command DSL.
- `ffprobe` owns queries plus forward-compatible typed JSON models.
- `filters` owns the filter graph AST and compilation to `-filter_complex`.
