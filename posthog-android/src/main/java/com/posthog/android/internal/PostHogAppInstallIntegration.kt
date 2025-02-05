package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.VERSION

/**
 * Captures app installed and updated events
 * @property context the App Context
 * @property config the Config
 */
internal class PostHogAppInstallIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) : PostHogIntegration {
    private companion object {
        @Volatile
        private var integrationInstalled = false
    }

    override fun install(postHog: PostHogInterface) {
        if (integrationInstalled) {
            return
        }
        integrationInstalled = true

        getPackageInfo(context, config)?.let { packageInfo ->
            config.cachePreferences?.let { preferences ->
                val versionName = packageInfo.versionName
                val versionCode = packageInfo.versionCodeCompat()

                val previousVersion = preferences.getValue(VERSION) as? String
                var previousBuild = preferences.getValue(BUILD)

                val event: String
                val props = mutableMapOf<String, Any>()
                if (previousBuild == null) {
                    event = "Application Installed"
                } else {
                    // to keep compatibility
                    if (previousBuild is Int) {
                        previousBuild = previousBuild.toLong()
                    }

                    // Do not send version updates if its the same
                    if (previousBuild == versionCode) {
                        return
                    }

                    event = "Application Updated"
                    previousVersion?.let {
                        props["previous_version"] = it
                    }
                    props["previous_build"] = previousBuild
                }
                props["version"] = versionName
                props["build"] = versionCode

                preferences.setValue(VERSION, versionName)
                preferences.setValue(BUILD, versionCode)

                postHog.capture(event, properties = props)
            }
        }
    }

    override fun uninstall() {
        integrationInstalled = false
    }
}
