package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.mockNetworkInfo
import com.posthog.android.mockPermission
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidNetworkStatusTest {
    private val context = mock<Context>()

    private fun getSut(): PostHogAndroidNetworkStatus {
        return PostHogAndroidNetworkStatus(context)
    }

    @Test
    fun `returns connected if no activity manager`() {
        val sut = getSut()

        assertTrue(sut.isConnected())
    }

    @Test
    fun `returns connected if no permission`() {
        val sut = getSut()
        mockPermission(context, PackageManager.PERMISSION_DENIED)

        assertTrue(sut.isConnected())
    }

    @Test
    fun `returns not connected if no active connection`() {
        val sut = getSut()
        mockPermission(context)

        assertFalse(sut.isConnected())
    }

    @Test
    fun `returns connected if active connection`() {
        val sut = getSut()
        val cm = mockPermission(context)
        mockNetworkInfo(cm)

        assertTrue(sut.isConnected())
    }

    @Test
    fun `returns not connected if active connection but not connected`() {
        val sut = getSut()
        val cm = mockPermission(context)
        mockNetworkInfo(cm, isConnected = false)

        assertFalse(sut.isConnected())
    }
}
