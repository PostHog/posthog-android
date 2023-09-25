package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogIntegration
import com.posthog.android.PostHogAndroidConfig

internal class PostHogAppInstallIntegration(private val context: Context, private val config: PostHogAndroidConfig) : PostHogIntegration {
    override fun install() {
        getPackageInfo(context, config)?.let { packageInfo ->
            config.cachePreferences?.let { preferences ->
                val versionName = packageInfo.versionName
                val versionCode = packageInfo.versionCodeCompat()

                val previousVersion = preferences.getValue("version") as? String
                var previousBuild = preferences.getValue("build")

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

                preferences.setValue("version", versionName)
                preferences.setValue("build", versionCode)

                PostHog.capture(event, properties = props)
            }
        }
    }
}
