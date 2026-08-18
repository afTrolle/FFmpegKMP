# Contributing

FFmpegKMP is still in its initial scaffolding and source-pinning stages. No
compilation or native build is expected to work yet.

## Development checkout

Clone the repository together with the pinned FFmpeg submodule:

```shell
git clone --recurse-submodules https://github.com/afTrolle/FFmpegKMP.git
cd FFmpegKMP
```

For an existing clone, or after switching branches or pulling changes, update
the submodule so it matches the revision expected by the parent repository:

```shell
git submodule update --init --recursive
```

The Gradle daemon uses an Adoptium Java 21 toolchain. Additional SDK, NDK, and
Emscripten requirements will be documented as the corresponding native build
pipelines are implemented.

## Dependency integrity

Gradle dependencies are locked per module and verified against SHA-256
metadata. Kotlin/Wasm package-manager state is also committed when the Kotlin
plugin generates it.

After an intentional dependency update, regenerate the lock and verification
state with:

```shell
./gradlew build --write-locks --write-verification-metadata sha256
```

Review the changed coordinates and independently confirm new checksums before
committing them. Checksum generation records the artifacts currently returned
by the configured repositories; it does not prove that those artifacts are
trustworthy.

## Android namespaces

Android namespaces are derived from each Gradle project path. A single module
can override its namespace without affecting the others by using a path-scoped
property, for example:

```shell
./gradlew :bindings:assemble \
    -Pffmpegkmp.android.namespace.bindings=com.example.ffmpegkmp.bindings
```

See the [architecture](architecture.md), [native build](native-builds.md), and
[licensing](licensing.md) documentation for the constraints that apply to new
modules and build tasks.

[Back to the project README](../README.md)
