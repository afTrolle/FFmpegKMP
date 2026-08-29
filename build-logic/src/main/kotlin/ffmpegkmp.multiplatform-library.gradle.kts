import io.github.aftrolle.ffmpegkmp.buildlogic.configureLibraryAppleTargets

pluginManager.apply("ffmpegkmp.multiplatform-library-base")

configureLibraryAppleTargets(includeTvosAndWatchos = true)

tasks.matching { it.name == "tvosSimulatorArm64Test" }.configureEach {
    val runTvosSimulatorTests = providers
        .gradleProperty("ffmpegkmp.runTvosSimulatorTests")
        .map(String::toBoolean)
        .orElse(false)

    // The tvOS SDK can be installed without a runnable tvOS simulator runtime.
    // Keep compiling and linking its test executable in allTests, but make
    // simulator execution an explicit opt-in for suitably provisioned hosts.
    onlyIf("-Pffmpegkmp.runTvosSimulatorTests=true was supplied") {
        runTvosSimulatorTests.get()
    }
}
