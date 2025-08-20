package com.posthog.android.internal

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import kotlin.math.pow
import kotlin.math.sqrt

// Device type based on the smallest screen width quantifier
// https://developer.android.com/guide/topics/resources/providing-resources#SmallestScreenWidthQualifier
private fun getDeviceTypeFromResourceConfiguration(context: Context): String? {
    val smallestScreenWidthDp = context.resources.configuration.smallestScreenWidthDp

    return if (smallestScreenWidthDp == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
        null
    } else if (smallestScreenWidthDp >= 600) {
        "Tablet"
    } else {
        "Mobile"
    }
}

private fun getDeviceTypeFromPhysicalSize(
    context: Context,
    displayMetrics: DisplayMetrics,
): String? {
    // Find the current window manager, if none is found we can't measure the device physical size.
    val windowManager =
        context.windowManager()
            ?: return null

    // Get display metrics to see if we can differentiate phones and tablets.
    val widthInches: Double
    val heightInches: Double

    // windowManager.defaultDisplay was marked as deprecated in API level 30 (Android R) and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val densityDpi = context.resources.configuration.densityDpi
        widthInches = windowBounds.width() / densityDpi.toDouble()
        heightInches = windowBounds.height() / densityDpi.toDouble()
    } else {
        widthInches = displayMetrics.widthPixels / displayMetrics.xdpi.toDouble()
        heightInches = displayMetrics.heightPixels / displayMetrics.ydpi.toDouble()
    }

    // Calculate physical size.
    val diagonalSizeInches = sqrt(widthInches.pow(2.0) + heightInches.pow(2.0))

    return if (diagonalSizeInches in 3.0..6.9) {
        // Devices in a sane range for phones are considered to be phones.
        "Mobile"
    } else if (diagonalSizeInches > 6.9 && diagonalSizeInches <= 18.0) {
        // Devices larger than a phone and in a sane range for tablets are tablets.
        "Tablet"
    } else {
        // Otherwise, we don't know what device type we're on.
        null
    }
}

// Inspired from https://github.com/expo/expo/blob/86bafbaa0b8b9fff5b11f0e5bcf9097bb5ac8878/packages/expo-device/android/src/main/java/expo/modules/device/DeviceModule.kt#L52
// missing auto, watch, embedded, desktop, etc
internal fun getDeviceType(context: Context): String? {
    val displayMetrics = context.displayMetrics()
    // Detect TVs via UI mode (Android TVs) or system features (Fire TV).
    if (context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")) {
        return "TV"
    }

    val uiManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager?
    uiManager?.let {
        if (it.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return "TV"
        }
    }

    val deviceTypeFromResourceConfiguration = getDeviceTypeFromResourceConfiguration(context)
    return deviceTypeFromResourceConfiguration ?: getDeviceTypeFromPhysicalSize(context, displayMetrics)
}
