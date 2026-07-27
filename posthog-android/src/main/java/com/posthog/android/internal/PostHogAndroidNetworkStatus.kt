package com.posthog.android.internal

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import com.posthog.internal.PostHogNetworkStatus

/**
 * Checks if there's an active network enabled and observes network availability changes.
 *
 * Network state is maintained from [ConnectivityManager.NetworkCallback] so callers never need to
 * query the connectivity service synchronously while capturing an event.
 *
 * @property context the App Context
 */
internal class PostHogAndroidNetworkStatus(private val context: Context) : PostHogNetworkStatus {
    private val lock = Any()
    private val connectedNetworks = linkedSetOf<Network>()
    private val networkPropertiesByNetwork = linkedMapOf<Network, Map<String, Any>>()
    private val availableCallbacks = mutableListOf<() -> Unit>()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var connected: Boolean? = null

    @Volatile
    private var networkProperties: Map<String, Any> = emptyMap()

    override fun isConnected(): Boolean {
        // Unknown connectivity should not prevent requests, matching the previous behavior when
        // ACCESS_NETWORK_STATE or ConnectivityManager was unavailable.
        return connected ?: true
    }

    override fun register(callback: () -> Unit) {
        synchronized(lock) {
            availableCallbacks.add(callback)
        }
        start()
    }

    internal fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // API 23 can observe matching networks, but not the default network. Leave the
            // snapshot empty rather than report a non-active transport or query synchronously.
            return
        }

        if (!context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
            return
        }

        val manager = context.connectivityManager() ?: return

        synchronized(lock) {
            if (networkCallback != null) {
                return
            }

            val callback = createNetworkCallback()
            try {
                manager.registerDefaultNetworkCallback(callback)
                connectivityManager = manager
                networkCallback = callback
            } catch (ignored: Throwable) {
                // SecurityException or other platform errors. Connectivity remains unknown so the
                // SDK attempts requests rather than incorrectly treating the device as offline.
            }
        }
    }

    internal fun getNetworkProperties(): Map<String, Any> {
        return networkProperties
    }

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val callbacks =
                    synchronized(lock) {
                        connectedNetworks.add(network)
                        connected = true
                        availableCallbacks.toList()
                    }
                callbacks.forEach { it() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val properties =
                    mapOf(
                        "\$network_wifi" to capabilities.hasTransport(TRANSPORT_WIFI),
                        "\$network_bluetooth" to capabilities.hasTransport(TRANSPORT_BLUETOOTH),
                        "\$network_cellular" to capabilities.hasTransport(TRANSPORT_CELLULAR),
                    )
                synchronized(lock) {
                    connectedNetworks.add(network)
                    connected = true
                    networkPropertiesByNetwork[network] = properties
                    networkProperties = properties
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    connectedNetworks.remove(network)
                    networkPropertiesByNetwork.remove(network)
                    connected = connectedNetworks.isNotEmpty()
                    networkProperties = networkPropertiesByNetwork.values.lastOrNull() ?: emptyMap()
                }
            }
        }
    }

    override fun unregister() {
        val registration =
            synchronized(lock) {
                val currentManager = connectivityManager
                val currentCallback = networkCallback
                connectivityManager = null
                networkCallback = null
                availableCallbacks.clear()
                connectedNetworks.clear()
                networkPropertiesByNetwork.clear()
                connected = null
                networkProperties = emptyMap()
                if (currentManager != null && currentCallback != null) {
                    currentManager to currentCallback
                } else {
                    null
                }
            } ?: return

        try {
            registration.first.unregisterNetworkCallback(registration.second)
        } catch (ignored: Throwable) {
            // IllegalArgumentException if the callback was not registered.
        }
    }
}
