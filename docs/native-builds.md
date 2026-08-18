# Native builds

Each child module under `native-build` owns the reproducible FFmpeg build
pipeline for one target family:

- `apple` for Apple toolchains;
- `android` for the Android NDK;
- `jvm` for supported desktop hosts; and
- `wasm` for Emscripten.

These pipelines are planned but not implemented yet. No native build is expected
to work at the current project stage.

## Local outputs

Generated libraries and intermediate toolchains belong in ignored `work/`,
`out/`, and `toolchains/` directories under the relevant module. The matching
outputs supply target-specific headers and libraries to the binding tasks.

Native artifacts should not depend on arbitrary libraries installed on a
developer machine. Each build pipeline is expected to record:

- the FFmpeg revision and applied patches;
- compiler, SDK, NDK, or Emscripten versions;
- target architecture and build profile;
- configure, compiler, and linker flags;
- optional native dependency versions; and
- hashes for produced artifacts.

This metadata should make locally generated artifacts traceable and help
reproduce local or CI failures.

Native builds remain local outputs and are not FFmpegKMP release artifacts. The
authoritative publication and downstream-distribution rules are in
[Licensing and distribution](licensing.md).

[Back to the project README](../README.md)
