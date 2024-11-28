package com.posthog

public object ScreenTracker {
    @Volatile private lateinit var currentScreen: String

    public fun setCurrentScreen(screenName: String) {
        currentScreen = screenName
    }

    public fun getCurrentScreenName(): String {
        return currentScreen
    }
}
