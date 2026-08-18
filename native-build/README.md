# Native build modules

Each child Gradle module owns the FFmpeg binary pipeline for one target family.
Android produces a Prefab AAR, Apple produces static install trees and
XCFrameworks, and JVM desktop produces host shared libraries. Generated
libraries and intermediate files belong in ignored `work/` and `out/`
directories under the relevant module.

Run `./gradlew assembleNativeBinaries` for the default profile. These modules
produce local build outputs only. See the [native build
documentation](../docs/native-builds.md) for tasks, configuration, artifacts,
and the authoritative
[licensing and distribution policy](../docs/licensing.md) for publication and
downstream-distribution rules.
