package com.posthog.android

import android.app.Application
import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogInterface
import com.posthog.PostHogPrintLogger
import com.posthog.android.internal.PostHogActivityLifecycleCallbackIntegration
import com.posthog.android.internal.PostHogAndroidContext
import com.posthog.android.internal.PostHogAndroidLogger
import com.posthog.android.internal.PostHogAndroidNetworkStatus
import com.posthog.android.internal.PostHogAppInstallIntegration
import com.posthog.android.internal.PostHogLifecycleObserverIntegration
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.android.internal.appContext
import java.io.File

/**
 * Main entrypoint for the Android SDK
 * Use the setup method to set a global and singleton instance
 * Or use the with method that returns an instance that you can hold and pass it around
 */
public class PostHogAndroid private constructor() {
    public companion object {
        private val lock = Any()

        /**
         * Setup the SDK and set a global and singleton instance
         * @property context the Context
         * @property config the Config
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
         * Setup the SDK and returns an instance that you can hold and pass it around
         * @property context the Context
         * @property config the Config
         */
        public fun <T : PostHogAndroidConfig> with(context: Context, config: T): PostHogInterface {
            setAndroidConfig(context.appContext(), config)
            return PostHog.with(config)
        }

        private fun <T : PostHogAndroidConfig> setAndroidConfig(context: Context, config: T) {
            config.logger = if (config.logger is PostHogPrintLogger) PostHogAndroidLogger(config) else config.logger
            config.context = config.context ?: PostHogAndroidContext(context, config)

            val legacyPath = context.getDir("app_posthog-disk-queue", Context.MODE_PRIVATE)
            val path = File(context.cacheDir, "posthog-disk-queue")
            config.legacyStoragePrefix = config.legacyStoragePrefix ?: legacyPath.absolutePath
            config.storagePrefix = config.storagePrefix ?: path.absolutePath
            val preferences = config.cachePreferences ?: PostHogSharedPreferences(context, config)
            config.cachePreferences = preferences
            config.networkStatus = config.networkStatus ?: PostHogAndroidNetworkStatus(context)
            config.sdkVersion = BuildConfig.VERSION_NAME

            if (context is Application) {
                if (config.captureDeepLinks || config.captureScreenViews) {
                    config.addIntegration(PostHogActivityLifecycleCallbackIntegration(context, config))
                }
            }
            if (config.captureApplicationLifecycleEvents) {
                config.addIntegration(PostHogAppInstallIntegration(context, config))
                config.addIntegration(PostHogLifecycleObserverIntegration(context, config))
            }
        }
    }
}
