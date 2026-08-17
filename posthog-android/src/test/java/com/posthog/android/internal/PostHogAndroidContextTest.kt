package com.posthog.android.internal

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.mockAppInfo
import com.posthog.android.mockDisplayMetrics
import com.posthog.android.mockPackageInfo
import com.posthog.android.mockTelephone
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
internal class PostHogAndroidContextTest {
    private val context = mock<Context>()
    private lateinit var config: PostHogAndroidConfig

    private fun getSut(networkProperties: Map<String, Any> = emptyMap()): PostHogAndroidContext {
        config = PostHogAndroidConfig(API_KEY)
        return PostHogAndroidContext(context, config) { networkProperties }
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

    @Test
    fun `returns sdk info`() {
        val sut = getSut()
        val sdkInfo = sut.getSdkInfo()

        assertEquals(config.sdkName, sdkInfo["\$lib"])
        assertEquals(config.sdkVersion, sdkInfo["\$lib_version"])
    }

    @Test
    fun `returns dynamic context`() {
        val sut = getSut()

        context.mockTelephone()
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
}
