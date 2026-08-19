package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class NativeBuildModelTest {
    @Test
    fun `later layers override scalars and add typed components`() {
        val objects = ProjectBuilder.builder().build().objects
        val common = objects.newInstance(FfmpegBuildOptions::class.java).apply {
            network.set(false)
            devices.set(false)
            decoders.add("aac")
            extraConfigureArgs.add("--common")
        }
        val profile = objects.newInstance(FfmpegBuildOptions::class.java).apply {
            network.set(true)
            decoders.add("h264")
            extraConfigureArgs.add("--profile")
        }
        val target = objects.newInstance(FfmpegBuildOptions::class.java).apply {
            devices.set(true)
            extraConfigureArgs.add("--target")
        }

        val resolved = MutableResolvedOptions().apply {
            overlay(common)
            overlay(profile)
            overlay(target)
        }

        assertTrue(resolved.network)
        assertTrue(resolved.devices)
        assertEquals(setOf("aac", "h264"), resolved.decoders)
        assertEquals(listOf("--common", "--profile", "--target"), resolved.extraConfigureArgs)
    }

    @Test
    fun `licence classifier recognizes safe gpl and nonfree configurations`() {
        val safe = assessLicense(emptyList())
        assertEquals("lgpl-2.1+", safe.mode)
        assertEquals("", safe.suffix)
        assertTrue(safe.redistributable)

        val gpl = assessLicense(listOf("--enable-libx264"))
        assertEquals("-gpl", gpl.suffix)
        assertTrue(gpl.warnings.isNotEmpty())

        val nonfree = assessLicense(listOf("--enable-nonfree", "--enable-libfdk-aac"))
        assertEquals("-nonfree", nonfree.suffix)
        assertFalse(nonfree.redistributable)
        assertTrue(nonfree.warnings.any { it.contains("External libraries") })
    }

    @Test
    fun `task suffixes are stable for profile and target names`() {
        assertEquals("Standard", profileTaskSuffix("standard"))
        assertEquals("Arm64V8a", targetTaskSuffix("arm64-v8a"))
        assertEquals("MyProfile", profileTaskSuffix("my_profile"))
    }

    @Test
    fun `shared profiles are inherited by the wasm platform`() {
        val objects = ProjectBuilder.builder().build().objects
        val parent = objects.newInstance(FfmpegNativeBuildExtension::class.java).apply {
            profiles.create("standard")
        }
        val child = objects.newInstance(FfmpegNativeBuildExtension::class.java)

        NativeBuildRegistration.inherit(parent, child)

        assertNotNull(child.wasm.profiles.findByName("standard"))
    }

    @Test
    fun `emscripten discovery returns a complete tool directory from PATH`() {
        val tools = createTempDirectory("emscripten-tools").toFile()
        listOf("emcc", "em++", "emar", "emnm", "emranlib", "emconfigure", "emmake")
            .forEach { tools.resolve(it).writeText("") }

        assertEquals(
            tools.absolutePath,
            discoverEmscriptenDirectory(tools.absolutePath, osName = "Linux", userHome = tools.resolve("home").path),
        )
    }
}
