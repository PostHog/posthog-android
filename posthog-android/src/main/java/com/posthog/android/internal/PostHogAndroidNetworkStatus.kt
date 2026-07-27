package com.posthog.android.internal

import android.Manifest
import android.annotation.SuppressLint
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
 * Network state is maintained from [ConnectivityManager.NetworkCallback] so event capture only
 * reads an in-memory snapshot. API 23 and callback registration failures refresh that snapshot
 * when [isConnected] runs on an SDK worker.
 *
 * @property context the App Context
 */
internal class PostHogAndroidNetworkStatus(private val context: Context) : PostHogNetworkStatus {
    private data class Snapshot(
        val network: Network?,
        val connected: Boolean,
        val properties: Map<String, Any>,
    )

    private val lock = Any()
    private val availableCallbacks = mutableListOf<() -> Unit>()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var started = false
    private var usesSynchronousFallback = true
    private var generation = 0

    @Volatile
    private var connected: Boolean? = null

    @Volatile
    private var networkProperties: Map<String, Any> = emptyMap()

    override fun isConnected(): Boolean {
        if (!context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
            return true
        }

        val queryGeneration = synchronized(lock) { generation }
        val shouldQuery = synchronized(lock) { !started || usesSynchronousFallback }
        if (!shouldQuery) {
            return connected ?: true
        }

        val manager = synchronized(lock) { connectivityManager } ?: context.connectivityManager() ?: return true
        val snapshot = querySnapshot(manager) ?: return true
        synchronized(lock) {
            if (queryGeneration == generation && (!started || usesSynchronousFallback)) {
                applySnapshotLocked(snapshot)
            }
        }
        return snapshot.connected
    }

    override fun register(callback: () -> Unit) {
        synchronized(lock) {
            availableCallbacks.add(callback)
        }
        start()
    }

    internal fun start() {
        if (!context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
            return
        }

        val manager = context.connectivityManager() ?: return
        synchronized(lock) {
            if (started) {
                return
            }
            started = true
            generation++
            connectivityManager = manager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val callback = createNetworkCallback()
                try {
                    manager.registerDefaultNetworkCallback(callback)
                    networkCallback = callback
                    usesSynchronousFallback = false
                } catch (ignored: Throwable) {
                    // SecurityException, callback limit, or another platform error. Connectivity
                    // will be refreshed by isConnected() on an SDK worker instead.
                    usesSynchronousFallback = true
                }
            } else {
                // API 23 cannot observe the default network.
                usesSynchronousFallback = true
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
                        if (networkCallback !== this) {
                            return
                        }
                        if (activeNetwork != network) {
                            activeNetwork = network
                            networkProperties = emptyMap()
                        }
                        connected = true
                        availableCallbacks.toList()
                    }
                callbacks.forEach { it() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                synchronized(lock) {
                    if (networkCallback !== this) {
                        return
                    }
                    if (network == activeNetwork) {
                        connected = true
                        networkProperties = capabilities.toNetworkProperties()
                    }
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    if (networkCallback !== this) {
                        return
                    }
                    if (network == activeNetwork) {
                        activeNetwork = null
                        connected = false
                        networkProperties = emptyMap()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun querySnapshot(manager: ConnectivityManager): Snapshot? {
        return try {
            val network = manager.activeNetwork
            if (network == null) {
                Snapshot(null, connected = false, properties = emptyMap())
            } else {
                val capabilities = manager.getNetworkCapabilities(network)
                Snapshot(
                    network,
                    connected = true,
                    properties = capabilities?.toNetworkProperties() ?: emptyMap(),
                )
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun applySnapshotLocked(snapshot: Snapshot) {
        activeNetwork = snapshot.network
        connected = snapshot.connected
        networkProperties = snapshot.properties
    }

    private fun NetworkCapabilities.toNetworkProperties(): Map<String, Any> {
        return mapOf(
            "\$network_wifi" to hasTransport(TRANSPORT_WIFI),
            "\$network_bluetooth" to hasTransport(TRANSPORT_BLUETOOTH),
            "\$network_cellular" to hasTransport(TRANSPORT_CELLULAR),
        )
    }

    override fun unregister() {
        val registration =
            synchronized(lock) {
                val currentManager = connectivityManager
                val currentCallback = networkCallback
                generation++
                connectivityManager = null
                networkCallback = null
                activeNetwork = null
                availableCallbacks.clear()
                started = false
                usesSynchronousFallback = true
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
