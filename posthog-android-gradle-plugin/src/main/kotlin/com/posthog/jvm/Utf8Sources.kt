package com.posthog.jvm

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Copies source directories with UTF-8 text. posthog-cli reads every source file as UTF-8 and skips
 * a file it cannot decode, so a project whose Java sources use another encoding would upload
 * without them.
 */
internal object Utf8Sources {
    fun isUtf8(encoding: Charset): Boolean = encoding == Charsets.UTF_8 || encoding == Charsets.US_ASCII

    /**
     * Copies each root into `<outputDirectory>/<index of the root>` with the same relative paths,
     * and returns the copies. Java files are decoded from [encoding] and written as UTF-8. Kotlin
     * files are copied unchanged, because the Kotlin compiler reads only UTF-8. Characters and line
     * breaks stay the same, so every line keeps its number.
     */
    fun copy(
        roots: List<File>,
        encoding: Charset,
        outputDirectory: File,
        buildDirectory: File,
    ): List<File> {
        outputDirectory.deleteRecursively()
        val output = outputDirectory.absoluteFile
        val build = buildDirectory.absoluteFile
        return roots.mapIndexed { index, directory ->
            val root = directory.absoluteFile
            val copyRoot = File(output, index.toString()).apply { mkdirs() }
            root.walkTopDown()
                .onEnter { dir ->
                    // A root such as the project directory holds the build directory, and with it
                    // these copies.
                    dir == root || !(dir.name.startsWith(".") || dir == output || (dir == build && !root.startsWith(build)))
                }.filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { file ->
                    val target = File(copyRoot, file.relativeTo(root).path)
                    target.parentFile.mkdirs()
                    if (file.extension == "java") {
                        target.writeBytes(decode(file.readBytes(), encoding).toByteArray(Charsets.UTF_8))
                    } else {
                        file.copyTo(target)
                    }
                }
            copyRoot
        }
    }

    // A byte the encoding cannot map becomes U+FFFD, as javac reports it, instead of dropping the
    // file.
    private fun decode(
        bytes: ByteArray,
        encoding: Charset,
    ): String =
        encoding.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}
