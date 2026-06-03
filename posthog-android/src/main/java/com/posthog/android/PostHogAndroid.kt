package com.posthog.android

import android.app.Application
import android.content.Context
import android.os.Build
import com.posthog.PostHog
import com.posthog.PostHogInterface
import com.posthog.android.internal.MainHandler
import com.posthog.android.internal.PostHogActivityLifecycleCallbackIntegration
import com.posthog.android.internal.PostHogAndroidContext
import com.posthog.android.internal.PostHogAndroidDateProvider
import com.posthog.android.internal.PostHogAndroidLogger
import com.posthog.android.internal.PostHogAndroidNetworkStatus
import com.posthog.android.internal.PostHogAppInstallIntegration
import com.posthog.android.internal.PostHogLifecycleObserverIntegration
import com.posthog.android.internal.PostHogMetaPropertiesApplier
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.android.internal.PostHogTouchActivityIntegration
import com.posthog.android.internal.appContext
import com.posthog.android.internal.getPackageInfo
import com.posthog.android.internal.versionCodeCompat
import com.posthog.android.replay.PostHogReplayIntegration
import com.posthog.android.replay.internal.PostHogLogCatIntegration
import com.posthog.android.surveys.PostHogSurveysIntegration
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogNoOpLogger
import com.posthog.internal.PostHogSessionManager
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import java.io.File

/**
 * Main entry point for the Android SDK.
 *
 * Use [setup] to configure the process-wide singleton, or [with] to create an instance that you
 * hold and pass around.
 */
public class PostHogAndroid private constructor() {
    public companion object {
        private val lock = Any()

        /**
         * Sets up the SDK and stores it as the global singleton.
         *
         * @param context Android context; the application context is retained internally.
         * @param config Android SDK configuration.
         */
        public fun <T : PostHogAndroidConfig> setup(
            context: Context,
            config: T,
        ) {
            synchronized(lock) {
                setAndroidConfig(context.appContext(), config)

                PostHog.setup(config)
            }
        }

        /**
         * Creates and returns a configured SDK instance that you can hold and pass around.
         *
         * Default PostHog integrations are installed only on the first instance created by either
         * [setup] or [with]. The first instance in the host app owns those integrations, so setup
         * order matters.
         *
         * @param context Android context; the application context is retained internally.
         * @param config Android SDK configuration.
         * @return The configured PostHog client instance.
         */
        public fun <T : PostHogAndroidConfig> with(
            context: Context,
            config: T,
        ): PostHogInterface {
            setAndroidConfig(context.appContext(), config)
            return PostHog.with(config)
        }

        private fun <T : PostHogAndroidConfig> setAndroidConfig(
            context: Context,
            config: T,
        ) {
            config.logger =
                if (config.logger is PostHogNoOpLogger) PostHogAndroidLogger(config) else config.logger

            val packageInfo = getPackageInfo(context, config)
            val packageName = packageInfo?.packageName ?: ""
            val versionName = packageInfo?.versionName ?: ""
            val buildNumber = packageInfo?.versionCodeCompat() ?: 0L

            // only frames coming from the package name will be considered inApp by default
            if (packageName.isNotEmpty() && !packageName.startsWith("android.")) {
                config.errorTrackingConfig.inAppIncludes.add(packageName)
            }

            val androidContext = config.context ?: PostHogAndroidContext(context, config)
            config.context = androidContext

            val legacyPath = context.getDir("app_posthog-disk-queue", Context.MODE_PRIVATE)
            val path = File(context.cacheDir, "posthog-disk-queue")
            val replayPath = File(context.cacheDir, "posthog-disk-replay-queue")
            val logsPath = File(context.cacheDir, "posthog-disk-logs-queue")
            config.legacyStoragePrefix = config.legacyStoragePrefix ?: legacyPath.absolutePath
            config.storagePrefix = config.storagePrefix ?: path.absolutePath
            config.replayStoragePrefix = config.replayStoragePrefix ?: replayPath.absolutePath
            config.logsStoragePrefix = config.logsStoragePrefix ?: logsPath.absolutePath
            val preferences = config.cachePreferences ?: PostHogSharedPreferences(context, config)
            config.cachePreferences = preferences
            // Defaults to PostHogDeviceDateProvider when api < 33
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (config.dateProvider is PostHogDeviceDateProvider) {
                    val dateProvider = PostHogAndroidDateProvider()
                    config.dateProvider = dateProvider
                    TimeBasedEpochGenerator.setDateProvider(dateProvider)
                    PostHogSessionManager.setDateProvider(dateProvider)
                } else {
                    TimeBasedEpochGenerator.setDateProvider(config.dateProvider)
                    PostHogSessionManager.setDateProvider(config.dateProvider)
                }
            }
            config.networkStatus = config.networkStatus ?: PostHogAndroidNetworkStatus(context)
            // Flutter and RN SDKs set the sdkName and sdkVersion, so this guard is not to allow
            // the values to be overwritten again
            if (config.sdkName != "posthog-flutter" && config.sdkName != "posthog-react-native") {
                config.sdkName = "posthog-android"
                config.sdkVersion = BuildConfig.VERSION_NAME
            }

            PostHogSessionManager.isReactNative = config.sdkName == "posthog-react-native"
            // Mark the process as backgrounded until the first onStart fires; an expired
            // session before any UI exists is cleared rather than silently rotated.
            PostHogSessionManager.setAppInBackground(true)

            val releaseIdentifierFallback = "$packageName@$versionName+$buildNumber"
            val metaPropertiesApplier = PostHogMetaPropertiesApplier()
            metaPropertiesApplier.applyToConfig(context, config, releaseIdentifierFallback)

            // Wire session replay sample rate provider so the core SDK can read the local value
            config.sampleRateProvider = { config.sessionReplayConfig.sampleRate }

            val mainHandler = MainHandler()
            config.addIntegration(PostHogReplayIntegration(context, config, mainHandler))
            config.addIntegration(PostHogTouchActivityIntegration(config))
            config.addIntegration(PostHogLogCatIntegration(config))
            if (context is Application) {
                if (config.captureDeepLinks || config.captureScreenViews || config.sessionReplay) {
                    config.addIntegration(
                        PostHogActivityLifecycleCallbackIntegration(
                            context,
                            config,
                        ),
                    )
                }
            }
            if (config.captureApplicationLifecycleEvents) {
                config.addIntegration(PostHogAppInstallIntegration(context, config))
            }
            config.addIntegration(PostHogLifecycleObserverIntegration(context, config, mainHandler))
            if (config.surveys) {
                config.addIntegration(PostHogSurveysIntegration(context, config))
            }
        }
    }
}
