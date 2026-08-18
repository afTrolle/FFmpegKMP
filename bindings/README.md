# Bindings

`:bindings` is a single internal Kotlin Multiplatform module. It stages the
headers from the pinned FFmpeg checkout once and uses that same input for every
generated platform binding.

The module will contain three backend lanes:

- Kotlin/Native cinterop for Apple targets;
- one shared JNI C++/Java binding implementation generated locally for both JVM
  and Android targets;
- Kotlin/Wasm interop for browser builds.

JVM and Android remain separate Kotlin publication targets, but they consume
the same generated JNI API and native bridge. Kotlin does not support a shared
JVM+Android intermediate source set, so shared code generation and C++ sources
form the common boundary instead.

`prepareFfmpegHeaders` currently stages FFmpeg headers under
`build/generated/ffmpeg-headers`, together with this module's licence. Later
generator and cinterop tasks should depend on that task and add target-specific
generated headers from the matching native-build output.

## Generation and distribution policy

Bindings are generated only in the user's local build environment. Generated
outputs are not release artifacts; see the authoritative
[licensing and distribution policy](../docs/licensing.md).

Binding generators for this module must:

- emit only the names, signatures, numerical constants, data-structure layouts,
  and accessors necessary for interoperability;
- not copy FFmpeg comments, documentation, inline implementations, or macro
  bodies into generated Kotlin, Java, C++, or WebAssembly binding sources;
- keep generated output inside ignored build directories; and
- retain enough provenance to identify the exact FFmpeg revision and headers
  used for generation.

## Licence

The `bindings` module, including project-authored binding code and generated
bindings, is licensed under the GNU Lesser General Public License version 2.1
or later (`LGPL-2.1-or-later`). The complete licence text is included in
[LICENSE](LICENSE).

This module-level licence does not change the Apache License 2.0 that applies to
the separate high-level FFmpegKMP Kotlin API and build-logic modules.
