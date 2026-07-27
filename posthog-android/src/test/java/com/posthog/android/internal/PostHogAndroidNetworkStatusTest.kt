package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.mockPermission
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidNetworkStatusTest {
    private val context = mock<Context>()

    private fun getSut(): PostHogAndroidNetworkStatus {
        return PostHogAndroidNetworkStatus(context)
    }

    @Test
    fun `returns connected if no connectivity manager`() {
        assertTrue(getSut().isConnected())
    }

    @Test
    fun `returns connected and does not register if no permission`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context, PackageManager.PERMISSION_DENIED)

        sut.register {}

        assertTrue(sut.isConnected())
        verifyNoInteractions(connectivityManager)
    }

    @Test
    @Config(sdk = [34])
    fun `updates connectivity and properties from callback`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        var availableCalls = 0

        sut.register { availableCalls++ }
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())

        val network = mock<Network>()
        val capabilities = mock<NetworkCapabilities>()
        whenever(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        whenever(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)).thenReturn(false)
        whenever(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(false)

        callbackCaptor.firstValue.onAvailable(network)
        callbackCaptor.firstValue.onCapabilitiesChanged(network, capabilities)

        assertTrue(sut.isConnected())
        assertEquals(1, availableCalls)
        assertEquals(
            mapOf(
                "\$network_wifi" to true,
                "\$network_bluetooth" to false,
                "\$network_cellular" to false,
            ),
            sut.getNetworkProperties(),
        )

        callbackCaptor.firstValue.onLost(network)

        assertFalse(sut.isConnected())
        assertTrue(sut.getNetworkProperties().isEmpty())
    }

    @Test
    @Config(sdk = [34])
    fun `registers one system callback and notifies every listener`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        var firstCalls = 0
        var secondCalls = 0

        sut.register { firstCalls++ }
        sut.register { secondCalls++ }

        verify(connectivityManager, times(1)).registerDefaultNetworkCallback(callbackCaptor.capture())

        callbackCaptor.firstValue.onAvailable(mock())

        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    @Config(sdk = [23])
    fun `uses network request callback on API 23`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)

        sut.register {}

        verify(connectivityManager).registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
    }

    @Test
    @Config(sdk = [34])
    fun `unregisters callback and clears snapshot`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        sut.register {}
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())

        val network = mock<Network>()
        val capabilities = mock<NetworkCapabilities>()
        callbackCaptor.firstValue.onCapabilitiesChanged(network, capabilities)

        sut.unregister()

        verify(connectivityManager).unregisterNetworkCallback(callbackCaptor.firstValue)
        assertTrue(sut.isConnected())
        assertTrue(sut.getNetworkProperties().isEmpty())
    }
}
