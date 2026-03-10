package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.shutdownAndAwaitTermination
import com.posthog.vendor.uuid.TimeBasedEpochGenerator
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
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

    @AfterTest
    fun `set down`() {
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
    fun `install bails out if not connected`() {
        val storagePrefix = writeFile(listOf(event))

        val sut =
            getSut(storagePrefix = storagePrefix, host = "host", networkStatus = {
                false
            })

        sut.install(mock())

        executor.shutdownAndAwaitTermination()

        // files should still be on disk since we bailed out
        assertFalse(File(storagePrefix, API_KEY).listFiles()!!.isEmpty())

        sut.uninstall()
    }
}
