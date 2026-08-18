# Licensing and distribution

This page is the authoritative FFmpegKMP policy for generated bindings, compiled
FFmpeg artifacts, and downstream distribution.

## Licences by component

Code authored for FFmpegKMP outside the `bindings` module is licensed under the
[Apache License 2.0](../LICENSE). It may be used in open-source and proprietary
software, including commercial products, subject to the licence conditions.

The [`bindings` module](../bindings/README.md), including generated bindings, is
separately licensed under `LGPL-2.1-or-later`; its complete licence text is in
[`bindings/LICENSE`](../bindings/LICENSE). This cautious module boundary does not
change the Apache licence applied to the separate high-level Kotlin APIs and
build logic.

FFmpeg and other third-party components are not relicensed under Apache 2.0.
They retain their own licences. See the
[third-party notices](../THIRD_PARTY_NOTICES.md) and the licence files in the
pinned `ffmpeg` submodule.

## No-binary-distribution policy

FFmpegKMP is a source-and-build-logic project, not an FFmpeg binary
distribution. Official FFmpegKMP packages, releases, public CI artifacts, and
downloadable caches do **not** include:

- FFmpeg executables;
- static or shared FFmpeg libraries;
- FFmpeg object code, frameworks, or WebAssembly modules; or
- generated, header-derived binding sources or objects.

Native build and binding-generation tasks create outputs only inside the user's
local build environment. CI may compile those outputs for verification, but it
must discard them after the job and must not expose them as downloadable
artifacts. Any future publishing task must exclude all such outputs.

Downloading, building, or using FFmpeg locally is not an official FFmpegKMP
binary distribution. A person or organization that gives a generated binary or
an application containing it to someone else becomes the downstream distributor
of that output and must independently satisfy the applicable legal requirements.

If generated bindings are ever distributed, they must be kept in a separate
artifact with the LGPL licence and applicable notices, and that exact artifact
must be reviewed for compliance before publication.

## What users need to do

The requirements depend on what is used and distributed:

- **Using only the Apache-licensed FFmpegKMP source or high-level API:** follow
  Apache 2.0. When redistributing the code or derivatives, provide a copy of the
  licence, mark modified files, and preserve applicable copyright, patent,
  trademark, and attribution notices.
- **Using or distributing the bindings module:** follow LGPL 2.1 or later and
  preserve its licence and applicable notices. Generated bindings remain under
  the bindings module's LGPL licence even when no FFmpeg binary is included.
- **Using FFmpeg without distributing it:** the LGPL's source-distribution
  obligations generally apply when copies are conveyed to others, not merely
  when software is run privately. Other laws and agreements may still apply.
- **Distributing an LGPL FFmpeg binary:** include the applicable LGPL text and
  prominent FFmpeg attribution; provide the exact corresponding FFmpeg source,
  including changes; document the FFmpeg revision and complete build
  configuration; and allow reverse engineering for debugging modifications to
  the LGPL-covered library. FFmpeg publishes a practical
  [LGPL compliance checklist](https://ffmpeg.org/legal.html).
- **Dynamically linking FFmpeg:** ensure the application can operate with a
  compatible, user-modified FFmpeg library. FFmpeg recommends dynamic linking
  as its simplest compliance route.
- **Statically linking FFmpeg, including typical Apple and WebAssembly
  builds:** provide the application source or relinkable object form and the
  scripts and information necessary for a recipient to modify FFmpeg and relink
  a working application. See the GNU project's
  [static-versus-dynamic LGPL guidance](https://www.gnu.org/licenses/gpl-faq.en.html#LGPLStaticVsDynamic)
  and [LGPL 2.1 text](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).
- **Enabling GPL components or libraries:** options such as `--enable-gpl`, or
  dependencies such as `libx264` and `libx265`, make the resulting FFmpeg build
  GPL-covered. A combined application may then also have to be distributed under
  a GPL-compatible licence. Such builds must be separate and clearly identified;
  they are not the company-friendly default.
- **Enabling `--enable-nonfree`:** do not redistribute the resulting binary.
  FFmpeg identifies that configuration as unredistributable.
- **Using codecs in a commercial product:** copyright licences do not grant
  patent licences for codecs. Formats such as H.264 may require separate patent
  permission or royalties depending on the product, use, and jurisdiction.

FFmpegKMP does not release native FFmpeg artifacts. Anyone who distributes an
output produced by the build pipeline should provide an exact dependency and
licence manifest rather than labelling the output only "Apache-2.0". The planned
default build profile will exclude GPL and nonfree components.

## Downstream distribution notice

FFmpegKMP does not grant rights to distribute FFmpeg, third-party codecs, or
media processed with them. Anyone who distributes a binary produced by this
project, or an application containing that binary, is responsible for:

- determining the effective FFmpeg licence for the exact build and complying
  with all LGPL or GPL obligations, including corresponding-source, notice,
  relinking, and reverse-engineering requirements where applicable;
- obtaining any required patent licences for enabled codecs, formats, or other
  technologies;
- confirming that the distribution model complies with applicable app-store
  rules and that those rules do not conflict with the applicable open-source
  licences; and
- obtaining the necessary copyright, privacy, publicity, and other permissions
  for all input and output media.

The project makes no representation that a particular build configuration or
use of generated output is lawful in every jurisdiction.

## Authoritative sources

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [How to apply Apache License 2.0](https://www.apache.org/legal/apply-license.html)
- [FFmpeg licence and external-library matrix](https://ffmpeg.org/doxygen/trunk/md_LICENSE.html)
- [FFmpeg legal and LGPL compliance guidance](https://ffmpeg.org/legal.html)
- [GNU LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
- [GNU licensing FAQ](https://www.gnu.org/licenses/gpl-faq.html)

## Legal disclaimer

This documentation provides general open-source compliance information, not
legal advice. Licence, patent, export-control, app-store, and consumer-law
requirements vary by build configuration, product, and jurisdiction. Anyone
distributing FFmpegKMP or FFmpeg in a commercial product should have the exact
release configuration reviewed by qualified legal counsel.

[Back to the project README](../README.md)
