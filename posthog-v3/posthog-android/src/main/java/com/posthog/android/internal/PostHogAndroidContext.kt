package com.posthog.android.internal

import android.content.Context
import android.os.Build
import com.posthog.PostHogConfig
import com.posthog.PostHogContext
import java.util.Locale
import java.util.TimeZone

internal class PostHogAndroidContext(private val context: Context, private val config: PostHogConfig) : PostHogContext {

    private val cacheStaticContext by lazy {
        val staticContext = mutableMapOf<String, Any>()
        val displayMetrics = context.displayMetrics()
        staticContext["\$screen_density"] = displayMetrics.density
        staticContext["\$screen_height"] = displayMetrics.heightPixels
        staticContext["\$screen_width"] = displayMetrics.widthPixels

        val packageInfo = getPackageInfo(context, config)
        packageInfo?.let {
            // TODO: check if we should use getApplicationInfo instead
            it.applicationInfo?.loadLabel(context.packageManager)?.let { name ->
                staticContext["\$app_name"] = name
            }
            it.versionName?.let { name ->
                staticContext["\$app_version"] = name
            }
            staticContext["\$app_namespace"] = it.packageName
            // TODO: is it string instead?
            staticContext["\$app_build"] = it.versionCodeCompat()
        }

        staticContext["\$device_manufacturer"] = Build.MANUFACTURER
        staticContext["\$device_model"] = Build.MODEL
        staticContext["\$device_name"] = Build.DEVICE
        staticContext["\$os_name"] = "Android"
        staticContext["\$os_version"] = Build.VERSION.RELEASE
        // TODO: $device_token?

        // TODO: read from metadata
        staticContext["\$lib"] = config.enable
        staticContext["\$lib_version"] = "version"

        staticContext
    }

    override fun getStaticContext(): Map<String, Any> {
        return cacheStaticContext
    }

    override fun getDynamicContext(): Map<String, Any> {
        val dynamicContext = mutableMapOf<String, Any>()
        dynamicContext["\$locale"] = "${Locale.getDefault().language}-${Locale.getDefault().country}"
        System.getProperty("http.agent")?.let {
            dynamicContext["\$user_agent"] = it
        }
        dynamicContext["\$timezone"] = TimeZone.getDefault().id

        // TODO: "$network_bluetooth", "$network_carrier","$network_cellular","$network_wifi"

        return dynamicContext
    }
}
