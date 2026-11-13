package com.posthog.server

import com.posthog.BuildConfig
import com.posthog.internal.PostHogApi
import com.posthog.server.internal.PostHogFeatureFlags
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogRemoteConfigPayloadTest {
    private class RecordingDispatcher(
        @Volatile var response: () -> MockResponse,
    ) : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        val remoteConfigRequests: List<RecordedRequest>
            get() = requests.filter { it.path?.contains("remote_config") == true }

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            return if (request.path?.contains("remote_config") == true) response() else MockResponse().setResponseCode(200)
        }
    }

    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<PostHogInterface>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.shutdown() }
    }

    private fun startServer(response: () -> MockResponse = { MockResponse().setBody("{}") }): Pair<MockWebServer, RecordingDispatcher> {
        val dispatcher = RecordingDispatcher(response)
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        servers.add(server)
        return server to dispatcher
    }

    private fun createClient(
        server: MockWebServer,
        projectToken: String = TEST_PROJECT_TOKEN,
        personalApiKey: String? = "phx_test",
    ): PostHogInterface {
        val client =
            PostHog.with(
                PostHogConfig.builder(projectToken)
                    .host(server.url("/").toString())
                    .personalApiKey(personalApiKey)
                    .localEvaluation(false)
                    .build(),
            )
        clients.add(client)
        return client
    }

    private fun payloadOf(body: String): Any? {
        val (server, _) = startServer { MockResponse().setBody(body) }
        return createClient(server).getRemoteConfigPayload("test-flag")
    }

    private fun createFeatureFlags(
        server: MockWebServer,
        logger: TestLogger,
        personalApiKey: String? = "phx_test",
    ): PostHogFeatureFlags {
        val config = createTestConfig(logger, host = server.url("/").toString())
        return PostHogFeatureFlags(config, PostHogApi(config), 60000, 100, personalApiKey = personalApiKey)
    }

    private fun capturingStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `getRemoteConfigPayload returns a map for an object body`() {
        val result = payloadOf("""{"foo":["bar","baz"]}""")

        assertEquals(mapOf("foo" to listOf("bar", "baz")), result)
    }

    @Test
    fun `getRemoteConfigPayload returns an empty map for an empty object body`() {
        assertEquals(emptyMap<String, Any?>(), payloadOf("{}"))
    }

    @Test
    fun `getRemoteConfigPayload parses a double-encoded string body`() {
        val result = payloadOf(""""{ \"foo\":[\"bar\",\"baz\"]}"""")

        assertEquals(mapOf("foo" to listOf("bar", "baz")), result)
    }

    @Test
    fun `getRemoteConfigPayload returns the string when the second parse fails`() {
        assertEquals("hello world", payloadOf(""""hello world""""))
        assertEquals("{bad", payloadOf(""""{bad""""))
    }

    @Test
    fun `getRemoteConfigPayload parses a string that holds a JSON scalar`() {
        assertEquals(123, payloadOf(""""123""""))
    }

    @Test
    fun `getRemoteConfigPayload returns a list and a number body`() {
        assertEquals(listOf(1, 2), payloadOf("[1,2]"))
        assertEquals(42, payloadOf("42"))
    }

    @Test
    fun `getRemoteConfigPayload returns null for a JSON null body and for an empty body`() {
        assertNull(payloadOf("null"))
        assertNull(payloadOf(""))
    }

    @Test
    fun `getRemoteConfigPayload returns null without a request when no personal API key is set`() {
        val (server, dispatcher) = startServer()
        val logger = TestLogger()
        val featureFlags = createFeatureFlags(server, logger, personalApiKey = null)

        assertNull(featureFlags.getRemoteConfigPayload("test-flag"))

        assertEquals(0, dispatcher.requests.size)
        assertEquals(
            listOf("Fetching a remote config payload requires a personal API key. This call will be ignored."),
            logger.warnings,
        )
    }

    @Test
    fun `getRemoteConfigPayload returns null without a request when the client is disabled`() {
        val (server, dispatcher) = startServer()

        val blankToken = createClient(server, projectToken = "")
        assertNull(blankToken.getRemoteConfigPayload("test-flag"))

        val closed = createClient(server)
        closed.close()
        assertNull(closed.getRemoteConfigPayload("test-flag"))

        assertEquals(0, dispatcher.remoteConfigRequests.size)
    }

    @Test
    fun `getRemoteConfigPayload checks the disabled state before the secret key`() {
        val (server, dispatcher) = startServer()
        val client = createClient(server, projectToken = "", personalApiKey = null)

        val output = capturingStdout { assertNull(client.getRemoteConfigPayload("test-flag")) }

        assertEquals(0, dispatcher.requests.size)
        assertFalse(output.contains("personal API key"), "unexpected output: $output")
    }

    @Test
    fun `getRemoteConfigPayload returns null and logs a warning on a non-2xx status`() {
        listOf(401, 404, 500).forEach { code ->
            val (server, dispatcher) = startServer { MockResponse().setResponseCode(code).setBody("""{"detail":"nope"}""") }
            val logger = TestLogger()
            val featureFlags = createFeatureFlags(server, logger)

            assertNull(featureFlags.getRemoteConfigPayload("test-flag"))

            assertEquals(1, dispatcher.remoteConfigRequests.size)
            assertEquals(1, logger.warnings.size)
            assertTrue(logger.warnings.single().contains("failed with status $code"), logger.warnings.single())
            assertTrue(logger.logs.none { it.contains("nope") })
        }
    }

    @Test
    fun `getRemoteConfigPayload returns null and logs a warning when the connection fails`() {
        val (server, _) = startServer()
        val logger = TestLogger()
        val featureFlags = createFeatureFlags(server, logger)
        server.shutdown()

        assertNull(featureFlags.getRemoteConfigPayload("test-flag"))

        assertEquals(1, logger.warnings.size)
        assertTrue(
            logger.warnings.single().contains("Loading the remote config payload for flag 'test-flag' failed"),
            logger.warnings.single(),
        )
    }

    @Test
    fun `getRemoteConfigPayload returns null and logs a warning on a body that is not JSON`() {
        listOf("{bad", "OK", "12abc", "<html>x</html>", "{a:1}", "{} x").forEach { body ->
            val (server, dispatcher) = startServer { MockResponse().setBody(body) }
            val logger = TestLogger()
            val featureFlags = createFeatureFlags(server, logger)

            assertNull(featureFlags.getRemoteConfigPayload("test-flag"), body)

            assertEquals(1, dispatcher.remoteConfigRequests.size)
            assertEquals(
                listOf("Loading the remote config payload for flag 'test-flag' failed: the response body is not valid JSON."),
                logger.warnings,
                body,
            )
        }
    }

    @Test
    fun `getRemoteConfigPayload returns null and logs a warning on an empty or blank body`() {
        listOf("", "  \n").forEach { body ->
            val (server, _) = startServer { MockResponse().setBody(body) }
            val logger = TestLogger()
            val featureFlags = createFeatureFlags(server, logger)

            assertNull(featureFlags.getRemoteConfigPayload("test-flag"))

            assertEquals(
                listOf("Loading the remote config payload for flag 'test-flag' failed: the response body is empty."),
                logger.warnings,
            )
        }
    }

    @Test
    fun `getRemoteConfigPayload keeps a string payload that is not strict JSON`() {
        assertEquals("OK", payloadOf(""""OK""""))
        assertEquals("{a:1}", payloadOf(""""{a:1}""""))
        assertEquals("", payloadOf(""""""""))
    }

    @Test
    fun `getRemoteConfigPayload logs no warning for a valid body`() {
        listOf("null", """"OK"""", """{"foo":"bar"}""").forEach { body ->
            val (server, _) = startServer { MockResponse().setBody(body) }
            val logger = TestLogger()

            createFeatureFlags(server, logger).getRemoteConfigPayload("test-flag")

            assertEquals(emptyList(), logger.warnings, body)
        }
    }

    @Test
    fun `getRemoteConfigPayload returns null for a blank, dot, or dot-dot key`() {
        val (server, dispatcher) = startServer()
        val logger = TestLogger()
        val featureFlags = createFeatureFlags(server, logger)
        val client = createClient(server)

        listOf("", " ", ".", "..").forEach { key ->
            assertNull(featureFlags.getRemoteConfigPayload(key))
            assertNull(client.getRemoteConfigPayload(key))
        }

        assertEquals(
            listOf("", " ", ".", "..").map { "The flag key '$it' is not valid for a remote config payload. No request was sent." },
            logger.warnings,
        )
        assertEquals(0, dispatcher.remoteConfigRequests.size)
    }

    @Test
    fun `getRemoteConfigPayload sends the expected request`() {
        val (server, dispatcher) = startServer()
        val client = createClient(server)

        client.getRemoteConfigPayload("a/b")

        val request = dispatcher.remoteConfigRequests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/projects/@current/feature_flags/a%2Fb/remote_config?token=$TEST_PROJECT_TOKEN", request.path)
        assertEquals("Bearer phx_test", request.headers["Authorization"])
        assertEquals("posthog-server/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
    }

    @Test
    fun `getRemoteConfigPayload sends no events`() {
        val (server, dispatcher) = startServer()
        val client = createClient(server)

        client.getRemoteConfigPayload("test-flag")
        client.flush()

        assertEquals(dispatcher.requests.size, dispatcher.remoteConfigRequests.size)
        assertTrue(dispatcher.requests.none { it.path?.contains("/batch") == true || it.path?.contains("/flags") == true })
    }

    @Test
    fun `getRemoteConfigPayload works without local evaluation`() {
        val (server, dispatcher) = startServer { MockResponse().setBody("""{"foo":"bar"}""") }
        val client = createClient(server)

        assertEquals(mapOf("foo" to "bar"), client.getRemoteConfigPayload("test-flag"))

        assertEquals(1, dispatcher.requests.size)
        assertEquals("GET", dispatcher.requests.single().method)
    }
}
