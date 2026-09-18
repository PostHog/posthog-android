package com.posthog.android

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogInterface
import com.posthog.android.internal.PostHogLifecycleObserverIntegration
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogNetworkStatus
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 33])
internal class PostHogAndroidSetupLookupsTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val context = mock<Context>()
    private lateinit var app: Application
    private lateinit var cacheDir: File
    private val assets = mock<AssetManager>()
    private val clients = mutableListOf<PostHogInterface>()
    private val packageInfo =
        PackageInfo().apply {
            packageName = "com.package"
            versionName = "1.0.0"
            @Suppress("DEPRECATION")
            versionCode = 1
        }

    @BeforeTest
    fun setUp() {
        mockContextAppStart(context, tmpDir)
        app = context.applicationContext as Application
        app.mockPackageInfo()
        app.mockDisplayMetrics()
        app.mockAppInfo()
        cacheDir = app.cacheDir
        whenever(app.assets).thenReturn(assets)
        whenever(assets.open(any())).thenThrow(FileNotFoundException())
        stubPackageInfo(packageInfo)
        clearInvocations(app)
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
    }

    private fun config() =
        PostHogAndroidConfig(API_KEY, host = "http://localhost:1").apply {
            cachePreferences = PostHogMemoryPreferences()
            preloadFeatureFlags = false
            networkStatus =
                object : PostHogNetworkStatus {
                    override fun isConnected(): Boolean = false
                }
        }

    private fun setup(config: PostHogAndroidConfig) {
        clients.add(PostHogAndroid.with(context, config))
    }

    @Suppress("DEPRECATION")
    private fun stubPackageInfo(value: PackageInfo?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            whenever(app.packageManager.getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>())).thenReturn(value)
        } else {
            whenever(app.packageManager.getPackageInfo(any<String>(), any<Int>())).thenReturn(value)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageInfoCalls(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            verify(app.packageManager, times(count)).getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>())
        } else {
            verify(app.packageManager, times(count)).getPackageInfo(any<String>(), any<Int>())
        }
    }

    @Test
    fun `cache directory is resolved once for all default paths`() {
        val config = config()
        setup(config)

        verify(app, times(1)).cacheDir
        assertEquals(File(cacheDir, "posthog-disk-queue").absolutePath, config.storagePrefix)
        assertEquals(File(cacheDir, "posthog-disk-replay-queue").absolutePath, config.replayStoragePrefix)
        assertEquals(File(cacheDir, "posthog-disk-logs-queue").absolutePath, config.logsStoragePrefix)
    }

    @Test
    fun `overridden paths do not resolve directories`() {
        val config =
            config().apply {
                legacyStoragePrefix = tmpDir.newFolder().absolutePath
                storagePrefix = tmpDir.newFolder().absolutePath
                replayStoragePrefix = tmpDir.newFolder().absolutePath
                logsStoragePrefix = tmpDir.newFolder().absolutePath
            }
        val expected = listOf(config.legacyStoragePrefix, config.storagePrefix, config.replayStoragePrefix, config.logsStoragePrefix)
        setup(config)

        verify(app, never()).cacheDir
        verify(app, never()).getDir(any(), any())
        assertEquals(
            expected,
            listOf(config.legacyStoragePrefix, config.storagePrefix, config.replayStoragePrefix, config.logsStoragePrefix),
        )
    }

    @Test
    fun `partially overridden paths share the cache directory`() {
        val customPath = tmpDir.newFolder().absolutePath
        val config = config().apply { storagePrefix = customPath }
        setup(config)

        verify(app, times(1)).cacheDir
        assertEquals(customPath, config.storagePrefix)
        assertEquals(File(cacheDir, "posthog-disk-replay-queue").absolutePath, config.replayStoragePrefix)
        assertEquals(File(cacheDir, "posthog-disk-logs-queue").absolutePath, config.logsStoragePrefix)
    }

    @Test
    fun `package info is shared by release install context and app open`() {
        val config = config()
        setup(config)
        val properties = config.context!!.getStaticContext()
        config.context!!.getStaticContext()
        config.integrations.filterIsInstance<PostHogLifecycleObserverIntegration>().single().onStart(mock<LifecycleOwner>())

        verifyPackageInfoCalls(1)
        assertEquals("com.package@1.0.0+1", config.releaseIdentifier)
        assertEquals("1.0.0", properties["\$app_version"])
        assertEquals(1L, properties["\$app_build"])
    }

    @Test
    fun `package info is not resolved when no consumer needs it`() {
        val config =
            config().apply {
                releaseIdentifier = "manual-id"
                captureApplicationLifecycleEvents = false
                context = mock()
            }
        setup(config)

        verifyPackageInfoCalls(0)
        verify(assets, never()).open(any())
        assertEquals("manual-id", config.releaseIdentifier)
        assertEquals(true, config.errorTrackingConfig.inAppIncludes.contains("com.package"))
    }

    @Test
    fun `mapping id avoids package lookup until static context is read`() {
        whenever(assets.open(any())).thenReturn(ByteArrayInputStream("io.posthog.proguard.mapid=map-id".toByteArray()))
        val config = config().apply { captureApplicationLifecycleEvents = false }
        setup(config)

        verifyPackageInfoCalls(0)
        assertEquals("map-id", config.releaseIdentifier)
        config.context!!.getStaticContext()
        config.context!!.getStaticContext()
        verifyPackageInfoCalls(1)
    }

    @Test
    fun `unavailable package info is not queried again by other consumers`() {
        stubPackageInfo(null)
        val config = config()
        setup(config)
        config.context!!.getStaticContext()
        config.integrations.filterIsInstance<PostHogLifecycleObserverIntegration>().single().onStart(mock<LifecycleOwner>())

        verifyPackageInfoCalls(1)
        assertEquals("com.package@+0", config.releaseIdentifier)
    }

    @Test
    fun `new setup resolves new package info and cache directory`() {
        val first = config()
        setup(first)
        clients.removeAt(0).close()
        val nextCacheDir = tmpDir.newFolder()
        whenever(app.cacheDir).thenReturn(nextCacheDir)
        stubPackageInfo(
            PackageInfo().apply {
                packageName = "com.package"
                versionName = "2.0.0"
                @Suppress("DEPRECATION")
                versionCode = 2
            },
        )
        val second = config()
        setup(second)
        second.context!!.getStaticContext()

        verifyPackageInfoCalls(2)
        verify(app, times(2)).cacheDir
        assertEquals("com.package@2.0.0+2", second.releaseIdentifier)
        assertEquals(File(nextCacheDir, "posthog-disk-queue").absolutePath, second.storagePrefix)
    }
}
