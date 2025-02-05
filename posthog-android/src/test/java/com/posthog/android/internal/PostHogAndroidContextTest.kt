@file:Suppress("DEPRECATION")

package com.posthog.android.internal

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.mockAppInfo
import com.posthog.android.mockDisplayMetrics
import com.posthog.android.mockGetNetworkInfo
import com.posthog.android.mockPackageInfo
import com.posthog.android.mockPermission
import com.posthog.android.mockTelephone
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidContextTest {
    private val context = mock<Context>()
    private lateinit var config: PostHogAndroidConfig

    private fun getSut(): PostHogAndroidContext {
        config = PostHogAndroidConfig(API_KEY)
        return PostHogAndroidContext(context, config)
    }

    @BeforeTest
    fun `set up`() {
        System.setProperty("http.agent", "value")
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
    }

    fun `returns sdk info`() {
        val sut = getSut()
        val sdkInfo = sut.getSdkInfo()

        assertEquals(config.sdkName, sdkInfo["\$lib"])
        assertEquals(config.sdkVersion, sdkInfo["\$lib_version"])
    }

    @Test
    fun `returns dynamic context`() {
        val sut = getSut()

        val cm = mockPermission(context)
        mockGetNetworkInfo(cm, ConnectivityManager.TYPE_WIFI)
        context.mockTelephone()
        val dynamicContext = sut.getDynamicContext()

        // its dynamic
        assertNotNull(dynamicContext["\$locale"])
        assertEquals("value", dynamicContext["\$user_agent"])
        assertNotNull(dynamicContext["\$timezone"])

        assertEquals("name", dynamicContext["\$network_carrier"])
    }

    private fun executeNetworkTest(
        networkType: Int = ConnectivityManager.TYPE_WIFI,
        isConnected: Boolean = true,
    ): Map<String, Any> {
        val sut = getSut()
        val cm = mockPermission(context)
        mockGetNetworkInfo(cm, networkType, isConnected = isConnected)
        return sut.getDynamicContext()
    }

    @Test
    fun `sets network wifi connected`() {
        val dynamicContext = executeNetworkTest()

        assertTrue(dynamicContext["\$network_wifi"] as Boolean)
    }

    @Test
    fun `sets network wifi not connected`() {
        val dynamicContext = executeNetworkTest(isConnected = false)

        assertFalse(dynamicContext["\$network_wifi"] as Boolean)
    }

    @Test
    fun `sets network bluetooth connected`() {
        val dynamicContext = executeNetworkTest(networkType = ConnectivityManager.TYPE_BLUETOOTH)

        assertTrue(dynamicContext["\$network_bluetooth"] as Boolean)
    }

    @Test
    fun `sets network bluetooth not connected`() {
        val dynamicContext = executeNetworkTest(isConnected = false, networkType = ConnectivityManager.TYPE_BLUETOOTH)

        assertFalse(dynamicContext["\$network_bluetooth"] as Boolean)
    }

    @Test
    fun `sets network cellular connected`() {
        val dynamicContext = executeNetworkTest(networkType = ConnectivityManager.TYPE_MOBILE)

        assertTrue(dynamicContext["\$network_cellular"] as Boolean)
    }

    @Test
    fun `sets network cellular not connected`() {
        val dynamicContext = executeNetworkTest(isConnected = false, networkType = ConnectivityManager.TYPE_MOBILE)

        assertFalse(dynamicContext["\$network_cellular"] as Boolean)
    }
}
