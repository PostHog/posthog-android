package com.posthog.android

import android.app.Application
import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogPrintLogger
import com.posthog.android.internal.PostHogActivityLifecycleCallback
import com.posthog.android.internal.PostHogAndroidContext
import com.posthog.android.internal.PostHogAndroidLogger
import java.io.File

public class PostHogAndroid private constructor() {
    public companion object {
        private val lock = Any()

        public fun setup(context: Context, config: PostHogConfig) {
            synchronized(lock) {
                setAndroidConfig(context.appContext(), config)

                PostHog.setup(config)
            }
        }

        public fun with(context: Context, config: PostHogConfig): PostHog {
            setAndroidConfig(context.appContext(), config)
            return PostHog.with(config)
        }

        private fun setAndroidConfig(context: Context, config: PostHogConfig) {
            config.logger = if (config.logger is PostHogPrintLogger) PostHogAndroidLogger(config) else config.logger
            config.context = config.context ?: PostHogAndroidContext(context)

            val legacyPath = context.getDir("app_posthog-disk-queue", Context.MODE_PRIVATE)
            val path = File(context.cacheDir, "posthog-disk-queue")
            config.legacyStoragePrefix = config.legacyStoragePrefix ?: legacyPath.absolutePath
            config.storagePrefix = config.storagePrefix ?: path.absolutePath

            if (context is Application) {
                config.integrations.add(PostHogActivityLifecycleCallback(context))
            }
        }

        private fun Context.appContext(): Context {
            return applicationContext ?: this
        }
    }
}
