package com.posthog.android.internal.errortracking

import android.content.Context
import android.content.SharedPreferences

internal fun Context.nativeCrashPreferences(): SharedPreferences = getSharedPreferences("posthog-native-crash", Context.MODE_PRIVATE)
