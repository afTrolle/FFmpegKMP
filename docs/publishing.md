# Publishing to Maven Central

FFmpegKMP publishes Kotlin APIs and generated binding declarations, but never
publishes an FFmpeg executable, FFmpeg library, JNI shim, Apple framework,
object file, or WebAssembly runtime. The release workflow builds a signed local
Maven repository first and rejects the release if native content is found in
any publication archive, including nested archives.

These are declaration/API artifacts, not ready-to-run FFmpeg distributions.
Consumers must build or otherwise provide a compatible native runtime under
the applicable platform and licence rules.

Only `:bindings` and the four projects under `:library` apply the publishing
plugin. Native-build and sample projects are excluded. The
`verifyMavenPublicationScope` task fails if that allow-list changes, and the
archive scan also rejects sample package paths.

## Published coordinates

Use the root publication from `commonMain`; Kotlin Gradle selects the matching
target artifact.

| Module | Maven coordinate |
| --- | --- |
| Bindings | `io.github.aftrolle.ffmpegkmp:bindings:<version>` |
| Core | `io.github.aftrolle.ffmpegkmp:core:<version>` |
| FFmpeg API | `io.github.aftrolle.ffmpegkmp:ffmpeg:<version>` |
| FFprobe API | `io.github.aftrolle.ffmpegkmp:ffprobe:<version>` |
| Filters DSL | `io.github.aftrolle.ffmpegkmp:filters:<version>` |

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.aftrolle.ffmpegkmp:ffmpeg:<version>")
        implementation("io.github.aftrolle.ffmpegkmp:ffprobe:<version>")
        implementation("io.github.aftrolle.ffmpegkmp:filters:<version>")
    }
}
```

## 1. Create the Maven Central publisher account

1. Sign in to the [Central Publisher Portal](https://central.sonatype.com/) with
   the GitHub account that owns `github.com/afTrolle`. A GitHub sign-in normally
   provisions the personal `io.github.aftrolle` namespace automatically.
2. Open **Publish → Namespaces** and confirm that `io.github.aftrolle` is
   verified. If it is missing, add it and follow the GitHub ownership check in
   the [namespace registration guide](https://central.sonatype.org/register/namespace/).
   The configured group, `io.github.aftrolle.ffmpegkmp`, is allowed beneath
   that namespace.
3. Open the [user-token page](https://central.sonatype.com/usertoken), generate
   a named token, and save both generated values. These are the publishing
   username and password; they are not the credentials used to sign in to the
   portal. The token cannot be viewed again after its dialog is closed.

The old OSSRH/Jira registration flow should not be used. New publishing goes
through the Central Publisher Portal.

## 2. Create the PGP signing key

Maven Central requires every deployed file to have a PGP signature. Install
GnuPG and create a key using the maintainer identity:

```shell
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
```

Copy the full fingerprint or long key ID, then distribute the public key to a
key server supported by Central:

```shell
gpg --keyserver keyserver.ubuntu.com --send-keys <full-key-fingerprint>
```

Export the private key for CI. This command prints a secret, so run it only in
a private terminal and never redirect it into the repository:

```shell
gpg --armor --export-secret-keys <full-key-fingerprint>
```

Keep the complete output, including the `BEGIN` and `END` lines. Back up the
private key and revocation certificate securely. See Central's
[PGP guide](https://central.sonatype.org/publish/requirements/gpg/) for key
creation, distribution, expiry, and rotation.

## 3. Configure local credentials

The Vanniktech publishing plugin reads Gradle properties. For a local release,
provide them through environment variables so nothing secret is committed:

```shell
export ORG_GRADLE_PROJECT_mavenCentralUsername='<token-username>'
export ORG_GRADLE_PROJECT_mavenCentralPassword='<token-password>'
export ORG_GRADLE_PROJECT_signingInMemoryKey='<complete-armored-private-key>'
export ORG_GRADLE_PROJECT_signingInMemoryKeyId='<optional-last-eight-key-id-characters>'
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='<private-key-passphrase>'
```

The build already configures `publishToMavenCentral()`, publication signing,
sources and Javadoc artifacts, and the required project, licence, developer,
and SCM POM metadata. Maven Central's complete validation rules are listed in
its [publishing requirements](https://central.sonatype.org/publish/requirements/).

## 4. Configure the GitHub publishing environment

Create or open the repository's
[`maven-central` environment](https://github.com/afTrolle/FFmpegKMP/settings/environments/20561370049/edit)
under **Settings → Environments**. Add these as environment secrets:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central user-token password |
| `SIGNING_KEY` | Complete ASCII-armored private key |
| `SIGNING_KEY_PASSWORD` | PGP private-key passphrase |
| `SIGNING_KEY_ID` | Optional last eight characters of the signing key ID |

The workflow in `.github/workflows/publish.yml` runs on a published GitHub
Release and requires the `maven-central` environment. It checks out the release
tag and submodules, verifies the version, stages and signs every KMP
publication, scans every archive for native content, and then calls
`publishAndReleaseToMavenCentral`. Configure required reviewers on the
environment when a manual approval gate is desired before its publishing
secrets are exposed.

## 5. Understand automatic versions

All modules receive one version from the shared `ffmpegkmp.project` convention.
Resolution order is:

1. `-Pffmpegkmp.version=<version>`, when explicitly supplied;
2. a semantic GitHub tag in `GITHUB_REF_NAME` during a tag workflow;
3. a semantic tag pointing at `HEAD`, when the local tracked worktree is clean;
4. the nearest reachable semantic tag with `-SNAPSHOT` appended; or
5. `0.1.0-SNAPSHOT` when the repository has no release tag.

Tags may be `v1.2.3` or `1.2.3`; the optional `v` is not part of the Maven
version. For example:

| Git state | Derived Maven version |
| --- | --- |
| clean `HEAD` tagged `v1.2.3` | `1.2.3` |
| commits after `v1.2.3` | `1.2.3-SNAPSHOT` |
| tracked changes on tagged `v1.2.3` | `1.2.3-SNAPSHOT` |
| no semantic tag | `0.1.0-SNAPSHOT` |

Inspect the result at any time:

```shell
./gradlew -q printVersion --no-configuration-cache
```

Central releases are immutable. Never move a release tag or try to reuse a
version that reached Maven Central; publish a new version instead.

## 6. Check binding and binary licence boundaries

The JavaCPP tool is dual-licensed under Apache 2.0 or GPLv2-or-later with the
Classpath exception; this project uses it as an Apache-licensed dependency and
does not bundle the JavaCPP tool itself. JavaCPP does not grant a new licence
to output parsed from third-party headers.

The generated Java declarations represent FFmpeg APIs parsed from FFmpeg
headers. The project therefore takes the conservative approach of publishing
the declarations only inside the `bindings` module under
`LGPL-2.1-or-later`, with an SPDX provenance header, `bindings/LICENSE`, and
the relevant notices. The high-level Kotlin modules remain Apache-2.0.

Before each release, confirm that:

- generated sources contain declarations only, not copied FFmpeg documentation,
  inline implementations, or macro bodies;
- `bindings/LICENSE` and `THIRD_PARTY_NOTICES.md` still describe the generated
  declarations and the pinned FFmpeg revision accurately;
- JavaCPP remains a normal Maven dependency rather than a shaded dependency;
- no publication contains `.so`, `.dylib`, `.dll`, `.a`, `.o`, `.wasm`,
  `.framework`, `.xcframework`, `ffmpeg`, or `ffprobe`; and
- the effective FFmpeg build has not enabled GPL or nonfree features that alter
  the release review.

This is a cautious engineering policy, not legal advice. Have the generated
binding release reviewed by qualified counsel if the legal classification is
material to a commercial distribution.

## 7. Run the local release preflight

Release from a clean macOS checkout because KMP Apple publications require
Apple toolchains. Initialize the pinned submodules first:

```shell
git submodule update --init --recursive
git status --short
```

Choose the release version and stage all signed publications in the repository
build directory:

```shell
release_version='0.1.0'
./gradlew verifyMavenPublicationScope \
    publishAllPublicationsToReleaseCheckRepository \
    -Pffmpegkmp.version="$release_version" \
    --no-configuration-cache
