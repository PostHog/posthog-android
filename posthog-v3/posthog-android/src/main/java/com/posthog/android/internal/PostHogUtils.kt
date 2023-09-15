package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.posthog.PostHogConfig

fun getPackageInfo(
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

fun getVersionCode(packageInfo: PackageInfo): Long {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
}
