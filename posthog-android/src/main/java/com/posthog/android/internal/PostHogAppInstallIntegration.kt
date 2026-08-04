package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures app installed and updated events
 * @property context the App Context
 * @property config the Config
 */
internal class PostHogAppInstallIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
) : PostHogIntegration {
    private var ownsInstallation = false

    private companion object {
        private val integrationInstalled = AtomicBoolean(false)
    }

    @Synchronized
    override fun install(postHog: PostHogInterface) {
        // While the store is unreadable (Direct Boot) VERSION/BUILD read as absent, which would
        // fire a spurious "Application Installed" for an existing install and overwrite the
        // persisted previous build on unlock. Stay uninstalled so the correct event can still be
        // emitted once the store is readable.
        if (config.cachePreferences?.isAvailable() == false) {
            return
        }
        if (!integrationInstalled.compareAndSet(false, true)) {
            return
        }
        ownsInstallation = true

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
                    previousBuild?.let { props["previous_build"] = it }
                }
                versionName?.let { props["version"] = it }
                versionCode?.let { props["build"] = it }

                versionName?.let { preferences.setValue(VERSION, it) }
                versionCode?.let { preferences.setValue(BUILD, it) }

                postHog.capture(event, properties = props)
            }
        }
    }

    @Synchronized
    override fun uninstall() {
        if (!ownsInstallation) {
            return
        }
        ownsInstallation = false
        integrationInstalled.set(false)
    }
}
