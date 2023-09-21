package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHog
import com.posthog.PostHogConfig
import com.posthog.PostHogIntegration

internal class PostHogAppInstallIntegration(private val context: Context, private val config: PostHogConfig) : PostHogIntegration {
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
                    event = "Application Updated"
                    previousVersion?.let {
                        props["previous_version"] = it
                    }
                    // to keep compatibility
                    if (previousBuild is Int) {
                        previousBuild = previousBuild.toLong()
                    }
                    props["previous_build"] = previousBuild
                }
                props["version"] = versionName
                props["build"] = versionCode

                preferences.setValue("version", versionName)
                preferences.setValue("build", versionCode)

                // TODO: do we need ot send an event every time as an update? we need to compare the Ids maybe?
                // maybe it didnt change
                PostHog.capture(event, properties = props)
            }
        }
    }
}
