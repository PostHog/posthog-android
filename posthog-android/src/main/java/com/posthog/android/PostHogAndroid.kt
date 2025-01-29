package com.posthog.android

import android.app.Application
import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogInterface
import com.posthog.android.internal.MainHandler
import com.posthog.android.internal.PostHogActivityLifecycleCallbackIntegration
import com.posthog.android.internal.PostHogAndroidContext
import com.posthog.android.internal.PostHogAndroidLogger
import com.posthog.android.internal.PostHogAndroidNetworkStatus
import com.posthog.android.internal.PostHogAppInstallIntegration
import com.posthog.android.internal.PostHogLifecycleObserverIntegration
import com.posthog.android.internal.PostHogSharedPreferences
import com.posthog.android.internal.appContext
import com.posthog.android.replay.PostHogReplayIntegration
import com.posthog.android.replay.internal.PostHogLogCatIntegration
import com.posthog.internal.PostHogNoOpLogger
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
         * @param T the type of the Config
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
         *
         * All default PostHogIntegration's will only be installed for the very 1st instance,
         * either that be created with the [setup] or [with] method otherwise they would race each other
         * and cause issues, so the 1st instance of the SDK that is created on the hosting app
         * will hold all installed integrations, the order of the setup matters.
         *
         * @param T the type of the Config
         * @property context the Context
         * @property config the Config
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
            config.logger = if (config.logger is PostHogNoOpLogger) PostHogAndroidLogger(config) else config.logger
            config.context = config.context ?: PostHogAndroidContext(context, config)

            val legacyPath = context.getDir("app_posthog-disk-queue", Context.MODE_PRIVATE)
            val path = File(context.cacheDir, "posthog-disk-queue")
            val replayPath = File(context.cacheDir, "posthog-disk-replay-queue")
            config.legacyStoragePrefix = config.legacyStoragePrefix ?: legacyPath.absolutePath
            config.storagePrefix = config.storagePrefix ?: path.absolutePath
            config.replayStoragePrefix = config.replayStoragePrefix ?: replayPath.absolutePath
            val preferences = config.cachePreferences ?: PostHogSharedPreferences(context, config)
            config.cachePreferences = preferences
            config.networkStatus = config.networkStatus ?: PostHogAndroidNetworkStatus(context)
            // Flutter SDK sets the sdkName and sdkVersion, so this guard is not to allow
            // the values to be overwritten again
            if (config.sdkName != "posthog-flutter") {
                config.sdkName = "posthog-android"
                config.sdkVersion = BuildConfig.VERSION_NAME
            }

            val mainHandler = MainHandler()
            config.addIntegration(PostHogReplayIntegration(context, config, mainHandler))
            config.addIntegration(PostHogLogCatIntegration(config))
            if (context is Application) {
                if (config.captureDeepLinks || config.captureScreenViews || config.sessionReplay) {
                    config.addIntegration(PostHogActivityLifecycleCallbackIntegration(context, config))
                }
            }
            if (config.captureApplicationLifecycleEvents) {
                config.addIntegration(PostHogAppInstallIntegration(context, config))
            }
            config.addIntegration(PostHogLifecycleObserverIntegration(context, config, mainHandler))
        }
    }
}
