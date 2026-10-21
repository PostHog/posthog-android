package com.posthog.android.internal

import android.content.Context
import android.content.pm.PackageInfo
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records app version/build and optionally captures app installed and updated events
 * @property config the Config
 */
internal class PostHogAppInstallIntegration(
    context: Context,
    private val config: PostHogAndroidConfig,
    private val packageInfoProvider: () -> PackageInfo? = { getPackageInfo(context, config) },
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
        packageInfoProvider()?.let { packageInfo ->
            config.cachePreferences?.let { preferences ->
                val versionName = packageInfo.versionName
                val versionCode = packageInfo.versionCodeCompat()

                val previousVersion = preferences.getValue(VERSION) as? String
                var previousBuild = preferences.getValue(BUILD)

                // Remember this launch independently of event capture, using the previous values
                // above to classify it. Consent still gates delivery in postHog.capture.
                versionName?.let { preferences.setValue(VERSION, it) }
                versionCode?.let { preferences.setValue(BUILD, it) }

                if (!config.captureApplicationLifecycleEvents || !integrationInstalled.compareAndSet(false, true)) {
                    return
                }
                ownsInstallation = true

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

                postHog.capture(event, properties = props)
            }
        }
    }

    /**
     * Records the app version/build without capturing, for a launch while opted out: integrations
     * aren't installed then, and skipping this would report a later update as an install.
     */
    @Synchronized
    internal fun recordAppVersion() {
        // runs outside the integration install failure boundary, so it must not throw into setup
        try {
            if (config.cachePreferences?.isAvailable() == false) {
                return
            }
            val packageInfo = packageInfoProvider() ?: return
            val preferences = config.cachePreferences ?: return
            packageInfo.versionName?.let { preferences.setValue(VERSION, it) }
            packageInfo.versionCodeCompat()?.let { preferences.setValue(BUILD, it) }
        } catch (e: Throwable) {
            config.logger.log("Recording the app version failed: $e.")
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
