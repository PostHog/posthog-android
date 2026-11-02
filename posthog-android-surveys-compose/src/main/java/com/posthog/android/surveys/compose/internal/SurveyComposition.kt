package com.posthog.android.surveys.compose.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.core.view.doOnAttach
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob

internal class SurveyComposition(
    context: Context,
    lifecycle: Lifecycle,
    private val onFailure: (Throwable) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var disposed = false
    private var failed = false
    private val job = SupervisorJob()
    val view = ComposeView(context)
    private val recomposer: Recomposer =
        view.createLifecycleAwareWindowRecomposer(
            job + CoroutineExceptionHandler { _, error -> fail(error) },
            lifecycle,
        )

    init {
        view.setParentCompositionContext(recomposer)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun setContent(content: @Composable () -> Unit) {
        view.doOnAttach {
            // Composition must start after attachment, inside our guard or our own recomposer.
            mainHandler.post {
                if (!disposed && !failed) {
                    try {
                        view.setContent(content)
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        job.cancel()
        recomposer.cancel()
        view.disposeComposition()
    }

    private fun fail(error: Throwable) {
        if (disposed || failed) return
        failed = true
        job.cancel()
        // Never dispose a composition while its composer is still unwinding a failure.
        mainHandler.post {
            if (!disposed) onFailure(error)
        }
    }
}

@Composable
internal fun DeferredSurveyContent(content: @Composable () -> Unit) {
    // A nested dialog initially composes during attachment, outside the recomposer's error handler.
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = true }
    if (ready) content()
}
