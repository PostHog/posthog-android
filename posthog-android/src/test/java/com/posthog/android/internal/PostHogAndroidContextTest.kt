package com.posthog.android.internal

import android.content.Context
import android.content.pm.InstallSourceInfo
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.mockAppInfo
import com.posthog.android.mockDisplayMetrics
import com.posthog.android.mockPackageInfo
import com.posthog.android.mockTelephone
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidContextTest {
    private val context = mock<Context>()
    private lateinit var config: PostHogAndroidConfig
    private val originalUserAgent = System.getProperty("http.agent")
    private val pendingTasks = mutableListOf<Runnable>()

    private fun getSut(networkProperties: Map<String, Any> = emptyMap()): PostHogAndroidContext {
        config = PostHogAndroidConfig(PROJECT_TOKEN)
        return PostHogAndroidContext(
            context,
            config,
            networkPropertiesProvider = { networkProperties },
            networkCarrierExecutor = { pendingTasks.add(it) },
        )
    }

    private fun runPendingTasks() {
        val tasks = pendingTasks.toList()
        pendingTasks.clear()
        tasks.forEach { it.run() }
    }

    @BeforeTest
    fun `set up`() {
        System.setProperty("http.agent", "value")
    }

    @AfterTest
    fun tearDown() {
        if (originalUserAgent == null) {
            System.clearProperty("http.agent")
        } else {
            System.setProperty("http.agent", originalUserAgent)
        }
    }

    @Test
    fun `returns static context`() {
        val sut = getSut()

        context.mockDisplayMetrics()
        context.mockPackageInfo()
        context.mockAppInfo()

        val staticContext = sut.getStaticContext()

        assertEquals(1f, staticContext["\$screen_density"])
        assertEquals(100, staticContext["\$screen_height"])
        assertEquals(150, staticContext["\$screen_width"])

        assertEquals("1.0.0", staticContext["\$app_version"])
        assertEquals("com.package", staticContext["\$app_namespace"])
        assertEquals(1L, staticContext["\$app_build"])
        assertEquals("Title", staticContext["\$app_name"])

        // its dynamic
        assertNotNull(staticContext["\$device_manufacturer"])
        assertNotNull(staticContext["\$device_model"])
        assertNotNull(staticContext["\$device_name"])
        assertEquals("Mobile", staticContext["\$device_type"])

        assertEquals("Android", staticContext["\$os_name"])
        // its dynamic
        assertNotNull(staticContext["\$os_version"])

        assertNotNull(staticContext["\$is_emulator"])
        assertNotNull(staticContext["\$is_sideloaded"])
    }

    @Suppress("DEPRECATION")
    private fun mockInstaller(
        installer: String? = null,
        error: Throwable? = null,
    ) {
        context.mockPackageInfo()
        val stub = whenever(context.packageManager.getInstallerPackageName(any()))
        if (error != null) stub.thenThrow(error) else stub.thenReturn(installer)
    }

    private fun getStaticContext(): Map<String, Any> {
        context.mockDisplayMetrics()
        context.mockAppInfo()
        return getSut().getStaticContext()
    }

    @Test
    @Config(sdk = [28])
    fun `reports sideloaded when the installer is unknown`() {
        mockInstaller()

        assertEquals(true, getStaticContext()["\$is_sideloaded"])
    }

    @Test
    @Config(sdk = [28])
    fun `reports sideloaded when the installer is not a store`() {
        mockInstaller("com.google.android.packageinstaller")

        assertEquals(true, getStaticContext()["\$is_sideloaded"])
    }

    @Test
    @Config(sdk = [28])
    fun `reports not sideloaded when installed from a store`() {
        mockInstaller("com.android.vending")

        assertEquals(false, getStaticContext()["\$is_sideloaded"])
    }

    @Test
    @Config(sdk = [30])
    fun `reads the install source on API 30 and above`() {
        context.mockPackageInfo()
        val installSourceInfo = mock<InstallSourceInfo>()
        whenever(installSourceInfo.installingPackageName).thenReturn("com.android.vending")
        whenever(context.packageManager.getInstallSourceInfo(any())).thenReturn(installSourceInfo)

        assertEquals(false, getStaticContext()["\$is_sideloaded"])
    }

    @Test
    @Config(sdk = [28])
    fun `reports not sideloaded on an emulator`() {
        mockInstaller()
        ShadowBuild.setFingerprint("generic")

        assertEquals(false, getStaticContext()["\$is_sideloaded"])
    }

    @Test
    @Config(sdk = [28])
    fun `reports not sideloaded when the package manager throws`() {
        mockInstaller(error = RuntimeException())

        assertEquals(false, getStaticContext()["\$is_sideloaded"])
    }

    @Test
    fun `returns sdk info`() {
        val sut = getSut()
        val sdkInfo = sut.getSdkInfo()

        assertEquals(config.sdkName, sdkInfo["\$lib"])
        assertEquals(config.sdkVersion, sdkInfo["\$lib_version"])
    }

    @Test
    fun `returns dynamic context`() {
        context.mockTelephone()
        val sut = getSut()
        runPendingTasks()

        val dynamicContext = sut.getDynamicContext()

        // its dynamic
        assertNotNull(dynamicContext["\$locale"])
        assertEquals("value", dynamicContext["\$user_agent"])
        assertEquals("value", dynamicContext["\$raw_user_agent"])
        assertNotNull(dynamicContext["\$timezone"])

        assertEquals("name", dynamicContext["\$network_carrier"])
    }

    @Test
    fun `returns network properties from snapshot`() {
        val sut =
            getSut(
                mapOf(
                    "\$network_wifi" to true,
                    "\$network_bluetooth" to false,
                    "\$network_cellular" to false,
                ),
            )

        val dynamicContext = sut.getDynamicContext()

        assertEquals(true, dynamicContext["\$network_wifi"])
        assertEquals(false, dynamicContext["\$network_bluetooth"])
        assertEquals(false, dynamicContext["\$network_cellular"])
    }

    @Test
    fun `omits network properties when snapshot is empty`() {
        val dynamicContext = getSut().getDynamicContext()

        assertFalse(dynamicContext.containsKey("\$network_wifi"))
        assertFalse(dynamicContext.containsKey("\$network_bluetooth"))
        assertFalse(dynamicContext.containsKey("\$network_cellular"))
    }

    @Test
    fun `does not query ConnectivityManager`() {
        val connectivityManager = mock<ConnectivityManager>()
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)

        val sut = getSut()
        repeat(10) { sut.getDynamicContext() }

        verifyNoInteractions(connectivityManager)
    }

    @Test
    fun `reads network carrier off the caller thread`() {
        val telephonyManager = mock<TelephonyManager>()
        whenever(context.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(telephonyManager)
        whenever(telephonyManager.networkOperatorName).thenReturn("name")

        val sut = getSut()
        repeat(10) {
            assertFalse(sut.getDynamicContext().containsKey("\$network_carrier"))
        }

        verifyNoInteractions(telephonyManager)
        assertEquals(1, pendingTasks.size)

        runPendingTasks()

        assertEquals("name", sut.getDynamicContext()["\$network_carrier"])
    }

    @Test
    fun `keeps network carrier and refreshes again after a failed read`() {
        val telephonyManager = mock<TelephonyManager>()
        whenever(context.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(telephonyManager)
        whenever(telephonyManager.networkOperatorName).thenReturn("name")

        val sut = getSut()
        runPendingTasks()
        sut.getDynamicContext()

        whenever(telephonyManager.networkOperatorName).thenThrow(SecurityException())
        runPendingTasks()

        assertEquals("name", sut.getDynamicContext()["\$network_carrier"])
        assertEquals(1, pendingTasks.size)
    }
}
