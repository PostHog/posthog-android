package com.posthog.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.posthog.PostHog
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

public fun createPostHogFake(): PostHogFake {
    val fake = PostHogFake()
    PostHog.overrideSharedInstance(fake)
    return fake
}
