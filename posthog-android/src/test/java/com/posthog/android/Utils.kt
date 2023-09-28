package com.posthog.android

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
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

@Suppress("DEPRECATION")
public fun Context.mockPackageInfo(name: String = "1.0.0", code: Int = 1) {
    val pm = mock<PackageManager>()
    whenever(packageManager).thenReturn(pm)
    whenever(packageName).thenReturn("test")
    val pi = PackageInfo()
    pi.versionName = name
    pi.versionCode = code

    whenever(pm.getPackageInfo(any<String>(), any<Int>())).thenReturn(pi)
}

public fun mockContextAppStart(context: Context, tmpDir: TemporaryFolder) {
    val app = mock<Application>()
    whenever(context.applicationContext).thenReturn(app)
    whenever(app.getDir(any(), any())).thenReturn(tmpDir.newFolder())
    whenever(app.cacheDir).thenReturn(tmpDir.newFolder())
    val sharedPreferences = mock<SharedPreferences>()
    whenever(app.getSharedPreferences(any(), any())).thenReturn(sharedPreferences)
}

public fun createPostHogFake(): PostHogFake {
    val fake = PostHogFake()
    PostHog.overrideSharedInstance(fake)
    return fake
}
