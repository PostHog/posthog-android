package com.posthog.internal

import com.posthog.PostHogConfig
import com.posthog.apiKey
import com.posthog.shutdownAndAwaitTermination
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogFeatureFlagsTest {
    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("PostHogSendCachedEventsThread"))

    val responseApi = """
{
  "autocaptureExceptions": false,
  "toolbarParams": {},
  "errorsWhileComputingFlags": false,
  "capturePerformance": true,
  "autocapture_opt_out": false,
  "isAuthenticated": false,
  "supportedCompression": [
    "gzip",
    "gzip-js"
  ],
  "config": {
    "enable_collect_everything": true
  },
  "featureFlagPayloads": {
    "thePayload": true
  },
  "featureFlags": {
    "4535-funnel-bar-viz": true
  },
  "sessionRecording": false,
  "siteApps": [
    {
      "id": 21039.0,
      "url": "/site_app/21039/EOsOSePYNyTzHkZ3f4mjrjUap8Hy8o2vUTAc6v1ZMFP/576ac89bc8aed72a21d9b19221c2c626/"
    }
  ],
  "editorParams": {

  }
}
    """.trimIndent()

    private fun getSut(
        host: String = "https://app.posthog.com",
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogFeatureFlags {
        val config = PostHogConfig(apiKey, host).apply {
            this.networkStatus = networkStatus
        }
        val serializer = PostHogSerializer(config)
        val api = PostHogApi(config, serializer)
        return PostHogFeatureFlags(config, api, executor = executor)
    }

    private fun mockHttp(
        response: MockResponse = MockResponse()
            .setBody(responseApi),
    ): MockWebServer {
        val mock = MockWebServer()
        mock.start()
        mock.enqueue(response)
        return mock
    }

    @Test
    fun `load flags bails out if not connected`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString(), networkStatus = {
            false
        })

        sut.loadFeatureFlags("distinctId", "anonId", groups = emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertNull(sut.getFeatureFlags())
    }

    @Test
    fun `load flags from decide api and call the onFeatureFlags callback`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        var callback = false
        sut.loadFeatureFlags("my_identify", "anonId", groups = emptyMap(), onFeatureFlags = {
            callback = true
        })

        executor.shutdownAndAwaitTermination()

        val request = http.takeRequest()

        assertEquals("posthog-java/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlagPayload("thePayload", defaultValue = false) as Boolean)
        assertTrue(callback)
    }

    @Test
    fun `clear the loaded feature flags`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", groups = emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)

        sut.clear()

        assertNull(sut.getFeatureFlags())
    }

    @Test
    fun `returns all feature flags`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", groups = emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        val flags = sut.getFeatureFlags()
        assertEquals(1, flags!!.size)
    }

    @Test
    fun `returns default value if given`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", groups = emptyMap(), null)

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("notFound", defaultValue = true) as Boolean)
    }

    @Test
    fun `merge feature flags if no errors`() {
        val http = mockHttp()
        val url = http.url("/")

        val sut = getSut(host = url.toString())

        sut.loadFeatureFlags("my_identify", "anonId", groups = emptyMap(), null)

        // do not use extension to not shutdown the executor
        executor.submit {}.get()

        val currentFlag = """"4535-funnel-bar-viz": true"""
        val newFlag = """$currentFlag,
            |"foo": true
        """.trimMargin()
        val newResponse = responseApi.replace(currentFlag, newFlag)
        val response = MockResponse()
            .setBody(newResponse)
        http.enqueue(response)

        sut.loadFeatureFlags("my_identify", "anonId", groups = emptyMap(), null)

        // do not use extension to not shutdown the executor
        executor.submit {}.get()

        executor.shutdownAndAwaitTermination()

        assertTrue(sut.getFeatureFlag("4535-funnel-bar-viz", defaultValue = false) as Boolean)
        assertTrue(sut.getFeatureFlag("foo", defaultValue = false) as Boolean)
    }
}