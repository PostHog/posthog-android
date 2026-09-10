package com.posthog.android.internal

import android.content.Context
import com.posthog.PostHogIntegration
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogPreferences.Companion.BUILD
import com.posthog.internal.PostHogPreferences.Companion.VERSION
import com.posthog.internal.executeSafely
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures app installed and updated events
 * @property context the App Context
 * @property config the Config
 * @property executor runs the PackageManager lookup and the preferences read/write, so the
 * thread that called setup does not pay for them
 */
internal class PostHogAppInstallIntegration(
    private val context: Context,
    private val config: PostHogAndroidConfig,
    private val executor: Executor,
) : PostHogIntegration {
    @Volatile
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

        executor.executeSafely {
            // executeSafely only guards the submission, so the task carries its own catch: this
            // work ran inline under setup's per-integration try before, and a host-supplied
            // preferences or logger that throws would otherwise reach the worker's uncaught
            // handler, which on Android ends the process.
            try {
                // uninstall() cannot cancel a queued task, so check again here: a run after a close
                // writes the VERSION and BUILD marker that says the event was already reported, while
                // its own capture is dropped, hiding that install or update for good.
                if (ownsInstallation) {
                    captureInstallOrUpdate(postHog)
                }
            } catch (e: Throwable) {
                config.logger.log("Capturing the app install or update failed: $e.")
            }
        }
    }

    private fun captureInstallOrUpdate(postHog: PostHogInterface) {
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
