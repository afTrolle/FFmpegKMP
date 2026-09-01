# Third-party notices

This file identifies third-party material intentionally referenced or included
in the FFmpegKMP source tree and material that users may compile locally. It
does not replace the applicable license texts.

## FFmpeg

FFmpegKMP contains a pinned FFmpeg source checkout in the `ffmpeg/` Git
submodule and provides build logic that can compile target-specific FFmpeg
binaries locally. Publication and downstream-distribution rules are defined in
the [licensing and distribution policy](docs/licensing.md).

FFmpeg is developed by the FFmpeg project and its contributors:

- Project: <https://ffmpeg.org/>
- Source: <https://git.ffmpeg.org/ffmpeg.git>
- Licence information: <https://ffmpeg.org/doxygen/trunk/md_LICENSE.html>
- Compliance guidance: <https://ffmpeg.org/legal.html>

Most FFmpeg files are licensed under the GNU Lesser General Public License
version 2.1 or later (`LGPL-2.1-or-later`). Some optional FFmpeg components and
external libraries are licensed under the GNU General Public License. Enabling
those components changes the licence that applies to the resulting FFmpeg
binary. Enabling `--enable-nonfree` produces a binary that FFmpeg describes as
unredistributable.

The authoritative licence texts for the pinned source are included in the
submodule, including:

- `ffmpeg/LICENSE.md`
- `ffmpeg/COPYING.LGPLv2.1`
- `ffmpeg/COPYING.LGPLv3`
- `ffmpeg/COPYING.GPLv2`
- `ffmpeg/COPYING.GPLv3`

FFmpegKMP's Apache License 2.0 does not replace or alter these licences.

The MediaCodec P010/HDR10 source overlay in `build-logic` contains
FFmpeg-derived source fragments and is licensed under `LGPL-2.1-or-later`.
Distributors of binaries produced with that overlay must provide the pinned
FFmpeg source and the overlay/build logic as part of the corresponding source.

Recommended attribution for an LGPL build:

> This product uses libraries from the FFmpeg project under the GNU Lesser
> General Public License version 2.1 or later. Corresponding source and build
> information are provided with the product or from its download location.

FFmpeg includes JPEG implementation files derived from work by the Independent
JPEG Group. When distributing executable-only FFmpeg artifacts, preserve the
credit and change-disclosure requirements described in `ffmpeg/LICENSE.md`.

> This software is based in part on the work of the Independent JPEG Group.

FFmpeg is a trademark of Fabrice Bellard. FFmpegKMP is an independent project
and is not affiliated with or endorsed by the FFmpeg project.

## Big Buck Bunny test clip

The Wasm FFprobe browser integration test includes an approximately one-second
excerpt of *Big Buck Bunny*:

- Original work: Copyright 2008 Blender Foundation
- Source clip: <https://github.com/cseitz/sample-files/blob/main/assets/video/mp4/bbb_short.mp4>
- Project: <https://peach.blender.org/>
- Licence: Creative Commons Attribution 3.0
  (<https://creativecommons.org/licenses/by/3.0/>)

The included test fixture was shortened and remuxed without re-encoding. Its
source and modification details are also recorded beside the fixture in
`library/ffprobe/src/wasmJsTest/resources/README.md`.

## Bindings module licence

As a cautious boundary for material generated from FFmpeg's LGPL-licensed
headers, the FFmpegKMP `bindings` module and its generated bindings are licensed
under `LGPL-2.1-or-later`. The complete licence text is included in
`bindings/LICENSE`. This module-level licence does not apply to the separate
high-level Kotlin API and build-logic modules, which remain Apache-2.0.

The policy for generated header-derived bindings is documented in
[Licensing and distribution](docs/licensing.md).

## Okio

The public mounted-I/O API and platform adapters use Okio:

- Project: <https://square.github.io/okio/>
- Source: <https://github.com/square/okio>
- Licence: Apache License 2.0

Okio remains separately copyrighted by Square, Inc. and its contributors.

## JavaCPP

JavaCPP generates and supports the JVM and Android declaration layer:

- Project: <https://github.com/bytedeco/javacpp>
- Licence choice used by FFmpegKMP: Apache License 2.0

The JavaCPP runtime is referenced as a normal Maven dependency and is not
shaded into FFmpegKMP. JavaCPP is also offered upstream under GPLv2-or-later
with the Classpath exception. Generated FFmpeg declarations are distributed
under the separate `bindings` module policy above; JavaCPP does not relicense
the FFmpeg headers parsed by the generator.

## Downstream distribution responsibility

The [licensing and distribution policy](docs/licensing.md) describes the
responsibilities that apply when distributing a locally generated FFmpeg binary,
generated bindings, or an application containing them.

## Other dependencies

Kotlin, Gradle plugins, native libraries, binding generators, and other build or
runtime dependencies retain their respective copyrights and licences. Before a
dependency is bundled into a release, its licence and notices must be added to
the release materials and checked against the effective FFmpeg build licence.
This file must be updated when such dependencies are introduced.
