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
import android.os.SystemClock
import com.posthog.internal.PostHogNetworkStatus
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/**
 * Checks if there's an active network enabled and observes network availability changes.
 *
 * Network state is maintained from [ConnectivityManager.NetworkCallback] so event capture only
 * reads an in-memory snapshot. API 23 and callback registration failures refresh that snapshot
 * asynchronously with synchronous platform queries.
 *
 * @property context the App Context
 */
internal class PostHogAndroidNetworkStatus(
    private val context: Context,
    private val backgroundExecutor: Executor =
        Executor { command ->
            thread(start = true, isDaemon = true, name = "PostHogNetworkStatusThread") {
                command.run()
            }
        },
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) : PostHogNetworkStatus {
    private data class Snapshot(
        val network: Network?,
        val connected: Boolean,
        val properties: Map<String, Any>,
    )

    private data class Refresh(
        val manager: ConnectivityManager,
        val generation: Int,
        val networkGeneration: Int,
    )

    private val lock = Any()
    private val availableCallbacks = mutableListOf<() -> Unit>()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var started = false
    private var usesPollingFallback = false
    private var refreshInFlight = false
    private var lastRefreshElapsedMs: Long? = null
    private var generation = 0
    private var networkGeneration = 0

    @Volatile
    private var connected: Boolean? = null

    @Volatile
    private var networkProperties: Map<String, Any> = emptyMap()

    override fun isConnected(): Boolean {
        connected?.let { return it }

        if (!context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
            return true
        }
        val manager = context.connectivityManager() ?: return true
        val currentNetworkGeneration = synchronized(lock) { networkGeneration }
        val snapshot = querySnapshot(manager) ?: return true
        synchronized(lock) {
            if (currentNetworkGeneration == networkGeneration &&
                (usesPollingFallback || activeNetwork == null || snapshot.network == activeNetwork)
            ) {
                applySnapshotLocked(snapshot)
                lastRefreshElapsedMs = elapsedRealtimeMs()
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
                } catch (ignored: Throwable) {
                    // SecurityException, callback limit, or another platform error.
                    usesPollingFallback = true
                }
            } else {
                // API 23 cannot observe the default network, so refresh the snapshot off-thread.
                usesPollingFallback = true
            }
        }

        scheduleSnapshotRefresh(force = true)
    }

    internal fun getNetworkProperties(): Map<String, Any> {
        val shouldRefresh =
            synchronized(lock) {
                usesPollingFallback || connected == null || (connected == true && networkProperties.isEmpty())
            }
        if (shouldRefresh) {
            scheduleSnapshotRefresh()
        }
        return networkProperties
    }

    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val (networkChanged, callbacks) =
                    synchronized(lock) {
                        val changed = activeNetwork != network
                        activeNetwork = network
                        connected = true
                        if (changed) {
                            networkGeneration++
                            networkProperties = emptyMap()
                            lastRefreshElapsedMs = null
                        }
                        changed to availableCallbacks.toList()
                    }
                callbacks.forEach { it() }
                if (networkChanged) {
                    scheduleSnapshotRefresh(force = true)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                synchronized(lock) {
                    if (network == activeNetwork) {
                        connected = true
                        networkProperties = capabilities.toNetworkProperties()
                    }
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) {
                    if (network == activeNetwork) {
                        networkGeneration++
                        activeNetwork = null
                        connected = false
                        networkProperties = emptyMap()
                    }
                }
            }
        }
    }

    private fun scheduleSnapshotRefresh(force: Boolean = false) {
        val refresh: Refresh =
            synchronized(lock) {
                val manager = connectivityManager ?: return
                if (!started || refreshInFlight) {
                    return
                }
                val now = elapsedRealtimeMs()
                val lastRefresh = lastRefreshElapsedMs
                if (!force && lastRefresh != null && now - lastRefresh < REFRESH_INTERVAL_MS) {
                    return
                }
                refreshInFlight = true
                Refresh(manager, generation, networkGeneration)
            }

        try {
            backgroundExecutor.execute {
                val snapshot = querySnapshot(refresh.manager)
                synchronized(lock) {
                    if (refresh.generation == generation) {
                        val canApply =
                            refresh.networkGeneration == networkGeneration &&
                                snapshot != null &&
                                (usesPollingFallback || activeNetwork == null || snapshot.network == activeNetwork)
                        if (canApply) {
                            applySnapshotLocked(snapshot)
                        }
                        lastRefreshElapsedMs = elapsedRealtimeMs()
                        refreshInFlight = false
                    }
                }
            }
        } catch (ignored: Throwable) {
            synchronized(lock) {
                if (refresh.generation == generation) {
                    lastRefreshElapsedMs = elapsedRealtimeMs()
                    refreshInFlight = false
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
                networkGeneration++
                connectivityManager = null
                networkCallback = null
                activeNetwork = null
                availableCallbacks.clear()
                started = false
                usesPollingFallback = false
                refreshInFlight = false
                lastRefreshElapsedMs = null
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

    private companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
    }
}
