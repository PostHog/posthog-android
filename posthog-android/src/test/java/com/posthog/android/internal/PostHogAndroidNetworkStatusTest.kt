package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidNetworkStatusTest {
    private val context = mock<Context>()
    private var elapsedRealtimeMs = 0L

    private fun getSut(backgroundExecutor: Executor = Executor { it.run() }): PostHogAndroidNetworkStatus {
        return PostHogAndroidNetworkStatus(
            context,
            backgroundExecutor = backgroundExecutor,
            elapsedRealtimeMs = { elapsedRealtimeMs },
        )
    }

    private fun mockCapabilities(
        wifi: Boolean = false,
        bluetooth: Boolean = false,
        cellular: Boolean = false,
    ): NetworkCapabilities {
        return mock<NetworkCapabilities>().also {
            whenever(it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(wifi)
            whenever(it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)).thenReturn(bluetooth)
            whenever(it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(cellular)
        }
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
    fun `isConnected synchronously resolves unknown state`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)

        assertFalse(sut.isConnected())

        verify(connectivityManager).activeNetwork
    }

    @Test
    @Config(sdk = [34])
    fun `primes properties off thread before callback delivery`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val network = mock<Network>()
        val capabilities = mockCapabilities(wifi = true)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(capabilities)

        sut.register {}

        assertEquals(true, sut.getNetworkProperties()["\$network_wifi"])
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
        val capabilities = mockCapabilities(wifi = true)

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
    fun `does not restore a previous default network after handover`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        sut.register {}
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())

        val wifiNetwork = mock<Network>()
        val cellularNetwork = mock<Network>()
        callbackCaptor.firstValue.onAvailable(wifiNetwork)
        callbackCaptor.firstValue.onCapabilitiesChanged(wifiNetwork, mockCapabilities(wifi = true))
        callbackCaptor.firstValue.onAvailable(cellularNetwork)
        callbackCaptor.firstValue.onCapabilitiesChanged(cellularNetwork, mockCapabilities(cellular = true))

        // A late callback from the previously-default network must not replace the snapshot.
        callbackCaptor.firstValue.onCapabilitiesChanged(wifiNetwork, mockCapabilities(wifi = true))
        assertEquals(true, sut.getNetworkProperties()["\$network_cellular"])

        callbackCaptor.firstValue.onLost(cellularNetwork)

        assertFalse(sut.isConnected())
        assertTrue(sut.getNetworkProperties().isEmpty())
    }

    @Test
    @Config(sdk = [34])
    fun `stale background refresh does not restore a lost network`() {
        val pendingTasks = mutableListOf<Runnable>()
        val sut = getSut(Executor { pendingTasks.add(it) })
        val connectivityManager = mockPermission(context)
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        val network = mock<Network>()
        val capabilities = mockCapabilities(wifi = true)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(capabilities)

        sut.register {}
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())
        callbackCaptor.firstValue.onAvailable(network)
        callbackCaptor.firstValue.onLost(network)

        pendingTasks.single().run()

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
    fun `uses background snapshot fallback on API 23`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val network = mock<Network>()
        val capabilities = mockCapabilities(bluetooth = true)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(capabilities)

        sut.register {}

        assertEquals(true, sut.getNetworkProperties()["\$network_bluetooth"])
    }

    @Test
    @Config(sdk = [34])
    fun `uses background snapshot fallback when callback registration fails`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val network = mock<Network>()
        val capabilities = mockCapabilities(cellular = true)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network)).thenReturn(capabilities)
        whenever(connectivityManager.registerDefaultNetworkCallback(any())).thenThrow(IllegalStateException("limit"))

        sut.register {}

        assertEquals(true, sut.getNetworkProperties()["\$network_cellular"])
    }

    @Test
    @Config(sdk = [34])
    fun `throttles failed background refreshes`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        whenever(connectivityManager.activeNetwork).thenThrow(IllegalStateException("service unavailable"))

        sut.register {}
        sut.getNetworkProperties()
        sut.getNetworkProperties()

        verify(connectivityManager, times(1)).activeNetwork
    }

    @Test
    @Config(sdk = [34])
    fun `polling fallback refreshes stale properties`() {
        val sut = getSut()
        val connectivityManager = mockPermission(context)
        val wifiNetwork = mock<Network>()
        val cellularNetwork = mock<Network>()
        val wifiCapabilities = mockCapabilities(wifi = true)
        val cellularCapabilities = mockCapabilities(cellular = true)
        whenever(connectivityManager.registerDefaultNetworkCallback(any())).thenThrow(IllegalStateException("limit"))
        whenever(connectivityManager.activeNetwork).thenReturn(wifiNetwork)
        whenever(connectivityManager.getNetworkCapabilities(wifiNetwork)).thenReturn(wifiCapabilities)
        sut.register {}
        assertEquals(true, sut.getNetworkProperties()["\$network_wifi"])

        whenever(connectivityManager.activeNetwork).thenReturn(cellularNetwork)
        whenever(connectivityManager.getNetworkCapabilities(cellularNetwork)).thenReturn(cellularCapabilities)
        elapsedRealtimeMs = 60_000L

        assertEquals(true, sut.getNetworkProperties()["\$network_cellular"])
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
        callbackCaptor.firstValue.onAvailable(network)
        callbackCaptor.firstValue.onCapabilitiesChanged(network, mockCapabilities(wifi = true))

        sut.unregister()

        verify(connectivityManager).unregisterNetworkCallback(callbackCaptor.firstValue)
        assertFalse(sut.isConnected())
        assertTrue(sut.getNetworkProperties().isEmpty())
    }
}
