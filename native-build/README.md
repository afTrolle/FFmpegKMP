# Native build modules

Each child Gradle module owns the reproducible FFmpegKMP build pipeline for one
target family. Generated libraries and intermediate toolchains belong in
ignored `work/`, `out/`, and `toolchains/` directories under the relevant
module.

These modules produce local build outputs only. See the
[native build documentation](../docs/native-builds.md) for the intended build
model and the authoritative
[licensing and distribution policy](../docs/licensing.md) for publication and
downstream-distribution rules.
