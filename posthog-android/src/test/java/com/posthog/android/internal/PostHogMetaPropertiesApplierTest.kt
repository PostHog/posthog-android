package com.posthog.android.internal

import android.content.Context
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
internal class PostHogMetaPropertiesApplierTest {
    private val context = mock<Context>()
    private val assets = mock<AssetManager>()
    private val config = PostHogAndroidConfig(API_KEY)

    private val sut = PostHogMetaPropertiesApplier()

    private fun mockMetaProperties(content: String) {
        whenever(context.assets).thenReturn(assets)
        whenever(assets.open(any())).thenReturn(ByteArrayInputStream(content.toByteArray()))
    }

    private fun mockMissingMetaProperties() {
        whenever(context.assets).thenReturn(assets)
        whenever(assets.open(any())).thenThrow(FileNotFoundException())
    }

    @Test
    fun `preserves manually set releaseIdentifier`() {
        config.releaseIdentifier = "manual-id"
        mockMetaProperties("io.posthog.proguard.mapid=meta-id")

        sut.applyToConfig(context, config, FALLBACK)

        assertEquals("manual-id", config.releaseIdentifier)
        verify(assets, never()).open(any())
    }

    @Test
    fun `uses meta properties map id when releaseIdentifier not set`() {
        mockMetaProperties("io.posthog.proguard.mapid=meta-id")

        sut.applyToConfig(context, config, FALLBACK)

        assertEquals("meta-id", config.releaseIdentifier)
    }

    @Test
    fun `uses fallback when meta properties file is missing`() {
        mockMissingMetaProperties()

        sut.applyToConfig(context, config, FALLBACK)

        assertEquals(FALLBACK, config.releaseIdentifier)
    }

    @Test
    fun `uses fallback when meta properties do not contain map id`() {
        mockMetaProperties("some.other.property=value")

        sut.applyToConfig(context, config, FALLBACK)

        assertEquals(FALLBACK, config.releaseIdentifier)
    }

    @Test
    fun `uses fallback when reading meta properties throws`() {
        whenever(context.assets).thenReturn(assets)
        whenever(assets.open(any())).thenThrow(RuntimeException("boom"))

        sut.applyToConfig(context, config, FALLBACK)

        assertEquals(FALLBACK, config.releaseIdentifier)
    }

    companion object {
        private const val FALLBACK = "com.package@1.0.0+1"
    }
}
