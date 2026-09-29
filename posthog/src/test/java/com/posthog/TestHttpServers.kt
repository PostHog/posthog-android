package com.posthog

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.rules.ExternalResource
import org.junit.runners.model.MultipleFailureException

/** Owns servers even when a test fails before its final assertions. */
internal class TestHttpServers : ExternalResource() {
    private val servers = mutableListOf<MockWebServer>()

    fun track(server: MockWebServer): MockWebServer = server.also { if (it !in servers) servers.add(it) }

    fun create(): MockWebServer = track(MockWebServer())

    fun mockHttp(
        total: Int = 1,
        response: MockResponse = MockResponse().setBody(""),
    ): MockWebServer = track(com.posthog.mockHttp(total, response))

    override fun after() {
        val failures = mutableListOf<Throwable>()
        servers.forEach { server ->
            try {
                server.shutdown()
            } catch (error: Throwable) {
                failures.add(error)
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }
}
