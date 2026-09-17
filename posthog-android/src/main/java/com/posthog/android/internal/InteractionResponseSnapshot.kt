package com.posthog.android.internal

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.webkit.WebView
import android.widget.Checkable
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import curtains.phoneWindow
import java.security.MessageDigest
import java.security.SecureRandom

private const val ANDROID_WIDGET_PACKAGE_PREFIX = "android.widget."
private const val ANDROID_VIEW_PACKAGE_PREFIX = "android.view."
private const val ANDROID_INTERNAL_PACKAGE_PREFIX = "com.android.internal."
private const val APPCOMPAT_WIDGET_PACKAGE_PREFIX = "androidx.appcompat.widget."
private const val MATERIAL_PACKAGE_PREFIX = "com.google.android.material."

/** Ephemeral salted digest, never serialized. No text, nodes or Views survive a snapshot walk. */
internal class InteractionResponseSnapshot {
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun take(root: View): ByteArray? =
        try {
            check(root.phoneWindow?.attributes?.flags?.and(FLAG_SECURE) != FLAG_SECURE)
            val sink = InteractionResponseDigest(salt)
            val excludedLayouts = mutableSetOf<androidx.compose.ui.layout.LayoutInfo>()
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            origin.forEach { sink.number(it) }
            val transform = FloatArray(9)

            fun visit(
                view: View,
                depth: Int,
                excluded: Boolean,
            ) {
                // Compose lazily adds these native drawing-only layers for pressed feedback.
                // Their creation/removal and animation are not meaningful UI responses.
                if (view.javaClass.name == COMPOSE_RIPPLE_CONTAINER_CLASS_NAME ||
                    view.javaClass.name == COMPOSE_RIPPLE_HOST_VIEW_CLASS_NAME
                ) {
                    return
                }
                sink.node(depth)
                sink.number(System.identityHashCode(view))
                sink.number(view.left)
                sink.number(view.top)
                sink.number(view.right)
                sink.number(view.bottom)
                view.matrix.getValues(transform)
                transform.forEach { sink.number(it.toBits()) }
                sink.number(view.scrollX)
                sink.number(view.scrollY)
                sink.number(view.visibility)
                sink.number(if (view.isEnabled) 1 else 0)
                sink.number(if (view.isSelected) 1 else 0)
                sink.number(if (view.isActivated) 1 else 0)
                sink.number(if (view.hasFocus()) 1 else 0)
                val excludedInterop =
                    if (excludedLayouts.isNotEmpty() && view.javaClass.name.startsWith(COMPOSE_VIEW_INTEROP_PACKAGE_PREFIX)) {
                        val layout = ComposeInteractionTargetResolver.interopLayoutInfo(listOf(view))
                        checkNotNull(layout)
                        layout in excludedLayouts
                    } else {
                        false
                    }
                val ignored =
                    excluded || excludedInterop || view is EditText ||
                        (view is TextView && view.transformationMethod is android.text.method.PasswordTransformationMethod) ||
                        view.isInteractionIgnored()
                sink.number(if (ignored) 1 else 0)
                if (!ignored) {
                    check(view !is SurfaceView && view !is TextureView && view !is WebView)
                    val name = view.javaClass.name
                    check(
                        name.startsWith(ANDROID_WIDGET_PACKAGE_PREFIX) || name.startsWith(ANDROID_VIEW_PACKAGE_PREFIX) ||
                            name.startsWith(ANDROID_INTERNAL_PACKAGE_PREFIX) || name.startsWith(APPCOMPAT_WIDGET_PACKAGE_PREFIX) ||
                            name.startsWith(COMPOSE_PLATFORM_PACKAGE_PREFIX) || name.startsWith(COMPOSE_VIEW_INTEROP_PACKAGE_PREFIX) ||
                            name.startsWith(MATERIAL_PACKAGE_PREFIX),
                    )
                    sink.text(view.contentDescription)
                    if (android.os.Build.VERSION.SDK_INT >= 30) sink.text(view.stateDescription)
                    sink.number(System.identityHashCode(view.background))
                    if (view is android.widget.ImageView) sink.number(System.identityHashCode(view.drawable))
                    if (view is TextView) sink.text(view.text)
                    if (view is Checkable) sink.number(if (view.isChecked) 1 else 0)
                    if (view is ProgressBar) sink.number(view.progress)
                    // Drawable state/pressed/alpha are deliberately absent: ripples are not responses.
                    if (name == ANDROID_COMPOSE_VIEW_CLASS_NAME) {
                        // Transfer semantic exclusions before traversing the separate native interop tree.
                        excludedLayouts.addAll(ComposeInteractionResponseSnapshot.append(view, sink, depth + 1))
                    }
                }
                if (view is ViewGroup) {
                    check(view.childCount <= MAX_INTERACTION_NODES - sink.nodes)
                    for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1, ignored)
                }
            }
            visit(root, 0, false)
            sink.finish()
        } catch (_: Throwable) {
            null
        }
}

internal class InteractionResponseDigest(salt: ByteArray) {
    private val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }
    var nodes: Int = 0
        private set
    private var characters = 0

    fun node(depth: Int) {
        check(++nodes <= MAX_INTERACTION_NODES && depth < MAX_INTERACTION_DEPTH)
    }

    fun number(value: Int) {
        repeat(4) { digest.update((value ushr (it * 8)).toByte()) }
    }

    fun text(value: CharSequence?) {
        val length = value?.length ?: 0
        // Count field delimiters too: huge lists of empty semantic strings are still bounded.
        check(length < 16384 - characters)
        characters += length + 1
        number(length)
        if (value != null) for (i in 0 until length) number(value[i].code)
    }

    fun finish(): ByteArray = digest.digest()
}
