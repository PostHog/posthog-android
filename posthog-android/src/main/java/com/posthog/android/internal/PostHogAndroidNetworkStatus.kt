package com.posthog.android.internal

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.posthog.internal.PostHogNetworkStatus

/**
 * Checks if there's an active network enabled and observes network availability changes
 * @property context the App Context
 */
internal class PostHogAndroidNetworkStatus(private val context: Context) : PostHogNetworkStatus {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun isConnected(): Boolean {
        return context.isConnected()
    }

    override fun register(callback: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        if (!context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
            return
        }

        val connectivityManager = context.connectivityManager() ?: return

        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    callback()
                }
            }

        try {
            connectivityManager.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (ignored: Throwable) {
            // SecurityException or other errors
        }
    }

    override fun unregister() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        val cb = networkCallback ?: return
        networkCallback = null

        val connectivityManager = context.connectivityManager() ?: return

        try {
            connectivityManager.unregisterNetworkCallback(cb)
        } catch (ignored: Throwable) {
            // IllegalArgumentException if not registered
        }
    }
}
