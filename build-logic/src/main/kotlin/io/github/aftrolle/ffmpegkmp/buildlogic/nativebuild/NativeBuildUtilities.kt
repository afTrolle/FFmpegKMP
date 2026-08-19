package io.github.aftrolle.ffmpegkmp.buildlogic.nativebuild

import java.io.File
import java.security.MessageDigest

internal val ffmpegLibraries = listOf(
    "avcodec",
    "avdevice",
    "avfilter",
    "avformat",
    "avutil",
    "swresample",
    "swscale",
)

internal data class LicenseAssessment(
    val mode: String,
    val suffix: String,
    val redistributable: Boolean,
    val warnings: List<String>,
)

internal fun assessLicense(arguments: Iterable<String>): LicenseAssessment {
    val normalized = arguments.map(String::lowercase)
    val nonfree = normalized.any { it == "--enable-nonfree" }
    val gplLibraries = listOf(
        "libx264", "libx265", "libxvid", "libvidstab", "librubberband",
        "frei0r", "libcdio", "avisynth", "libdavs2", "libxavs", "libxavs2",
    )
    val gpl = normalized.any { it == "--enable-gpl" } ||
        normalized.any { argument -> gplLibraries.any { argument == "--enable-$it" } }
    val version3 = normalized.any { it == "--enable-version3" }
    val warnings = buildList {
        if (gpl) add("GPL components appear to be enabled; the resulting FFmpeg binary is not LGPL-only.")
        if (version3) add("Version 3 licensing appears to be enabled; review the effective LGPLv3/GPLv3 terms.")
        if (nonfree) add("Nonfree components appear to be enabled; FFmpeg marks this configuration unredistributable.")
        if (normalized.any { it.startsWith("--enable-lib") }) {
            add("External libraries are enabled; their licences and corresponding source are the builder's responsibility.")
        }
    }
    return when {
        nonfree -> LicenseAssessment("nonfree", "-nonfree", false, warnings)
        gpl -> LicenseAssessment(if (version3) "gpl-3+" else "gpl-2+", "-gpl", true, warnings)
        version3 -> LicenseAssessment("lgpl-3+", "-lgpl3", true, warnings)
        else -> LicenseAssessment("lgpl-2.1+", "", true, warnings)
    }
}

internal fun String.jsonEscaped(): String = buildString {
    this@jsonEscaped.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun commandOutput(vararg command: String, workingDirectory: File? = null): String {
    val process = ProcessBuilder(*command)
        .directory(workingDirectory)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0) {
        "Command failed (${command.joinToString(" ")}): $output"
    }
    return output
}

internal fun profileTaskSuffix(profile: String): String =
    profile.split('-', '_').joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

internal fun targetTaskSuffix(target: String): String = profileTaskSuffix(target)

/**
 * Finds a complete Emscripten tool directory without launching a subprocess.
 * Android Studio does not inherit the user's interactive shell PATH on macOS,
 * so include the standard emsdk and Homebrew locations as fallbacks.
 */
fun discoverEmscriptenDirectory(
    searchPath: String,
    osName: String = System.getProperty("os.name"),
    userHome: String = System.getProperty("user.home"),
): String {
    val windows = osName.contains("windows", ignoreCase = true)
    val suffixes = if (windows) listOf(".bat", ".cmd", ".exe", "") else listOf("")
    val requiredTools = listOf("emcc", "em++", "emar", "emnm", "emranlib", "emconfigure", "emmake")
    val directories = buildList {
        addAll(searchPath.split(File.pathSeparatorChar).filter(String::isNotBlank).map(::File))
        add(File(userHome, ".emsdk/upstream/emscripten"))
        add(File(userHome, "emsdk/upstream/emscripten"))
        if (osName.contains("mac", ignoreCase = true)) {
            add(File("/opt/homebrew/bin"))
            add(File("/usr/local/bin"))
        }
    }.distinctBy { it.absoluteFile.normalize().path }

    return directories.firstOrNull { directory ->
        requiredTools.all { tool -> suffixes.any { suffix -> directory.resolve(tool + suffix).isFile } }
    }?.absolutePath.orEmpty()
}
