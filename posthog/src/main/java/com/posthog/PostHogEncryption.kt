package com.posthog

import java.io.InputStream
import java.io.OutputStream

/**
 * Interface for encrypt and decrypt events, by default there's no encrypt since Android is sandboxed
 * https://source.android.com/docs/security/app-sandbox
 */
public interface PostHogEncryption {
    /**
     * Decrypts data read from [inputStream].
     * @param inputStream the InputStream that has to be decrypted
     * @return an InputStream that yields decrypted event data
     */
    public fun decrypt(inputStream: InputStream): InputStream

    /**
     * Encrypts data written to [outputStream].
     * @param outputStream the OutputStream that has to be encrypted
     * @return an OutputStream that writes encrypted event data
     */
    public fun encrypt(outputStream: OutputStream): OutputStream
}
