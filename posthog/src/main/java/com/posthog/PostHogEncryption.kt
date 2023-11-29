package com.posthog

import java.io.InputStream
import java.io.OutputStream

/**
 * Interface for encrypt and decrypt events, by default there's no encrypt since Android is sandboxed
 * https://source.android.com/docs/security/app-sandbox
 */
public interface PostHogEncryption {
    /**
     * The decrypt method
     * @param inputStream the InputStream that has to be decrypted
     */
    public fun decrypt(inputStream: InputStream): InputStream

    /**
     * The decrypt method
     * @param outputStream the OutputStream that has to be encrypted
     */
    public fun encrypt(outputStream: OutputStream): OutputStream
}
