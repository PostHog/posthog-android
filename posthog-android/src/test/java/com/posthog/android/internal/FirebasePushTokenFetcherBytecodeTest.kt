package com.posthog.android.internal

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FirebasePushTokenFetcherBytecodeTest {
    @Test
    fun `SDK bytecode has no Firebase or Play Services type references`() {
        val location = File(FirebasePushTokenFetcher::class.java.protectionDomain!!.codeSource.location.toURI())
        val classes =
            if (location.isDirectory) {
                location.walkTopDown().filter { it.isFile && it.extension == "class" }
                    .map { it.relativeTo(location).path to it.readBytes() }.toList()
            } else {
                ZipFile(location).use { jar ->
                    jar.entries().asSequence().filter { it.name.endsWith(".class") }
                        .map { it.name to jar.getInputStream(it).use { stream -> stream.readBytes() } }.toList()
                }
            }

        assertTrue(classes.isNotEmpty())
        for ((name, bytes) in classes) {
            val bytecode = bytes.toString(Charsets.ISO_8859_1)
            for (typePrefix in listOf("com/google/firebase/", "com/google/android/gms/")) {
                assertFalse(bytecode.contains(typePrefix), "$name references $typePrefix")
            }
        }
    }
}
