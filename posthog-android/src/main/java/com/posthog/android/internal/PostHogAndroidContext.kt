package com.posthog.android.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogContext
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the static and dynamic context
 * For example, screen's metrics, app's name and version, device details, connectivity status
 * @property context the App Context
 * @property config the Config
 */
internal class PostHogAndroidContext(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) : PostHogContext {

    private val cacheStaticContext by lazy {
        val staticContext = mutableMapOf<String, Any>()
        val displayMetrics = context.displayMetrics()
        staticContext["\$screen_density"] = displayMetrics.density
        staticContext["\$screen_height"] = displayMetrics.heightPixels
        staticContext["\$screen_width"] = displayMetrics.widthPixels

        getPackageInfo(context, config)?.let {
            it.versionName?.let { name ->
                staticContext["\$app_version"] = name
            }
            staticContext["\$app_namespace"] = it.packageName
            staticContext["\$app_build"] = it.versionCodeCompat()
        }
        staticContext["\$app_name"] = context.applicationInfo.loadLabel(context.packageManager)

        staticContext["\$device_manufacturer"] = Build.MANUFACTURER
        staticContext["\$device_model"] = Build.MODEL
        staticContext["\$device_name"] = Build.DEVICE
        // Check https://github.com/PostHog/posthog-flutter/issues/49 and change if needed
        staticContext["\$device_type"] = "mobile"
        staticContext["\$os_name"] = "Android"
        staticContext["\$os_version"] = Build.VERSION.RELEASE

        staticContext["\$lib"] = config.sdkName
        staticContext["\$lib_version"] = config.sdkVersion

        staticContext
    }

    override fun getStaticContext(): Map<String, Any> {
        return cacheStaticContext
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun getDynamicContext(): Map<String, Any> {
        val dynamicContext = mutableMapOf<String, Any>()
        dynamicContext["\$locale"] = "${Locale.getDefault().language}-${Locale.getDefault().country}"
        System.getProperty("http.agent")?.let {
            dynamicContext["\$user_agent"] = it
        }
        dynamicContext["\$timezone"] = TimeZone.getDefault().id

        context.connectivityManager()?.let { connectivityManager ->
            if (context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.let {
                    dynamicContext["\$network_wifi"] = it.isConnected
                }
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_BLUETOOTH)?.let {
                    dynamicContext["\$network_bluetooth"] = it.isConnected
                }
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE)?.let {
                    dynamicContext["\$network_cellular"] = it.isConnected
                }
            }
        }

        context.telephonyManager()?.let {
            dynamicContext["\$network_carrier"] = it.networkOperatorName
        }

        return dynamicContext
    }
}
