package com.posthog.server

import com.posthog.server.otel.PostHogContextSpanProcessor
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanLimits
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PostHogIncomingSpanContextTest {
    @Test
    fun `request scope enriches an existing application SDK span`() {
        SdkTracerProvider.builder().build().use { provider ->
            val span = provider.get("posthog-test").spanBuilder("incoming").startSpan() as ReadWriteSpan
            try {
                span.makeCurrent().use {
                    val context =
                        PostHogRequestContext.fromHeaders(
                            mapOf(
                                PostHogRequestContext.SESSION_ID_HEADER to "browser-session",
                                PostHogRequestContext.DISTINCT_ID_HEADER to "browser-person",
                            ),
                        )
                    PostHogRequestContext.beginScope(context, fresh = true).use {
                        val attributes = span.toSpanData().attributes.asMap().mapKeys { it.key.key }
                        assertEquals("browser-session", attributes["sessionId"])
                        assertEquals("browser-person", attributes["posthogDistinctId"])
                    }
                }
            } finally {
                span.end()
            }
        }
    }

    @Test
    fun `child spans keep their request snapshot after the scope ends`() {
        SdkTracerProvider.builder().addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
            val tracer = provider.get("posthog-test")
            val child =
                PostHogRequestContext.withContext(PostHogRequestContextData("person-one", "session-one"), fresh = true) {
                    tracer.spanBuilder("child").startSpan() as ReadWriteSpan
                }
            PostHogRequestContext.withContext(PostHogRequestContextData("person-two", "session-two"), fresh = true) {
                child.end()
                val attrs = child.toSpanData().attributes.asMap().mapKeys { it.key.key }
                assertEquals("person-one", attrs["posthogDistinctId"])
                assertEquals("session-one", attrs["sessionId"])
            }
            val outside = tracer.spanBuilder("outside").startSpan() as ReadWriteSpan
            outside.end()
            assertTrue(outside.toSpanData().attributes.isEmpty)
        }
    }

    @Test
    fun `canonical attributes of every supported type take precedence`() {
        val cases =
            listOf(
                Attributes.builder().put("sessionId", "explicit").put("posthogDistinctId", "explicit").build(),
                Attributes.builder().put("sessionId", true).put("posthogDistinctId", 42L).build(),
                Attributes.builder()
                    .put(AttributeKey.stringArrayKey("sessionId"), listOf("explicit"))
                    .put("posthogDistinctId", false).build(),
            )
        SdkTracerProvider.builder().addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
            for (explicit in cases) {
                val span =
                    provider.get("posthog-test").spanBuilder("explicit")
                        .setAllAttributes(explicit).startSpan() as ReadWriteSpan
                try {
                    span.makeCurrent().use {
                        PostHogRequestContext.beginScope(
                            PostHogRequestContextData("header-person", "header-session"),
                            fresh = true,
                        ).use {
                            assertEquals(explicit, span.toSpanData().attributes)
                        }
                    }
                } finally {
                    span.end()
                }
            }
        }
    }

    @Test
    fun `enrichment does not evict existing attributes at the SDK limit`() {
        SdkTracerProvider.builder()
            .setSpanLimits(SpanLimits.builder().setMaxNumberOfAttributes(1).build())
            .addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
                PostHogRequestContext.withContext(PostHogRequestContextData("person", "session"), fresh = true) {
                    val span =
                        provider.get("posthog-test").spanBuilder("full")
                            .setAttribute("keep", "application").startSpan() as ReadWriteSpan
                    span.makeCurrent().use {
                        PostHogRequestContext.beginScope(PostHogRequestContextData("other-person", "other-session")).use {
                            assertEquals(Attributes.of(AttributeKey.stringKey("keep"), "application"), span.toSpanData().attributes)
                        }
                    }
                    span.end()
                }
            }
    }

    @Test
    fun `missing and blank IDs remain absent`() {
        SdkTracerProvider.builder().addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
            for (value in listOf(null, "", "  ")) {
                PostHogRequestContext.withContext(PostHogRequestContextData(value, value), fresh = true) {
                    val span = provider.get("posthog-test").spanBuilder("missing").startSpan() as ReadWriteSpan
                    span.end()
                    assertTrue(span.toSpanData().attributes.isEmpty)
                }
            }
        }
    }

    @Test
    fun `failure closes inner scope and the next span sees its parent context`() {
        SdkTracerProvider.builder().addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
            val tracer = provider.get("posthog-test")
            PostHogRequestContext.withContext(PostHogRequestContextData("outer-person", "outer-session"), fresh = true) {
                assertFailsWith<IllegalStateException> {
                    PostHogRequestContext.withContext(PostHogRequestContextData("inner-person", "inner-session")) {
                        val inner = tracer.spanBuilder("inner").startSpan() as ReadWriteSpan
                        inner.end()
                        assertEquals("inner-session", inner.toSpanData().attributes.get(AttributeKey.stringKey("sessionId")))
                        throw IllegalStateException("synthetic failure")
                    }
                }
                val restored = tracer.spanBuilder("restored").startSpan() as ReadWriteSpan
                restored.end()
                assertEquals("outer-session", restored.toSpanData().attributes.get(AttributeKey.stringKey("sessionId")))
            }
            assertNull(PostHogRequestContext.current())
        }
    }

    @Test
    fun `concurrent request threads and reused workers stay isolated`() {
        SdkTracerProvider.builder().addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val ready = CountDownLatch(2)
                val futures =
                    listOf("one", "two").map { id ->
                        executor.submit<Attributes> {
                            val attributes =
                                PostHogRequestContext.withContext(
                                    PostHogRequestContextData("person-$id", "session-$id"),
                                    fresh = true,
                                ) {
                                    ready.countDown()
                                    check(ready.await(5, TimeUnit.SECONDS))
                                    val span = provider.get("posthog-test").spanBuilder("request-$id").startSpan() as ReadWriteSpan
                                    span.end()
                                    span.toSpanData().attributes
                                }
                            check(PostHogRequestContext.current() == null)
                            attributes
                        }
                    }
                futures.forEachIndexed { index, future ->
                    val id = listOf("one", "two")[index]
                    val attrs = future.get(5, TimeUnit.SECONDS)
                    assertEquals("session-$id", attrs.get(AttributeKey.stringKey("sessionId")))
                    assertEquals("person-$id", attrs.get(AttributeKey.stringKey("posthogDistinctId")))
                }
                val outside =
                    executor.submit<Boolean> {
                        val span = provider.get("posthog-test").spanBuilder("reused-worker").startSpan() as ReadWriteSpan
                        span.end()
                        span.toSpanData().attributes.isEmpty
                    }
                assertTrue(outside.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `detached work receives no implicit context after request completion`() {
        SdkTracerProvider.builder().addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
            val executor = Executors.newSingleThreadExecutor()
            val release = CountDownLatch(1)
            try {
                val work =
                    PostHogRequestContext.withContext(PostHogRequestContextData("person", "session"), fresh = true) {
                        executor.submit<Attributes> {
                            check(release.await(5, TimeUnit.SECONDS))
                            val span = provider.get("posthog-test").spanBuilder("detached").startSpan() as ReadWriteSpan
                            span.end()
                            span.toSpanData().attributes
                        }
                    }
                release.countDown()
                assertTrue(work.get(5, TimeUnit.SECONDS).isEmpty)
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `processor shutdown stops future child enrichment`() {
        val processor = PostHogContextSpanProcessor()
        SdkTracerProvider.builder().addSpanProcessor(processor).build().use { provider ->
            assertTrue(processor.shutdown().isSuccess)
            PostHogRequestContext.withContext(PostHogRequestContextData("person", "session"), fresh = true) {
                val span = provider.get("posthog-test").spanBuilder("after-shutdown").startSpan() as ReadWriteSpan
                span.end()
                assertTrue(span.toSpanData().attributes.isEmpty)
            }
        }
    }

    @Test
    fun `application sampling remains unchanged`() {
        SdkTracerProvider.builder().setSampler(Sampler.alwaysOff())
            .addSpanProcessor(PostHogContextSpanProcessor()).build().use { provider ->
                val span = provider.get("posthog-test").spanBuilder("sampled-out").startSpan()
                span.makeCurrent().use {
                    PostHogRequestContext.beginScope(PostHogRequestContextData("person", "session"), fresh = true).use {
                        assertFalse(span.isRecording)
                    }
                }
                span.end()
            }
    }

    @Test
    fun `request scopes work with no tracing SDK or with API only`() {
        for (mode in listOf("none", "api-only")) {
            val classpath =
                System.getProperty("java.class.path").split(File.pathSeparator)
                    .filterNot { path ->
                        val name = File(path).name
                        if (mode == "none") name.startsWith("opentelemetry-") else name.startsWith("opentelemetry-sdk")
                    }.joinToString(File.pathSeparator)
            val process =
                ProcessBuilder(
                    File(System.getProperty("java.home"), "bin/java").absolutePath,
                    "-cp",
                    classpath,
                    NoOpenTelemetryScopeFixture::class.java.name,
                    mode,
                ).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fixture JVM did not exit: $mode")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally {
                process.destroyForcibly()
            }
        }
    }
}
