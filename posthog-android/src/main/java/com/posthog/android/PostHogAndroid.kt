package com.posthog.android

import android.app.Application
import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogPrintLogger
import com.posthog.android.internal.PostHogActivityLifecycleCallback
import com.posthog.android.internal.PostHogAndroidContext
import com.posthog.android.internal.PostHogAndroidLogger
import com.posthog.android.internal.PostHogAndroidNetworkStatus
import com.posthog.android.internal.PostHogAppInstallIntegration
import com.posthog.android.internal.PostHogLifecycleObserverIntegration
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.android.internal.appContext
import java.io.File

public class PostHogAndroid private constructor() {
    public companion object {
        private val lock = Any()

        /**
         * Setup the SDK
         * @property context the Context
         * @property config the Config
         */
        public fun setup(context: Context, config: PostHogAndroidConfig) {
            synchronized(lock) {
                setAndroidConfig(context.appContext(), config)

                PostHog.setup(config)
            }
        }

        public fun with(context: Context, config: PostHogAndroidConfig): PostHog {
            setAndroidConfig(context.appContext(), config)
            return PostHog.with(config)
        }

        private fun setAndroidConfig(context: Context, config: PostHogAndroidConfig) {
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
                config.addIntegration(PostHogActivityLifecycleCallback(context, config))
            }
            if (config.captureApplicationLifecycleEvents) {
                config.addIntegration(PostHogAppInstallIntegration(context, config))
                config.addIntegration(PostHogLifecycleObserverIntegration(context, config))
            }
        }
    }
}
