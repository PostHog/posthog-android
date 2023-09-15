package com.posthog

import java.io.InputStream
import java.io.OutputStream

public abstract class PostHogEncryption {
    public abstract fun decrypt(inputStream: InputStream): InputStream

    public abstract fun encrypt(outputStream: OutputStream): OutputStream

    internal class PostHogEncryptionNone : PostHogEncryption() {
        override fun decrypt(inputStream: InputStream): InputStream {
            return inputStream
        }

        override fun encrypt(outputStream: OutputStream): OutputStream {
            return outputStream
        }
    }
}
