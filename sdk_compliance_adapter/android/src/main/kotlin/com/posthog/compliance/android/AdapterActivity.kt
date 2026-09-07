package com.posthog.compliance.android

import android.app.Activity
import android.os.Bundle
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.compliance.InitRequest
import com.posthog.compliance.Observation
import com.posthog.compliance.SdkClient
import com.posthog.compliance.SdkProfile
import com.posthog.compliance.StatefulClient
import com.posthog.compliance.complianceRoutes
import com.posthog.compliance.configureStateful
import com.posthog.compliance.sdkVersion
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import java.io.File

class AdapterActivity : Activity() {
    private var server: ApplicationEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port", 8080)
        val context = applicationContext
        val profile =
            object : SdkProfile {
                override val name = "posthog-android-entry"
                override val version = sdkVersion("android")

                override fun create(
                    request: InitRequest,
                    storage: File,
                    observer: Observation,
                ): SdkClient {
                    // This test app owns its storage; start each case without persisted SDK identity/queues.
                    context.cacheDir.listFiles()?.filter { it.name.startsWith("posthog-") }?.forEach { it.deleteRecursively() }
                    File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach {
                        context.getSharedPreferences(it.nameWithoutExtension, MODE_PRIVATE).edit().clear().commit()
                    }
                    val config =
                        PostHogAndroidConfig(
                            request.api_key,
                            request.host,
                            captureApplicationLifecycleEvents = false,
                            captureDeepLinks = false,
                            captureScreenViews = false,
                            capturePushNotificationSubscriptions = false,
                            capturePushNotificationOpened = false,
                        )
                    val completion = configureStateful(config, request, observer)
                    return StatefulClient(PostHogAndroid.with(context, config), observer, completion)
                }
            }
        server =
            embeddedServer(CIO, port = port) {
                complianceRoutes(profile, port, File(context.cacheDir, "compliance"))
            }.start(wait = false)
    }

    override fun onDestroy() {
        server?.stop(100, 1000)
        super.onDestroy()
    }
}
