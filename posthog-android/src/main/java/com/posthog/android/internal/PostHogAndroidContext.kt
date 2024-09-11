package com.posthog.android.internal

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager.TYPE_BLUETOOTH
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.ConnectivityManager.TYPE_WIFI
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import android.util.DisplayMetrics
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogContext
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.sqrt

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
    private val cacheSdkInfo by lazy {
        val sdkInfo = mutableMapOf<String, Any>()

        sdkInfo["\$lib"] = config.sdkName
        sdkInfo["\$lib_version"] = config.sdkVersion

        sdkInfo
    }

    private val cacheStaticContext by lazy {
        val staticContext = mutableMapOf<String, Any>()

        // we don't use the context.screenSize() specifically because that requires a UI Context
        // and here is the App Context always
        val displayMetrics = context.displayMetrics()
        staticContext["\$screen_density"] = displayMetrics.density
        staticContext["\$screen_height"] = displayMetrics.heightPixels.densityValue(displayMetrics.density)
        staticContext["\$screen_width"] = displayMetrics.widthPixels.densityValue(displayMetrics.density)

        getPackageInfo(context, config)?.let {
            it.versionName?.let { name ->
                staticContext["\$app_version"] = name
            }
            staticContext["\$app_namespace"] = it.packageName
            staticContext["\$app_build"] = it.versionCodeCompat()
        }
        staticContext["\$app_name"] = context.applicationInfo.loadLabel(context.packageManager)

        staticContext["\$device_manufacturer"] = Build.MANUFACTURER
        staticContext["\$device_model"] = Build.MODEL // returns eg Pixel 7a
        staticContext["\$device_name"] = Build.DEVICE // returns eg lynx
        // Check https://github.com/PostHog/posthog-flutter/issues/49 and change if needed
        staticContext["\$device_type"] = getDeviceType(context, displayMetrics) ?: "Mobile"
        staticContext["\$os_name"] = "Android"
        staticContext["\$os_version"] = Build.VERSION.RELEASE

        staticContext["\$is_emulator"] = isEmulator

        staticContext
    }

    // Inspired from https://github.com/expo/expo/blob/86bafbaa0b8b9fff5b11f0e5bcf9097bb5ac8878/packages/expo-device/android/src/main/java/expo/modules/device/DeviceModule.kt#L52
    // missing auto, watch, embedded, desktop, etc
    private fun getDeviceType(
        context: Context,
        displayMetrics: DisplayMetrics,
    ): String? {
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

        // TODO: use ConnectivityManager.NetworkCallback instead
        context.connectivityManager()?.let { connectivityManager ->
            if (context.hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    connectivityManager.activeNetwork?.let {
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(it)

                        networkCapabilities?.let { capabilities ->
                            dynamicContext["\$network_wifi"] = capabilities.hasTransport(TRANSPORT_WIFI)
                            dynamicContext["\$network_bluetooth"] = capabilities.hasTransport(TRANSPORT_BLUETOOTH)
                            dynamicContext["\$network_cellular"] = capabilities.hasTransport(TRANSPORT_CELLULAR)
                        }
                    }
                } else {
                    connectivityManager.getNetworkInfo(TYPE_WIFI)?.let {
                        dynamicContext["\$network_wifi"] = it.isConnected
                    }
                    connectivityManager.getNetworkInfo(TYPE_BLUETOOTH)?.let {
                        dynamicContext["\$network_bluetooth"] = it.isConnected
                    }
                    connectivityManager.getNetworkInfo(TYPE_MOBILE)?.let {
                        dynamicContext["\$network_cellular"] = it.isConnected
                    }
                }
            }
        }

        context.telephonyManager()?.let {
            val networkOperatorName = it.networkOperatorName
            if (!networkOperatorName.isNullOrEmpty()) {
                dynamicContext["\$network_carrier"] = networkOperatorName
            }
        }

        return dynamicContext
    }

    override fun getSdkInfo(): Map<String, Any> {
        return cacheSdkInfo
    }

    // Inspired from https://github.com/fluttercommunity/plus_plugins/blob/a71a27c5fbdbbfc56a30359a1aff0a3d3da8dc73/packages/device_info_plus/device_info_plus/android/src/main/kotlin/dev/fluttercommunity/plus/device_info/MethodCallHandlerImpl.kt#L105-L123
    private val isEmulator: Boolean
        get() = (
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("vbox86p") ||
                Build.PRODUCT.contains("emulator") ||
                Build.PRODUCT.contains("simulator")
        )
}
