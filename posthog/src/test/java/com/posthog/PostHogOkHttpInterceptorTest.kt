package com.posthog

import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogThreadFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

internal class PostHogOkHttpInterceptorTest {
    private data class ExpectedHeaders(
        val distinctId: String? = null,
        val sessionId: String? = null,
        val windowId: String? = null,
    )

    private data class HostMatchingCase(
        val name: String,
        val configHosts: List<String>?,
        val requestHost: String,
        val shouldInject: Boolean,
    )

    private data class HeaderMutationCase(
        val name: String,
        val configHosts: List<String>?,
        val requestHost: String = PRIMARY_HOST,
        val captureNetworkTelemetry: Boolean = false,
        val endSessionBeforeRequest: Boolean = false,
        val initialHeaders: ExpectedHeaders = ExpectedHeaders(),
        val expectedHeaders: (PostHogInterface) -> ExpectedHeaders,
    )

    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun `host matching cases inject tracing headers as expected`() {
        HOST_MATCHING_CASES.forEach { testCase ->
            withTracingSut(tracingHeaders = testCase.configHosts) { postHog ->
                withServer { server ->
                    server.enqueue(MockResponse().setBody("ok"))

                    val client = newClient(postHog)
                    try {
                        executeRequest(client, server, testCase.requestHost)

                        val recordedRequest = takeRecordedRequest(server)
                        val expectedHeaders =
                            if (testCase.shouldInject) {
                                ExpectedHeaders(
                                    distinctId = postHog.distinctId(),
                                    sessionId = postHog.getSessionId()?.toString(),
                                )
                            } else {
                                ExpectedHeaders()
                            }

                        assertHeaders(recordedRequest, expectedHeaders, testCase.name)
                    } finally {
                        client.shutdown()
                    }
                }
            }
        }
    }

    @Test
    fun `tracing header mutation cases behave as expected`() {
        HEADER_MUTATION_CASES.forEach { testCase ->
            withTracingSut(tracingHeaders = testCase.configHosts) { postHog ->
                if (testCase.endSessionBeforeRequest) {
                    postHog.endSession()
                }

                withServer { server ->
                    server.enqueue(MockResponse().setBody("ok"))

                    val client = newClient(postHog, captureNetworkTelemetry = testCase.captureNetworkTelemetry)
                    try {
                        executeRequest(
                            client = client,
                            server = server,
                            host = testCase.requestHost,
                            requestBuilder = requestBuilder(testCase.initialHeaders),
                        )

                        val recordedRequest = takeRecordedRequest(server)
                        assertHeaders(recordedRequest, testCase.expectedHeaders(postHog), testCase.name)
                    } finally {
                        client.shutdown()
                    }
                }
            }
        }
    }

    private fun assertHeaders(
        recordedRequest: RecordedRequest,
        expectedHeaders: ExpectedHeaders,
        caseName: String,
    ) {
        assertEquals(
            expectedHeaders.distinctId,
            recordedRequest.getHeader(DISTINCT_ID_HEADER),
            "$caseName: unexpected distinct id header",
        )
        assertEquals(
            expectedHeaders.sessionId,
            recordedRequest.getHeader(SESSION_ID_HEADER),
            "$caseName: unexpected session id header",
        )
        assertEquals(
            expectedHeaders.windowId,
            recordedRequest.getHeader(WINDOW_ID_HEADER),
            "$caseName: unexpected window id header",
        )
    }

    private fun requestBuilder(initialHeaders: ExpectedHeaders): Request.Builder {
        return Request.Builder().apply {
            initialHeaders.distinctId?.let { header(DISTINCT_ID_HEADER, it) }
            initialHeaders.sessionId?.let { header(SESSION_ID_HEADER, it) }
            initialHeaders.windowId?.let { header(WINDOW_ID_HEADER, it) }
        }
    }

