package com.posthog

import java.io.InputStream
import java.io.OutputStream

public interface PostHogEncryption {
    public fun decrypt(inputStream: InputStream): InputStream

    public fun encrypt(outputStream: OutputStream): OutputStream
}