package com.posthog.android.surveys.compose.internal

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Hosts the survey composition and reports a failure of its first composition to [onFailure]
 * instead of letting it crash the host app.
 *
 * A `ComposeView` composes when its window attaches, on a later frame and outside any caller's
 * try/catch. An incompatible host Compose version can then throw a linkage [Error] (e.g.
 * `NoSuchMethodError`) straight to the main looper. The inner view defers the first composition
 * to its measure pass, and this container catches what that measure pass throws. After a failure
 * the content is replaced with nothing, so later frames do not throw again.
 */
internal class SurveyComposeContainer(
    context: Context,
    private val content: @Composable () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : FrameLayout(context) {
    private var failed = false

    private val composeView =
        object : AbstractComposeView(context) {
            override val shouldCreateCompositionOnAttachedToWindow: Boolean
                get() = false

            @Composable
            override fun Content() {
                if (!failed) content()
            }
        }.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    init {
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } catch (t: Throwable) {
            setMeasuredDimension(
                getDefaultSize(0, widthMeasureSpec),
                getDefaultSize(0, heightMeasureSpec),
            )
            if (!failed) {
                failed = true
                onFailure(t)
            }
        }
    }

    fun disposeComposition() {
        composeView.disposeComposition()
    }
}
