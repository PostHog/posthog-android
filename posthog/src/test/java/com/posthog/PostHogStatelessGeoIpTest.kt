package com.posthog

import com.posthog.internal.PostHogBatchEvent
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

internal class PostHogStatelessGeoIpTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @get:Rule
    val httpServers = TestHttpServers()

    private lateinit var http: MockWebServer
    private lateinit var config: PostHogConfig
    private var client: PostHogStatelessInterface? = null

    private fun getSut(
        disableGeoIp: Boolean = false,
        beforeSend: PostHogBeforeSend? = null,
    ): PostHogStatelessInterface {
        http = httpServers.mockHttp()
        config =
            PostHogConfig(PROJECT_TOKEN, http.url("/").toString()).apply {
                this.disableGeoIp = disableGeoIp
                storagePrefix = tmpDir.newFolder().absolutePath
                preloadFeatureFlags = false
                flushAt = 20
                flushIntervalSeconds = 3600
                if (beforeSend != null) {
                    addBeforeSend(beforeSend)
                }
            }
        return PostHogStateless.with(config).also { client = it }
    }

    @AfterTest
    fun tearDown() {
        client?.close()
    }

    private fun flushedEvents(): List<PostHogEvent> {
        client!!.flush()
        val request = assertNotNull(http.takeRequest(10, TimeUnit.SECONDS))
        assertEquals("/batch", request.path)
        return config.serializer.deserialize<PostHogBatchEvent>(request.body.unGzip().reader()).batch
    }

    @Test
    fun `GeoIP is enabled by default and explicit stateless markers are preserved`() {
        val sut = getSut()

        sut.captureStateless("default", DISTINCT_ID)
        sut.captureStateless("disabled", DISTINCT_ID, properties = mapOf("\$geoip_disable" to true))
        sut.captureStateless("enabled", DISTINCT_ID, properties = mapOf("\$geoip_disable" to false))

        val events = flushedEvents()
        assertEquals(listOf("default", "disabled", "enabled"), events.map { it.event })
        assertFalse(events[0].properties!!.containsKey("\$geoip_disable"))
        assertEquals(true, events[1].properties!!["\$geoip_disable"])
        assertEquals(false, events[2].properties!!["\$geoip_disable"])
    }

    @Test
    fun `disableGeoIp reaches stateless hooks and all event producers`() {
        val observedMarkers = mutableListOf<Any?>()
        val sut =
            getSut(disableGeoIp = true, beforeSend = { event ->
                observedMarkers.add(event.properties?.get("\$geoip_disable"))
                event
            })

        sut.captureStateless("Save", DISTINCT_ID, properties = mapOf("\$geoip_disable" to false))
        sut.identify(DISTINCT_ID, userProperties = userProps, userPropertiesSetOnce = userPropsOnce)
        sut.aliasStateless(DISTINCT_ID, "alias-user")
        sut.groupStateless(DISTINCT_ID, "organization", "acme", groupProperties = mapOf("plan" to "paid"))
        sut.captureExceptionStateless(IllegalStateException("test"), distinctId = DISTINCT_ID)
        sut.getFeatureFlagStateless(DISTINCT_ID, "test-flag", defaultValue = false)

        val events = flushedEvents()
        assertEquals(6, events.size)
        assertEquals(
            setOf("Save", "\$identify", "\$create_alias", "\$groupidentify", "\$exception", "\$feature_flag_called"),
            events.map {
                it.event
            }.toSet(),
        )
        assertEquals(List<Any?>(6) { true }, observedMarkers)
        events.forEach { event ->
            assertEquals(true, event.properties!!["\$geoip_disable"], event.event)
        }
        val identify = events.single { it.event == "\$identify" }
        assertEquals(userProps, identify.properties!!["\$set"])
        assertEquals(userPropsOnce, identify.properties!!["\$set_once"])
        val group = events.single { it.event == "\$groupidentify" }
        assertEquals(mapOf("plan" to "paid"), group.properties!!["\$group_set"])
    }

    @Test
    fun `stateless beforeSend can replace or remove the GeoIP marker`() {
        val observedMarkers = mutableListOf<Any?>()
        val sut =
            getSut(disableGeoIp = true, beforeSend = { event ->
                observedMarkers.add(event.properties?.get("\$geoip_disable"))
                if (event.event == "replace") {
                    event.properties?.put("\$geoip_disable", false)
                } else {
                    event.properties?.remove("\$geoip_disable")
                }
                event
            })

        sut.captureStateless("replace", DISTINCT_ID)
        sut.captureStateless("remove", DISTINCT_ID)

        val events = flushedEvents()
        assertEquals(listOf("replace", "remove"), events.map { it.event })
        assertEquals(listOf<Any?>(true, true), observedMarkers)
        assertEquals(false, events[0].properties!!["\$geoip_disable"])
        assertFalse(events[1].properties!!.containsKey("\$geoip_disable"))
    }
}
