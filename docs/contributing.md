# Contributing

FFmpegKMP includes native FFmpeg build pipelines, generated JavaCPP and
Kotlin/Native bindings, browser worker bindings, and the public multiplatform
runtime. Development work should preserve the boundary between declaration-only
Maven artifacts and locally built native runtimes.

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

The Gradle daemon uses an Adoptium Java 21 toolchain. Android builds require the
configured Android NDK, Apple builds require Xcode, JVM builds require the host
C toolchain, and browser builds require an activated Emscripten SDK. See the
native build documentation for versions, overrides, and target-specific setup.

Run the portable test matrix with `./gradlew allTests`. This always compiles and
links the tvOS simulator test executables, but does not launch them because a
tvOS SDK installation does not necessarily include a runnable simulator. On a
host with a tvOS simulator runtime, opt into execution with:

```shell
./gradlew allTests -Pffmpegkmp.runTvosSimulatorTests=true
```

## Dependency integrity

Gradle dependency versions are centralized in `gradle/libs.versions.toml`, and
the wrapper version is pinned by the checked-in wrapper files. Kotlin/JS and
Kotlin/Wasm package-manager state is committed in `kotlin-js-store/yarn.lock`.
Gradle dependency locking and checksum verification metadata are not currently
enabled, so do not describe the repository as using them.

After an intentional dependency update, review the changed coordinates, refresh
the Kotlin package-manager lock when applicable, and run `./gradlew allTests`.
If Gradle dependency verification is introduced later, independently confirm
new checksums before committing them: generated checksums only record the
artifacts returned by the configured repositories and do not prove that those
artifacts are trustworthy.

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

## Shared media test fixtures

Modules that exercise the repository's media samples opt in with the
`ffmpegkmp.shared-media-test-fixtures` convention plugin. Keep this concern out
of the base multiplatform convention: a new module should apply the fixture
plugin explicitly instead of adding project-name or project-path checks to
shared build logic.

Every published multiplatform library also exposes `commonTestAllTargets` from
the base convention. It runs common tests on Android host, JVM, browser, iOS
simulator, and macOS targets, then compiles test variants for device-only and
non-runnable Apple targets. Use this task as the cross-platform gate for shared
behavior.

[Back to the project README](../README.md)
