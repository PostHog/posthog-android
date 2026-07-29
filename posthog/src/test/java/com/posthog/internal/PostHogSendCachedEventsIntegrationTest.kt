package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogInterface
import com.posthog.shutdownAndAwaitTermination
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class PostHogSendCachedEventsIntegrationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor(PostHogThreadFactory("Test"))

    private val file = File("src/test/resources/json/basic-event.json")
    private val event = file.readText()

    private fun getSut(
        storagePrefix: String = tmpDir.newFolder().absolutePath,
        host: String,
        networkStatus: PostHogNetworkStatus? = null,
    ): PostHogSendCachedEventsIntegration {
        val config =
            PostHogConfig(API_KEY, host = host).apply {
                this.storagePrefix = storagePrefix
                this.networkStatus = networkStatus
            }
        val api = PostHogApi(config)
        return PostHogSendCachedEventsIntegration(config, api, executor = executor)
    }

    @BeforeTest
    fun `set up`() {
        PostHogSendCachedEventsIntegration.resetInstallationForTesting()
    }

    @AfterTest
    fun `set down`() {
        PostHogSendCachedEventsIntegration.resetInstallationForTesting()
        tmpDir.root.deleteRecursively()
    }

    private fun writeFile(content: List<String> = emptyList()): String {
        val storagePrefix = tmpDir.newFolder().absolutePath
        val fullFile = File(storagePrefix, API_KEY)
        fullFile.mkdirs()

        content.forEach {
            val uuid = TimeBasedEpochGenerator.generate()
            val file = File(fullFile.absoluteFile, "$uuid.event")
            file.writeText(it)
        }

        return storagePrefix
    }

    @Test
    fun `concurrent installs only schedule one legacy flush`() {
        val threadCount = 32
        val scheduledFlushes = AtomicInteger()
        val start = CountDownLatch(1)
        val ready = CountDownLatch(threadCount)
        val integrations =
            List(threadCount) {
                val config = PostHogConfig(API_KEY, host = "host")
                val countingExecutor =
                    object : AbstractExecutorService() {
                        override fun execute(command: Runnable) {
                            scheduledFlushes.incrementAndGet()
                        }

                        override fun shutdown() {
                        }

                        override fun shutdownNow(): List<Runnable> = emptyList()

                        override fun isShutdown(): Boolean = false

                        override fun isTerminated(): Boolean = false

                        override fun awaitTermination(
                            timeout: Long,
                            unit: TimeUnit,
                        ): Boolean = true
                    }
                PostHogSendCachedEventsIntegration(config, PostHogApi(config), countingExecutor)
            }
        val postHog = mock<PostHogInterface>()
        val threads =
            integrations.map { integration ->
                Thread {
                    ready.countDown()
                    start.await()
                    integration.install(postHog)
                }.apply { start() }
            }

        ready.await()
        start.countDown()
        threads.forEach { it.join() }

        try {
            assertEquals(1, scheduledFlushes.get())
        } finally {
            integrations.forEach { it.uninstall() }
        }
    }

    @Test
    fun `install bails out if not connected`() {
        val storagePrefix = writeFile(listOf(event))

        val sut =
            getSut(
                storagePrefix = storagePrefix,
                host = "host",
                networkStatus =
                    object : PostHogNetworkStatus {
                        override fun isConnected() = false
                    },
            )

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        // files should still be on disk since we bailed out
        assertFalse(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())

        sut.uninstall()
    }
}
