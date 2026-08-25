# iOS Studio launcher

Open `FFmpegKMPStudio.xcodeproj`, choose the `FFmpegKMPStudio` scheme and an iOS
15+ simulator, then run. The Xcode build phase invokes
`:samples:ios:prepareFFmpegKmpRuntime` and
`:samples:studio:embedAndSignAppleFrameworkForXcode`. The first task builds and
stages the eight target-specific FFmpeg/bridge archives; the Xcode project links
them only into the local sample application. They are not embedded in the
published Kotlin/Native KLIB or any Maven artifact.

To prepare both iPhoneOS and Apple-silicon simulator runtimes without opening
Xcode, run:

```shell
./gradlew :samples:ios:prepareFFmpegKmpRuntime
```

See [`../../docs/consuming.md`](../../docs/consuming.md) for linking the
generated Apple XCFrameworks in another application.
