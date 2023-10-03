@file:Suppress("DEPRECATION")

package com.posthog.android

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import com.posthog.PostHog
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

public const val apiKey: String = "_6SG-F7I1vCuZ-HdJL3VZQqjBlaSb1_20hDPwqMNnGI"

public fun mockActivityUri(uri: String): Activity {
    val activity = mock<Activity>()
    val intent = Intent().apply {
        data = Uri.parse(uri)
    }
    whenever(activity.intent).thenReturn(intent)
    return activity
}

public fun mockScreenTitle(throws: Boolean): Activity {
    val activity = mock<Activity>()
    val pm = mock<PackageManager>()
    val ac = mock<ActivityInfo>()
    whenever(ac.loadLabel(any())).thenReturn("Title")
    if (throws) {
        whenever(pm.getActivityInfo(any(), any<Int>())).thenThrow(PackageManager.NameNotFoundException())
    } else {
        whenever(pm.getActivityInfo(any(), any<Int>())).thenReturn(ac)
    }
    val component = mock<ComponentName>()
    whenever(activity.componentName).thenReturn(component)
    whenever(activity.packageManager).thenReturn(pm)
    return activity
}

public fun Context.mockPackageInfo(name: String = "1.0.0", code: Int = 1) {
    val pm = mock<PackageManager>()
    whenever(packageManager).thenReturn(pm)
    whenever(packageName).thenReturn("com.package")
    val pi = PackageInfo()
    pi.versionName = name
    pi.versionCode = code
    pi.packageName = "com.package"

    whenever(pm.getPackageInfo(any<String>(), any<Int>())).thenReturn(pi)
}

public fun Context.mockAppInfo() {
    val ap = mock<ApplicationInfo>()
    whenever(applicationInfo).thenReturn(ap)
    whenever(ap.loadLabel(any())).thenReturn("Title")
}

public fun Context.mockDisplayMetrics() {
    val res = mock<Resources>()
    whenever(resources).thenReturn(res)
    val dm = DisplayMetrics().apply {
        density = 1f
        heightPixels = 100
        widthPixels = 150
    }
    whenever(res.displayMetrics).thenReturn(dm)
}

public fun mockContextAppStart(context: Context, tmpDir: TemporaryFolder) {
    val app = mock<Application>()
    whenever(context.applicationContext).thenReturn(app)
    whenever(app.getDir(any(), any())).thenReturn(tmpDir.newFolder())
    whenever(app.cacheDir).thenReturn(tmpDir.newFolder())
    val sharedPreferences = mock<SharedPreferences>()
    whenever(app.getSharedPreferences(any(), any())).thenReturn(sharedPreferences)
}

public fun mockPermission(context: Context, permission: Int = PackageManager.PERMISSION_GRANTED): ConnectivityManager {
    val cm = mock<ConnectivityManager>()
    whenever(context.getSystemService(any())).thenReturn(cm)
    whenever(context.checkPermission(any(), any(), any())).thenReturn(permission)
    return cm
}

public fun mockNetworkInfo(connectivityManager: ConnectivityManager, hasNetwork: Boolean = true, isConnected: Boolean = true) {
    if (hasNetwork) {
        val ni = mock<NetworkInfo>()
        whenever(connectivityManager.activeNetworkInfo).thenReturn(ni)
        whenever(ni.isConnected).thenReturn(isConnected)
    }
}

public fun Context.mockTelephone() {
    val tm = mock<TelephonyManager>()
    whenever(getSystemService(any())).thenReturn(tm)
    whenever(tm.networkOperatorName).thenReturn("name")
}

public fun mockGetNetworkInfo(connectivityManager: ConnectivityManager, networkType: Int, isConnected: Boolean = true) {
    val ni = mock<NetworkInfo>()
    whenever(connectivityManager.getNetworkInfo(networkType)).thenReturn(ni)
    whenever(ni.isConnected).thenReturn(isConnected)
}

public fun createPostHogFake(): PostHogFake {
    val fake = PostHogFake()
    PostHog.overrideSharedInstance(fake)
    return fake
}
