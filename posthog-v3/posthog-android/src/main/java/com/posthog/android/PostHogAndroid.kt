package com.posthog.android

import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.android.internal.PostHogLogger

public class PostHogAndroid {
    companion object {
        private val lock = Any()

        public fun setup(context: Context, config: PostHogConfig) {
            synchronized(lock) {
                config.logger = PostHogLogger(config)
                PostHog.setup(config)
            }
        }

        public fun with(context: Context, config: PostHogConfig): PostHog {
            config.logger = PostHogLogger(config)
            return PostHog.with(config)
        }
    }
}
