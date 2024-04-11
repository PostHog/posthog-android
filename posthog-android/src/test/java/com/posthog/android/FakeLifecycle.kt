package com.posthog.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver

internal class FakeLifecycle(override val currentState: State) : Lifecycle() {
    var observers = 0

    override fun addObserver(observer: LifecycleObserver) {
        observers++
    }

    override fun removeObserver(observer: LifecycleObserver) {
        observers--
    }
}
