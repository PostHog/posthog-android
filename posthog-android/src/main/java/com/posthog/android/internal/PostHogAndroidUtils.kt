package com.posthog.android.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_META_DATA
import android.graphics.Point
import android.net.ConnectivityManager
import android.os.Build
import android.os.Looper
import android.os.Process
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
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

@Suppress("DEPRECATION")
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

internal fun Context.windowManager(): WindowManager? {
    return getSystemService(Context.WINDOW_SERVICE) as? WindowManager
}

internal fun Int.densityValue(density: Float): Int {
    return (this / density).toInt()
}

@Suppress("DEPRECATION")
internal fun Context.screenSize(): PostHogScreenSizeInfo? {
    val windowManager = windowManager() ?: return null
    val displayMetrics = displayMetrics()
    val screenHeight: Int
    val screenWidth: Int
    val density: Float
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val currentWindowMetrics = windowManager.currentWindowMetrics
        val screenBounds = currentWindowMetrics.bounds

        density = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            currentWindowMetrics.density
        } else {
            displayMetrics.density
        }

        screenHeight = (screenBounds.bottom - screenBounds.top).densityValue(density)
        screenWidth = (screenBounds.right - screenBounds.left).densityValue(density)
    } else {
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        screenHeight = size.y.densityValue(displayMetrics.density)
        screenWidth = size.x.densityValue(displayMetrics.density)
        density = displayMetrics.density
    }
    return PostHogScreenSizeInfo(
        width = screenWidth,
        height = screenHeight,
        density = density,
    )
}

internal fun Context.appContext(): Context {
    return applicationContext ?: this
}

internal fun Context.hasPermission(permission: String): Boolean {
    return checkPermission(permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
internal fun Context.isConnected(): Boolean {
    val connectivityManager = connectivityManager() ?: return true

    if (!hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
        return true
    }
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

internal fun isMainThread(): Boolean {
    return Thread.currentThread().id == Looper.getMainLooper().thread.id
}
