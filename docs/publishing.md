# Maven Central publishing

Every publishable Kotlin Multiplatform module uses the shared
`ffmpegkmp.multiplatform-library` convention. The convention creates the root
Kotlin Multiplatform publication and target-specific publications, attaches
sources and Javadoc jars, supplies Maven Central POM metadata, and signs every
publication.

## Coordinates

Consumers add the root publication to `commonMain`; Kotlin's Gradle plugin then
selects the matching target artifact.

| Module | Maven coordinate |
| --- | --- |
| Bindings | `io.github.aftrolle.ffmpegkmp:bindings:<version>` |
| Core | `io.github.aftrolle.ffmpegkmp:core:<version>` |
| FFmpeg API | `io.github.aftrolle.ffmpegkmp:ffmpeg:<version>` |
| FFprobe API | `io.github.aftrolle.ffmpegkmp:ffprobe:<version>` |
| Filters DSL | `io.github.aftrolle.ffmpegkmp:filters:<version>` |

For example:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aftrolle.ffmpegkmp:ffmpeg:<version>")
            implementation("io.github.aftrolle.ffmpegkmp:ffprobe:<version>")
            implementation("io.github.aftrolle.ffmpegkmp:filters:<version>")
        }
    }
}
```

The high-level modules bring in their internal module dependencies. A consumer
only needs a direct dependency on `bindings` when using its API directly.

## Release prerequisites

Publishing requires a verified Maven Central namespace for
`io.github.aftrolle`, a Central Portal user token, and a public PGP key whose
private half is available to Gradle. Keep credentials outside the repository,
for example as environment-backed Gradle properties:

```shell
export ORG_GRADLE_PROJECT_mavenCentralUsername='<central-token-username>'
export ORG_GRADLE_PROJECT_mavenCentralPassword='<central-token-password>'
export ORG_GRADLE_PROJECT_signingInMemoryKey='<ascii-armored-private-key>'
export ORG_GRADLE_PROJECT_signingInMemoryKeyId='<optional-last-eight-key-id-characters>'
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='<private-key-passphrase>'
```

Publish the complete multiplatform library from one macOS host. This prevents
duplicate target publications and is required for the Apple cinterop outputs.
Before publishing, initialize the FFmpeg submodule and build the selected native
profile and generated binding inputs described in [Native builds](native-builds.md)
and [Binding generation](bindings.md).

## Publish

The checked-in default is the development version `0.1.0-SNAPSHOT`. Supply a
non-SNAPSHOT release version on the command line so every module and every
target receives exactly the same version:

```shell
./gradlew publishToMavenCentral \
    -Pffmpegkmp.version=0.1.0 \
    --no-configuration-cache
```

This uploads the publications for validation in the Central Portal. To ask the
plugin to release the validated deployment without a separate portal action,
use `publishAndReleaseToMavenCentral` instead.

For local inspection without Central credentials, generate a module's POM or
publish it to the local Maven repository:

```shell
./gradlew :library:core:generatePomFileForKotlinMultiplatformPublication
./gradlew :library:core:publishToMavenLocal
```

Do not publish only the root `kotlinMultiplatform` publication. A usable KMP
release requires that the root and all target-specific publications are
uploaded together.

[Back to the project README](../README.md)