    private fun executeRequest(
        client: OkHttpClient,
        server: MockWebServer,
        host: String,
        requestBuilder: Request.Builder = Request.Builder(),
    ) {
        val request =
            requestBuilder
                .url(server.url("/test").newBuilder().host(host).build())
                .build()

        client.newCall(request).execute().use { response ->
            response.body?.string()
        }
    }

    private fun takeRecordedRequest(server: MockWebServer): RecordedRequest {
        return server.takeRequest(5, TimeUnit.SECONDS)
            ?: error("Timed out waiting for request")
    }

    private fun newClient(
        postHog: PostHogInterface,
        captureNetworkTelemetry: Boolean = false,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(LOOPBACK_DNS)
            .addInterceptor(
                PostHogOkHttpInterceptor(
                    captureNetworkTelemetry = captureNetworkTelemetry,
                    postHog = postHog,
                ),
            )
            .build()
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    @Suppress("DEPRECATION")
    private fun withTracingSut(
        tracingHeaders: List<String>?,
        block: (PostHogInterface) -> Unit,
    ) {
        val executors = TestExecutors()
        val config =
            PostHogConfig(API_KEY, "http://localhost").apply {
                this.tracingHeaders = tracingHeaders
                this.storagePrefix = tmpDir.newFolder().absolutePath
                this.replayStoragePrefix = tmpDir.newFolder().absolutePath
                this.cachePreferences = PostHogMemoryPreferences()
                this.preloadFeatureFlags = false
                this.remoteConfig = false
            }

        val postHog =
            PostHog.withInternal(
                config,
                executors.queue,
                executors.replay,
                executors.featureFlags,
                executors.cachedEvents,
                reloadFeatureFlags = false,
            )

        try {
            block(postHog)
        } finally {
            postHog.close()
            executors.shutdown()
        }
    }

    private class TestExecutors {
        val queue: ScheduledExecutorService = scheduledExecutor("TestQueue")
        val replay: ScheduledExecutorService = scheduledExecutor("TestReplayQueue")
        val featureFlags: ScheduledExecutorService = scheduledExecutor("TestRemoteConfig")
        val cachedEvents: ScheduledExecutorService = scheduledExecutor("TestCachedEvents")

        fun shutdown() {
            queue.shutdownAndAwaitTermination()
            replay.shutdownAndAwaitTermination()
            featureFlags.shutdownAndAwaitTermination()
            cachedEvents.shutdownAndAwaitTermination()
        }
    }

    private companion object {
        private const val PRIMARY_HOST = "api.example.com"
        private const val OTHER_HOST = "other.example.com"
        private const val SUBDOMAIN_HOST = "sub.api.example.com"

        private const val DISTINCT_ID_HEADER = "X-POSTHOG-DISTINCT-ID"
        private const val SESSION_ID_HEADER = "X-POSTHOG-SESSION-ID"
        private const val WINDOW_ID_HEADER = "X-POSTHOG-WINDOW-ID"

        private val HOST_MATCHING_CASES =
            listOf(
                HostMatchingCase(
                    name = "injects headers for an exact host match",
                    configHosts = listOf(PRIMARY_HOST),
                    requestHost = PRIMARY_HOST,
                    shouldInject = true,
                ),
                HostMatchingCase(
                    name = "normalizes configured hostnames",
                    configHosts = listOf("  API.EXAMPLE.COM  "),
                    requestHost = PRIMARY_HOST,
                    shouldInject = true,
                ),
                HostMatchingCase(
                    name = "injects headers when any configured host matches",
                    configHosts = listOf(OTHER_HOST, PRIMARY_HOST),
                    requestHost = PRIMARY_HOST,
                    shouldInject = true,
                ),
                HostMatchingCase(
                    name = "ignores blank configured hostnames",
                    configHosts = listOf(" ", "\t", PRIMARY_HOST),
                    requestHost = PRIMARY_HOST,
                    shouldInject = true,
                ),
                HostMatchingCase(
                    name = "does not inject headers for an empty host list",
                    configHosts = emptyList(),
                    requestHost = PRIMARY_HOST,
                    shouldInject = false,
                ),
                HostMatchingCase(
                    name = "does not inject headers when tracing headers are disabled",
                    configHosts = null,
                    requestHost = PRIMARY_HOST,
                    shouldInject = false,
                ),
                HostMatchingCase(
                    name = "does not inject headers for subdomains when only the parent host is configured",
                    configHosts = listOf(PRIMARY_HOST),
                    requestHost = SUBDOMAIN_HOST,
                    shouldInject = false,
                ),
                HostMatchingCase(
                    name = "does not inject headers for unlisted hosts",
                    configHosts = listOf(PRIMARY_HOST),
                    requestHost = OTHER_HOST,
                    shouldInject = false,
                ),
            )

        private val HEADER_MUTATION_CASES =
            listOf(
                HeaderMutationCase(
                    name = "injects headers even when network telemetry capture is disabled",
                    configHosts = listOf(PRIMARY_HOST),
                    captureNetworkTelemetry = false,
                    expectedHeaders = { postHog ->
                        ExpectedHeaders(
                            distinctId = postHog.distinctId(),
                            sessionId = postHog.getSessionId()?.toString(),
                        )
                    },
                ),
                HeaderMutationCase(
                    name = "injects headers when network telemetry capture is enabled",
                    configHosts = listOf(PRIMARY_HOST),
                    captureNetworkTelemetry = true,
                    expectedHeaders = { postHog ->
                        ExpectedHeaders(
                            distinctId = postHog.distinctId(),
                            sessionId = postHog.getSessionId()?.toString(),
                        )
                    },
                ),
                HeaderMutationCase(
                    name = "overwrites existing tracing headers on matching hosts",
                    configHosts = listOf(PRIMARY_HOST),
                    initialHeaders =
                        ExpectedHeaders(
                            distinctId = "existing-distinct-id",
                            sessionId = "existing-session-id",
                        ),
                    expectedHeaders = { postHog ->
                        ExpectedHeaders(
                            distinctId = postHog.distinctId(),
                            sessionId = postHog.getSessionId()?.toString(),
                        )
                    },
                ),
                HeaderMutationCase(
                    name = "keeps the distinct id header but omits the session header when the session has ended",
                    configHosts = listOf(PRIMARY_HOST),
                    endSessionBeforeRequest = true,
                    expectedHeaders = { postHog ->
                        ExpectedHeaders(distinctId = postHog.distinctId())
                    },
                ),
                HeaderMutationCase(
                    name = "does not touch existing tracing headers on unlisted hosts",
                    configHosts = listOf(PRIMARY_HOST),
                    requestHost = OTHER_HOST,
                    initialHeaders =
                        ExpectedHeaders(
                            distinctId = "existing-distinct-id",
                            sessionId = "existing-session-id",
                        ),
                    expectedHeaders = {
                        ExpectedHeaders(
                            distinctId = "existing-distinct-id",
                            sessionId = "existing-session-id",
                        )
                    },
                ),
                HeaderMutationCase(
                    name = "does not touch existing tracing headers when tracing header config is disabled",
                    configHosts = null,
                    initialHeaders =
                        ExpectedHeaders(
                            distinctId = "existing-distinct-id",
                            sessionId = "existing-session-id",
                        ),
                    expectedHeaders = {
                        ExpectedHeaders(
                            distinctId = "existing-distinct-id",
                            sessionId = "existing-session-id",
                        )
                    },
                ),
            )

        private val LOOPBACK_DNS =
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return when (hostname) {
                        PRIMARY_HOST,
                        OTHER_HOST,
                        SUBDOMAIN_HOST,
                        -> {
                            listOf(InetAddress.getByAddress(hostname, byteArrayOf(127.toByte(), 0, 0, 1)))
                        }

                        else -> Dns.SYSTEM.lookup(hostname)
                    }
                }
            }
    }
}

private fun scheduledExecutor(name: String): ScheduledExecutorService {
    return Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory(name))
}

private fun OkHttpClient.shutdown() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
}
