package com.posthog.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.posthog.PostHog
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

public fun createPostHogFake(): PostHogFake {
    val fake = PostHogFake()
    PostHog.overrideSharedInstance(fake)
    return fake
}
