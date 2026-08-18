package com.posthog.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.GsonBuilder
import com.posthog.PostHogInterface
import com.posthog.PostHogOnFeatureFlags
import com.posthog.internal.PostHogContext
import com.posthog.internal.PostHogDateProvider
import com.posthog.internal.PostHogDeviceDateProvider
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogPreferences
import com.posthog.internal.PostHogSessionManager
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import java.io.File
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidEventSnapshotsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val context = mock<Context>()
    private val clients = mutableListOf<PostHogInterface>()
    private val servers = mutableListOf<MockWebServer>()
    private lateinit var originalTimeZone: TimeZone

    @BeforeTest
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.shutdown() }
        PostHogSessionManager.endSession()
        PostHogSessionManager.setAppInBackground(false)
        val deviceDateProvider = PostHogDeviceDateProvider()
        PostHogSessionManager.setDateProvider(deviceDateProvider)
        TimeBasedEpochGenerator.setDateProvider(deviceDateProvider)
        TimeZone.setDefault(originalTimeZone)
        tmpDir.root.deleteRecursively()
    }

    @Test
    fun `batch request and enriched event shapes match snapshot`() {
        val fixture = createFixture()
        val sut = fixture.client

        sut.capture(
            event = "checkout completed",
            properties =
                linkedMapOf(
                    "amount" to 42.5,
                    "currency" to "USD",
                    "items" to listOf(mapOf("sku" to "sku-1", "quantity" to 2)),
                ),
            userProperties = mapOf("plan" to "pro"),
            userPropertiesSetOnce = mapOf("first_seen_source" to "snapshot-test"),
            groups = mapOf("company" to "posthog"),
            timestamp = FIXED_DATE,
        )
        sut.identify(
            "identified-user",
            userProperties = mapOf("email" to "person@example.com"),
            userPropertiesSetOnce = mapOf("signed_up_at" to "2020-01-01"),
        )
        sut.group("company", "posthog", mapOf("industry" to "analytics", "employees" to 100))
        assertEquals("snapshot-variant", sut.getFeatureFlag("snapshot-flag"))
        sut.captureException(fixedThrowable(), mapOf("handled" to true, "component" to "checkout"))
        sut.flush()

        val request = fixture.server.takeRequests("/flags/?v=2", "/batch").single { it.path == "/batch" }
        val snapshot = requestSnapshot(request, fixture.config)
        normalizeBatchVolatileFields(snapshot)

        assertSnapshot("batch-request.json", snapshot, fixture.config)
    }

    @Test
    fun `flags request matches snapshot`() {
        val fixture = createFixture()
        val loaded = CountDownLatch(1)

        fixture.client.setPersonPropertiesForFlags(
            mapOf("plan" to "enterprise", "age" to 37),
            reloadFeatureFlags = false,
        )
        fixture.client.setGroupPropertiesForFlags(
            "company",
            mapOf("industry" to "analytics"),
            reloadFeatureFlags = false,
        )
        fixture.client.reloadFeatureFlags(PostHogOnFeatureFlags { loaded.countDown() })

        assertTrue(loaded.await(10, TimeUnit.SECONDS), "Feature flags request did not finish")
        val request = fixture.server.takeRequests("/flags/?v=2").single()

        assertSnapshot("flags-request.json", requestSnapshot(request, fixture.config), fixture.config)
    }

    @Test
    fun `session replay request and event envelope match snapshot`() {
        val fixture = createFixture()

        fixture.client.capture(
            event = "\$snapshot",
            distinctId = "identified-user",
            properties =
                linkedMapOf(
                    "\$session_id" to SESSION_ID.toString(),
                    "\$window_id" to SESSION_ID.toString(),
                    "\$snapshot_data" to
                        listOf(
                            linkedMapOf(
                                "type" to 2,
                                "timestamp" to 1_700_000_000_123L,
                                "data" to
                                    linkedMapOf(
                                        "source" to 1,
                                        "texts" to listOf("Checkout", "Pay now"),
                                        "wireframes" to listOf(mapOf("id" to 1, "type" to "view")),
                                    ),
                            ),
                        ),
                ),
            timestamp = FIXED_DATE,
        )
        fixture.client.flush()

        val request = fixture.server.takeRequests("/s/").single()
        val snapshot = requestSnapshot(request, fixture.config)
        normalizeReplayVolatileFields(snapshot)

        assertSnapshot("session-replay-request.json", snapshot, fixture.config)
    }

    private fun createFixture(): Fixture {
        mockContextAppStart(context, tmpDir)
        val server =
            MockWebServer().apply {
                repeat(10) {
                    enqueue(MockResponse().setBody(FLAGS_RESPONSE).setHeader("Content-Type", "application/json"))
                }
                start()
            }
        servers += server

        val preferences = PostHogMemoryPreferences()
        preferences.setValue(PostHogPreferences.ANONYMOUS_ID, "anonymous-user")
        preferences.setValue(PostHogPreferences.DISTINCT_ID, "anonymous-user")
        preferences.setValue(PostHogPreferences.DEVICE_ID, "device-123")
        preferences.setValue("groups", mapOf("company" to "posthog"))
        preferences.setValue("featureFlags", mapOf("snapshot-flag" to "snapshot-variant"))
        preferences.setValue("featureFlagsPayload", mapOf("snapshot-flag" to mapOf("color" to "orange")))
        preferences.setValue("feature_flag_request_id", "flag-request-123")

        val config =
            PostHogAndroidConfig(API_KEY, server.url("/").toString()).apply {
                flushAt = 20
                flushIntervalSeconds = 60
                maxBatchSize = 20
                preloadFeatureFlags = false
                @Suppress("DEPRECATION")
                remoteConfig = false
                captureApplicationLifecycleEvents = false
                captureDeepLinks = false
                captureScreenViews = false
                capturePushNotificationOpened = false
                capturePushNotificationSubscriptions = false
                sessionReplay = false
                cachePreferences = preferences
                this.context = DeterministicAndroidContext(this)
                dateProvider = FixedDateProvider()
                releaseIdentifier = "com.example.snapshot@1.2.3+42"
                requestHeaders = mapOf("X-PostHog-Snapshot" to "android-events-v1")
                setDefaultPersonProperties = false
            }

        val client = PostHogAndroid.with(context, config)
        clients += client
        PostHogSessionManager.setSessionId(SESSION_ID)
        return Fixture(client, config, server)
    }

    private fun requestSnapshot(
        request: RecordedRequest,
        config: PostHogAndroidConfig,
    ): MutableMap<String, Any?> {
        assertEquals("POST", request.method)
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        val userAgent = assertNotNull(request.getHeader("User-Agent"))
        assertEquals("posthog-android/${config.sdkVersion}", userAgent)
        assertTrue(config.sdkVersion.isNotEmpty())
        assertEquals("android-events-v1", request.getHeader("X-PostHog-Snapshot"))

        val body = GZIPInputStream(request.body.readByteArray().inputStream()).bufferedReader().use { it.readText() }
        val decodedBody = config.serializer.deserializeString(body)
        assertNotNull(decodedBody, "Request body must decode as JSON")

        return linkedMapOf(
            "method" to request.method,
            "path" to request.path,
            "headers" to
                linkedMapOf(
                    "Content-Encoding" to request.getHeader("Content-Encoding"),
                    "Content-Type" to request.getHeader("Content-Type"),
                    "User-Agent" to "posthog-android/<sdk-version>",
                    "X-PostHog-Snapshot" to request.getHeader("X-PostHog-Snapshot"),
                ),
            "body" to decodedBody,
        )
    }

    private fun normalizeBatchVolatileFields(snapshot: MutableMap<String, Any?>) {
        val body = snapshot.map("body")
        assertEquals(FIXED_TIMESTAMP, body["sent_at"])

        val batch = body["batch"]
        assertIs<List<*>>(batch)
        assertEquals(
            listOf("checkout completed", "\$identify", "\$groupidentify", "\$feature_flag_called", "\$exception"),
            batch.map { (it as Map<*, *>)["event"] },
        )
        batch.forEach { rawEvent ->
            @Suppress("UNCHECKED_CAST")
            val event = rawEvent as MutableMap<String, Any?>
            assertEquals(FIXED_TIMESTAMP, event["timestamp"])
            val uuid = assertIs<String>(event["uuid"])
            UUID.fromString(uuid)
            event["uuid"] = "<uuid>"
            normalizeSdkVersion(event.map("properties"))
        }

        val exceptionEvent = batch.last() as Map<*, *>
        val exceptionProperties = exceptionEvent["properties"] as Map<*, *>
        val exceptionList = assertIs<List<*>>(exceptionProperties["\$exception_list"])
        exceptionList.forEach { rawException ->
            @Suppress("UNCHECKED_CAST")
            val exception = rawException as MutableMap<String, Any?>
            assertIs<Number>(exception["thread_id"])
            exception["thread_id"] = "<thread_id>"
        }
    }

    private fun normalizeReplayVolatileFields(snapshot: MutableMap<String, Any?>) {
        val body = snapshot["body"]
        assertIs<List<*>>(body)
        assertEquals(1, body.size)
        @Suppress("UNCHECKED_CAST")
        val event = body.single() as MutableMap<String, Any?>
        assertEquals("\$snapshot", event["event"])
        assertEquals(FIXED_TIMESTAMP, event["timestamp"])
        val uuid = assertIs<String>(event["uuid"])
        UUID.fromString(uuid)
        event["uuid"] = "<uuid>"
        normalizeSdkVersion(event.map("properties"))
    }

    private fun normalizeSdkVersion(properties: MutableMap<String, Any?>) {
        val version = assertIs<String>(properties["\$lib_version"])
        assertTrue(version.isNotEmpty())
        properties["\$lib_version"] = "<sdk-version>"
    }

    private fun assertSnapshot(
        name: String,
        actual: Map<String, Any?>,
        config: PostHogAndroidConfig,
    ) {
        if (System.getenv("UPDATE_EVENT_SHAPE_SNAPSHOTS") == "1") {
            val rendered = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(actual)
            snapshotSourceFile(name).writeText("$rendered\n")
            return
        }

        val resource = javaClass.getResource("/json/snapshots/$name")
        assertNotNull(resource, "Missing snapshot resource json/snapshots/$name")
        val expected = config.serializer.deserializeString(resource.readText())
        assertEquals(
            expected,
            actual,
            "Snapshot snapshots/$name changed. Re-record with UPDATE_EVENT_SHAPE_SNAPSHOTS=1.",
        )
    }

    private fun snapshotSourceFile(name: String): File {
        val paths =
            listOf(
                File("posthog-android/src/test/resources/json/snapshots/$name"),
                File("src/test/resources/json/snapshots/$name"),
            )
        return paths.firstOrNull { it.parentFile?.isDirectory == true } ?: paths.first()
    }

    private fun MockWebServer.takeRequests(vararg paths: String): List<RecordedRequest> {
        val requests =
            paths.map {
                val request = takeRequest(10, TimeUnit.SECONDS)
                assertNotNull(request, "Timed out waiting for ${paths.joinToString()}")
                request
            }
        assertEquals(paths.sorted(), requests.map { assertNotNull(it.path) }.sorted())
        assertEquals(paths.size, requestCount, "Unexpected additional network requests")
        return requests
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.map(key: String): MutableMap<String, Any?> {
        return assertIs<MutableMap<String, Any?>>(this[key])
    }

    private fun fixedThrowable(): Throwable {
        val cause = IllegalArgumentException("Card token is invalid")
        cause.stackTrace = arrayOf(StackTraceElement("com.example.PaymentApi", "charge", "PaymentApi.kt", 73))
        return IllegalStateException("Checkout failed", cause).apply {
            stackTrace = arrayOf(StackTraceElement("com.example.CheckoutViewModel", "submit", "CheckoutViewModel.kt", 41))
        }
    }

    private data class Fixture(
        val client: PostHogInterface,
        val config: PostHogAndroidConfig,
        val server: MockWebServer,
    )

    private class DeterministicAndroidContext(private val config: PostHogAndroidConfig) : PostHogContext {
        override fun getStaticContext(): Map<String, Any> =
            linkedMapOf(
                "\$app_name" to "Snapshot App",
                "\$app_namespace" to "com.example.snapshot",
                "\$app_version" to "1.2.3",
                "\$app_build" to 42,
                "\$device_manufacturer" to "PostHog",
                "\$device_model" to "Snapshot Phone",
                "\$device_name" to "snapshot-device",
                "\$device_type" to "Mobile",
                "\$os_name" to "Android",
                "\$os_version" to "14",
                "\$screen_density" to 2.0,
                "\$screen_height" to 800,
                "\$screen_width" to 400,
                "\$is_emulator" to false,
            )

        override fun getDynamicContext(): Map<String, Any> =
            linkedMapOf(
                "\$locale" to "en-US",
                "\$timezone" to "UTC",
                "\$network_type" to "wifi",
                "\$network_carrier" to "Snapshot Telecom",
            )

        override fun getSdkInfo(): Map<String, Any> =
            linkedMapOf(
                "\$lib" to config.sdkName,
                "\$lib_version" to config.sdkVersion,
            )
    }

    private class FixedDateProvider : PostHogDateProvider {
        override fun currentDate(): Date = FIXED_DATE

        override fun addSecondsToCurrentDate(seconds: Int): Date = Date(FIXED_MILLIS + seconds * 1_000L)

        override fun currentTimeMillis(): Long = FIXED_MILLIS

        override fun nanoTime(): Long = FIXED_MILLIS * 1_000_000L
    }

    private companion object {
        private const val FIXED_MILLIS = 1_700_000_000_123L
        private val FIXED_DATE = Date(FIXED_MILLIS)
        private val SESSION_ID = UUID.fromString("018bcfe5-687b-7abc-8def-0123456789ab")
        private const val FIXED_TIMESTAMP = "2023-11-14T22:13:20.123Z"
        private const val FLAGS_RESPONSE =
            """{
                "flags": {
                    "snapshot-flag": {
                        "key": "snapshot-flag",
                        "enabled": true,
                        "variant": "snapshot-variant",
                        "metadata": {"id": 123, "version": 7, "payload": "{\"color\":\"orange\"}"}
                    }
                },
                "errorsWhileComputingFlags": false,
                "requestId": "flag-request-123"
            }"""
    }
}