```

Run the mandatory native-content scan:

```shell
scripts/verify-no-native-binaries.sh build/release-check-repository
```

To inspect artifact contents without loading a private signing key, stage an
explicitly unsigned local audit repository instead:

```shell
./gradlew verifyMavenPublicationScope \
    publishAllPublicationsToReleaseCheckRepository \
    -Pffmpegkmp.version="$release_version" \
    -Pffmpegkmp.unsignedPublicationAudit=true \
    --no-configuration-cache
scripts/verify-no-native-binaries.sh build/release-check-repository
```

That switch is rejected by Maven Central publication tasks. It is only for the
local `releaseCheck` repository; real preflight and release builds remain
signed.

The check is deliberately performed against the Maven repository that would be
uploaded, rather than against the source tree. It verifies the artifact-ID
allow-list and opens nested JAR, AAR, KLIB, and ZIP files to reject native
content or sample application packages. Do not publish if this check fails.

Optionally test dependency resolution from the staged repository in a small
consumer project before releasing. Use this repository URL:

```text
<checkout>/build/release-check-repository
```

## 8. Tag and publish the release

Create a signed semantic tag on the exact reviewed commit and push it:

```shell
git tag -s v0.1.0 -m 'FFmpegKMP 0.1.0'
git push origin v0.1.0
```

Create a GitHub Release for that existing tag and publish it. Publishing the
release starts `Publish to Maven Central`. Do not configure both a tag-push and
a release-published trigger, because that can attempt to upload the same
immutable Maven version twice.

If tag pushes are preferred instead of GitHub Releases, replace the workflow
trigger with:

```yaml
on:
  push:
    tags:
      - "v*"
```

After the action succeeds, confirm the deployment in the
[Central Portal deployments page](https://central.sonatype.com/publishing/deployments)
and search for `io.github.aftrolle.ffmpegkmp`. Portal validation and repository
synchronization can take a few minutes.

## Manual emergency publish

The same guarded flow can be run locally after preflight:

```shell
./gradlew publishAndReleaseToMavenCentral \
    -Pffmpegkmp.version="$release_version" \
    --no-configuration-cache
```

`publishToMavenCentral` uploads for portal review without automatically
releasing. `publishAndReleaseToMavenCentral` releases after successful portal
validation. Always publish the complete KMP module set; publishing only the
root `kotlinMultiplatform` publication leaves target artifacts missing.

[Back to the project README](../README.md)
