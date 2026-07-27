package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
internal class DeviceUtilsTest {
    private fun contextWithConfiguration(configuration: Configuration): Context {
        val context = mock<Context>()
        val resources = mock<Resources>()
        val packageManager = mock<PackageManager>()
        whenever(context.resources).thenReturn(resources)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(resources.configuration).thenReturn(configuration)
        whenever(resources.displayMetrics).thenReturn(DisplayMetrics())
        whenever(packageManager.hasSystemFeature("amazon.hardware.fire_tv")).thenReturn(false)
        return context
    }

    @Test
    fun `returns TV from the local resource configuration`() {
        val configuration = Configuration()
        configuration.uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES
        val context = contextWithConfiguration(configuration)

        assertEquals("TV", getDeviceType(context))
        verify(context, never()).getSystemService(Context.UI_MODE_SERVICE)
    }

    @Test
    fun `keeps tablet classification for non television UI mode`() {
        val configuration = Configuration()
        configuration.uiMode = Configuration.UI_MODE_TYPE_NORMAL
        configuration.smallestScreenWidthDp = 600

        assertEquals("Tablet", getDeviceType(contextWithConfiguration(configuration)))
    }

    @Test
    fun `keeps mobile classification for non television UI mode`() {
        val configuration = Configuration()
        configuration.uiMode = Configuration.UI_MODE_TYPE_NORMAL
        configuration.smallestScreenWidthDp = 599

        assertEquals("Mobile", getDeviceType(contextWithConfiguration(configuration)))
    }
}
