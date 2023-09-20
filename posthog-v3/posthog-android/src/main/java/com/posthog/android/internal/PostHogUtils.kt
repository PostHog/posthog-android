package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import com.posthog.PostHogConfig

internal fun getPackageInfo(
    context: Context,
    config: PostHogConfig,
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
        config.logger.log("Error getting package info.")
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

internal fun hasPermission(context: Context): Boolean {
    return false
}
internal fun hasFeature(context: Context): Boolean {
    return false
}

internal fun isConnected(context: Context): Boolean {
    return false
}

internal fun Context.displayMetrics(): DisplayMetrics {
    return resources.displayMetrics
}

internal fun Context.appContext(): Context {
    return applicationContext ?: this
}
