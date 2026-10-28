package com.posthog.android.replay

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.android.PROJECT_TOKEN
import com.posthog.android.PostHogAndroidConfig
import com.posthog.android.internal.MainHandler
import com.posthog.android.replay.internal.WindowDrawState
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Golden images exercise native View mask discovery and the production screenshot painter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], qualifiers = "en-rUS-w320dp-h400dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PostHogReplayMaskingSnapshotsTest {
    @Test
    fun `default masking covers text inputs passwords and raster images`() =
        snapshot("native-defaults", "Default text and image masking") { }

    @Test
    fun `password remains masked when global text and image masking are disabled`() =
        snapshot("globals-disabled", "Global masks off; password still masked") {
            config.sessionReplayConfig.maskAllTextInputs = false
            config.sessionReplayConfig.maskAllImages = false
        }

    @Test
    fun `explicit mask covers a container with global masks disabled`() =
        snapshot("explicit-container", "Explicit container mask; global masks off") {
            config.sessionReplayConfig.maskAllTextInputs = false
            config.sessionReplayConfig.maskAllImages = false
            content.tag = "ph-no-capture"
        }

    @Test
    fun `explicit unmask exempts ordinary text and image leaves`() =
        snapshot("unmasked-leaves", "Unmasked leaves; password stays masked") {
            name.tag = "ph-no-mask"
            email.tag = "ph-no-mask"
            image.tag = "ph-no-mask"
        }

    @Test
    fun `masked parent covers an unmasked descendant`() =
        snapshot("masked-parent", "Parent mask covers an unmasked child") {
            content.tag = "ph-no-capture"
            name.tag = "ph-no-mask"
        }

    @Test
    fun `scrolled masks follow clipped content`() =
        snapshot("scrolled-content", "Content scrolled up by 40 pixels") {
            content.scrollTo(0, 40)
        }

    @Test
    fun `half resolution masks scale with screenshot pixels`() = snapshot("half-resolution", "Half-resolution screenshot", scale = 0.5f) { }

    @Test
    fun `dialog mask geometry stays in its own window`() = snapshot("dialog-window", "Dialog-local mask coordinates", dialog = true) { }

    private class Fixture(val activity: Activity) {
        val config = PostHogAndroidConfig(PROJECT_TOKEN)
        val content = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(230, 238, 248)) }
        val name = TextView(activity).apply { text = "Ada Lovelace" }
        val email =
            EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setText("ada@example.test")
                isCursorVisible = false
                isFocusable = false
            }
        val password =
            EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("synthetic-secret")
                isCursorVisible = false
                isFocusable = false
            }
        val imageBitmap =
            Bitmap.createBitmap(280, 100, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(75, 145, 210))
                val paint = Paint().apply { color = Color.rgb(245, 175, 65) }
                Canvas(this).drawCircle(140f, 50f, 32f, paint)
            }
        val image = ImageView(activity).apply { setImageBitmap(imageBitmap) }

        init {
            listOf(name, email, password).forEachIndexed { index, view ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f)
                view.typeface = Typeface.MONOSPACE
                view.setTextColor(Color.rgb(25, 35, 50))
                view.setBackgroundColor(Color.WHITE)
                view.setPadding(8, 8, 8, 8)
                view.includeFontPadding = false
                content.addView(
                    view,
                    FrameLayout.LayoutParams(280, 48).apply {
                        leftMargin = 20
                        topMargin = 20 + index * 64
                    },
                )
            }
            content.addView(
                image,
                FrameLayout.LayoutParams(280, 100).apply {
                    leftMargin = 20
                    topMargin = 224
                },
            )
        }
    }

    private fun snapshot(
        name: String,
        description: String,
        scale: Float = 1f,
        dialog: Boolean = false,
        configure: Fixture.() -> Unit,
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        controller.setup()
        val fixture = Fixture(controller.get())
        val modal = if (dialog) Dialog(fixture.activity) else null
        try {
            fixture.configure()
            val window = modal?.window ?: fixture.activity.window
            window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
            if (modal != null) {
                modal.setContentView(fixture.content)
                modal.show()
            } else {
                fixture.activity.setContentView(fixture.content)
            }
            shadowOf(Looper.getMainLooper()).idle()
            val root = window.decorView
            // Robolectric does not propagate ViewRootImpl window visibility into AttachInfo.
            val attachInfo = ReflectionHelpers.getField<Any>(root, "mAttachInfo")
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 320, 400)
            fixture.content.layout(0, 0, 320, 400)
            val sut = PostHogReplayIntegration(fixture.activity, fixture.config, MainHandler())
            val original = Bitmap.createBitmap((320 * scale).toInt(), (400 * scale).toInt(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(original)
                canvas.scale(scale, scale)
                root.draw(canvas)
                val masked = assertNotNull(original.copy(Bitmap.Config.ARGB_8888, true))
                try {
                    // Invoke the same collection + painter used by screenshot capture, without
                    // PixelCopy timing or lossy encoding. Both capture paths share that painter.
                    val painted =
                        ReflectionHelpers.callInstanceMethod<Boolean>(
                            sut,
                            "maskLegacyScreenshot",
                            ReflectionHelpers.ClassParameter.from(View::class.java, root),
                            ReflectionHelpers.ClassParameter.from(Bitmap::class.java, masked),
                            ReflectionHelpers.ClassParameter.from(WindowDrawState::class.java, WindowDrawState()),
                            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 320),
                            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 400),
                        )
                    assertTrue(painted, "SDK rejected the fixture's mask geometry")
                    assertFalse(original.sameAs(masked), "Fixture must contain a visible mask")
                    assertGolden(name, description, original, masked)
                } finally {
                    masked.recycle()
                }
            } finally {
                original.recycle()
            }
        } finally {
            modal?.dismiss()
            controller.pause().stop().destroy()
            fixture.imageBitmap.recycle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun assertGolden(
        name: String,
        description: String,
        original: Bitmap,
        masked: Bitmap,
    ) {
        val actual = Bitmap.createBitmap(original.width * 2 + 24, original.height + 64, Bitmap.Config.ARGB_8888)
        try {
            actual.eraseColor(Color.WHITE)
            val canvas = Canvas(actual)
            val text =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                }
            canvas.drawText(description, 8f, 18f, text)
            canvas.drawText("Original", 8f, 44f, text)
            canvas.drawText("SDK masked", original.width + 16f, 44f, text)
            canvas.drawBitmap(original, 8f, 56f, null)
            canvas.drawBitmap(masked, original.width + 16f, 56f, null)
            val resource = "snapshots/replay-masking/$name.png"
            if (System.getenv("UPDATE_MASKING_SNAPSHOTS") == "1") {
                writePng(File("src/test/resources/$resource"), actual)
                return
            }
            val failures = File("build/reports/replay-masking-snapshots/$name")
            val expectedStream = javaClass.classLoader!!.getResourceAsStream(resource)
            if (expectedStream == null) {
                writePng(File(failures, "actual.png"), actual)
                fail("Missing $resource. Actual: ${failures.absolutePath}. Run make recordMaskingSnapshots and review the images.")
            }
            val expected = expectedStream.use { assertNotNull(BitmapFactory.decodeStream(it), "Cannot decode $resource") }
            try {
                if (expected.sameAs(actual)) return
                writePng(File(failures, "expected.png"), expected)
                writePng(File(failures, "actual.png"), actual)
                val diff = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
                try {
                    for (y in 0 until actual.height) {
                        for (x in 0 until actual.width) {
                            val matches = x < expected.width && y < expected.height && expected.getPixel(x, y) == actual.getPixel(x, y)
                            diff.setPixel(x, y, if (matches) Color.WHITE else Color.MAGENTA)
                        }
                    }
                    writePng(File(failures, "diff.png"), diff)
                } finally {
                    diff.recycle()
                }
                fail("Masking snapshot $name changed. Expected, actual and diff: ${failures.absolutePath}")
            } finally {
                expected.recycle()
            }
        } finally {
            actual.recycle()
        }
    }

    private fun writePng(
        file: File,
        bitmap: Bitmap,
    ) {
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
