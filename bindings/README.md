# Bindings

`:bindings` is a single internal Kotlin Multiplatform module. It stages the
headers from the pinned FFmpeg checkout once and uses that same input for every
generated platform binding.

The module contains three backend lanes:

- Kotlin/Native cinterop for Apple targets;
- one shared JNI C++/Java binding implementation generated locally for both JVM
  and Android targets;
- Kotlin/Wasm interop for browser builds.

JVM and Android remain separate Kotlin publication targets, but they consume
the same generated JNI API and native bridge. Kotlin does not support a shared
JVM+Android intermediate source set, so shared code generation and C++ sources
form the common boundary instead.

`prepareFfmpegHeaders` stages FFmpeg headers under
`build/generated/ffmpeg-headers`. `generateJavaCppBindings` runs one JavaCPP
parser task for each library and the command bridge; `verifyJavaCppBindings`
compiles the complete generated declaration set. Apple targets create one
`ffmpeg` cinterop from `ffmpeg.def`, the matching target install tree, and the
umbrella header. See [binding generation](../docs/bindings.md).

## Generation and distribution policy

Bindings are generated in the build environment. Maven releases include the
generated Java declarations needed by JVM and Android consumers and the
declaration-only Kotlin/Native cinterop klibs. Generated JNI libraries, FFmpeg
libraries, Apple frameworks, executables, and Wasm modules are local runtime
outputs and are never Maven release artifacts; see the authoritative
[licensing and distribution policy](../docs/licensing.md).

App-facing local runtime tasks are:

```shell
./gradlew :bindings:assembleJavaCppAndroidRuntime
./gradlew :bindings:assembleJavaCppHostRuntime
./gradlew :bindings:assembleWasmRuntime
./gradlew :native-build:apple:packageFfmpegStandardXcframeworks
```

The Android AAR is binary-only so it can be used alongside the declaration
classes from Maven without duplicate classes. See [Using FFmpegKMP in an
application](../docs/consuming.md) for platform integration.

Binding generators for this module must:

- emit only the names, signatures, numerical constants, data-structure layouts,
  and accessors necessary for interoperability;
- not copy FFmpeg comments, documentation, inline implementations, or macro
  bodies into generated Kotlin, Java, C++, or WebAssembly binding sources;
- keep generated intermediates inside ignored build directories;
- publish declarations only through the LGPL-licensed `bindings` artifact; and
- retain enough provenance to identify the exact FFmpeg revision and headers
  used for generation.

## Licence

The `bindings` module, including project-authored binding code and generated
bindings, is licensed under the GNU Lesser General Public License version 2.1
or later (`LGPL-2.1-or-later`). The complete licence text is included in
[LICENSE](LICENSE).

This module-level licence does not change the Apache License 2.0 that applies to
the separate high-level FFmpegKMP Kotlin API and build-logic modules.
