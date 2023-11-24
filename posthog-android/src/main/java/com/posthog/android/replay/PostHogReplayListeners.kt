package com.posthog.android.replay

import android.content.Context
import android.view.View
import com.posthog.PostHogIntegration
import com.posthog.android.internal.displayMetrics
import com.posthog.android.replay.internal.NextDrawListener
import com.posthog.android.replay.internal.NextDrawListener.Companion.onNextDraw
import com.posthog.android.replay.internal.RRDomContentLoadedEvent
import com.posthog.android.replay.internal.RREvent
import com.posthog.android.replay.internal.RRLoadedEvent
import com.posthog.android.replay.internal.RRMetaEvent
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.OnTouchEventListener
import curtains.onDecorViewReady
import curtains.phoneWindow
import curtains.touchEventInterceptors
import curtains.windowAttachCount
import java.util.WeakHashMap

public class PostHogReplayListeners(private val context: Context): PostHogIntegration {

    private val decorViews = WeakHashMap<View, NextDrawListener>()
    private val events = mutableListOf<RREvent>()

    override fun install() {
        Curtains.onRootViewsChangedListeners += OnRootViewsChangedListener { view, added ->
            if (added) {
                val startTimeMs = System.currentTimeMillis()

                if (events.isEmpty()) {
                    events.add(RRDomContentLoadedEvent(startTimeMs))
                    events.add(RRLoadedEvent(startTimeMs))
                }

                view.phoneWindow?.let { window ->
                    if (view.windowAttachCount == 0) {
                        window.onDecorViewReady { decorView ->
                            val displayMetrics = context.displayMetrics()
                            events.add(RRMetaEvent(window.attributes.title.toString().substringAfter("/"),
                                width = displayMetrics.widthPixels,
                                height = displayMetrics.heightPixels,
                                startTimeMs))

                            val hasDecorView = decorViews.containsKey(decorView)
                            if (!hasDecorView) {
                                val listener = decorView.onNextDraw {
                                    print("onNextDraw")
                                    generateSnapshot()
                                }
                                // TODO: check if WeakHashMap still works
                                decorViews[decorView] = listener
                            }
                        }

                        window.touchEventInterceptors += OnTouchEventListener { motionEvent ->
                            // TODO: add touch events
                        }
                    }
                }
            } else {
                view.phoneWindow?.let { window ->
                    window.peekDecorView()?.let { decorView ->
                        decorViews[decorView]?.let { listener ->
                            decorView.viewTreeObserver.removeOnDrawListener(listener)
                            decorViews.remove(decorView)
                        }
                    }
                }
                // TODO: flush and start full snapshot?
            }
        }
    }

    private fun generateSnapshot() {
        val currentDecorViews = decorViews.keys
        if (currentDecorViews.isNotEmpty()) {

        }
    }
}
