package com.posthog.android.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import com.posthog.android.PostHogAndroidConfig

internal fun getPackageInfo(
    context: Context,
    config: PostHogAndroidConfig,
): PackageInfo? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context
                .packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0.toLong()),
                )
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (e: Throwable) {
        config.logger.log("Error getting package info: $e.")
        null
    }
}

internal fun PackageInfo.versionCodeCompat(): Long {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        versionCode.toLong()
    }
}

internal fun Context.displayMetrics(): DisplayMetrics {
    return resources.displayMetrics
}

internal fun Context.appContext(): Context {
    return applicationContext ?: this
}

internal fun Context.hasPermission(permission: String): Boolean {
    return checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
internal fun Context.isConnected(): Boolean {
    val connectivityManager = connectivityManager() ?: return true

    if (!hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
        return true
    }
    // TODO: stop using activeNetworkInfo
    val networkInfo = connectivityManager.activeNetworkInfo ?: return false
    return networkInfo.isConnected
}

internal fun Context.connectivityManager(): ConnectivityManager? {
    return getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}

internal fun Context.telephonyManager(): TelephonyManager? {
    return getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
}

internal fun Activity.activityLabel(config: PostHogAndroidConfig): String? {
    return try {
        val info = packageManager.getActivityInfo(componentName, GET_META_DATA)
        info.loadLabel(packageManager).toString()
    } catch (e: Throwable) {
        config.logger.log("Error getting the Activity's label: $e.")
        null
    }
}
