package com.posthog.android.sample

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/** A recognizable, synthetic checkout for inspecting screenshot-scene recordings. */
internal class CheckoutProof(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val ink = Color.rgb(26, 37, 54)
    private val muted = Color.rgb(103, 116, 135)
    private val navy = Color.rgb(29, 47, 79)
    private val accent = Color.rgb(55, 108, 177)
    private val surface = Color.WHITE

    fun show() {
        val screen =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(245, 247, 251))
            }
        val header =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(26), dp(24), dp(28))
                setBackgroundColor(navy)
            }
        header.stack(label("NORTHSTAR MARKET", 12f, Color.rgb(170, 205, 246), true))
        header.stack(label("Checkout", 30f, surface, true), top = 7)
        header.stack(label("Your order is almost on its way", 14f, Color.rgb(214, 225, 239)), top = 4)
        screen.stack(header)

        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(16))
            }
        screen.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.stack(label("Order details", 20f, ink, true))

        val delivery = panel()
        delivery.stack(label("DELIVERY", 11f, accent, true))
        val deliveryName = label("Standard delivery", 18f, ink, true)
        delivery.stack(deliveryName, top = 6)
        val deliveryEta = label("Arrives in 3–5 business days", 13f, muted)
        delivery.stack(deliveryEta, top = 3)
        body.stack(delivery, top = 16)

        val item = panel()
        item.stack(label("YOUR ITEM", 11f, muted, true))
        val itemRow =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val productImage =
            FrameLayout(activity).apply {
                background = rounded(Color.rgb(232, 238, 248), 12)
                addView(label("🎧", 39f, ink).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            }
        itemRow.addView(productImage, LinearLayout.LayoutParams(dp(72), dp(72)))
        val productText = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        productText.stack(label("Wireless headphones", 15f, ink, true))
        productText.stack(label("Midnight blue · Qty 1", 12f, muted), top = 4)
        productText.stack(label("$89.00", 15f, ink, true), top = 7)
        itemRow.addView(productText, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(15) })
        item.stack(itemRow, top = 12)
        body.stack(item, top = 14)

        val address = panel()
        address.stack(label("DELIVER TO", 11f, muted, true))
        address.stack(
            label("Alex Example · 123 Demo Street", 14f, ink).apply { tag = "ph-no-capture" },
            top = 8,
        )
        address.stack(label("San Francisco, CA", 13f, muted), top = 3)
        body.stack(address, top = 14)

        body.stack(label("Payment summary", 17f, ink, true), top = 23)
        body.stack(summaryRow("Subtotal", "$89.00"), top = 10)
        val shippingAmount = label("FREE", 14f, accent, true)
        body.stack(summaryRow("Shipping", shippingAmount), top = 9)
        val totalAmount = label("$89.00", 18f, ink, true)
        body.stack(summaryRow("Total", totalAmount), top = 15)
        body.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))

        val sheet =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(28))
                background = rounded(surface, 24)
            }
        val handle = View(activity).apply { background = rounded(Color.rgb(198, 206, 216), 4) }
        sheet.addView(
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(handle, LinearLayout.LayoutParams(dp(38), dp(4)))
            },
            LinearLayout.LayoutParams(-1, dp(18)),
        )
        sheet.stack(label("Choose delivery", 24f, ink, true), top = 10)
        sheet.stack(label("When should we bring your order?", 14f, muted), top = 5)
        sheet.stack(deliveryOption("Standard", "3–5 business days", "FREE", false), top = 21)
        sheet.stack(deliveryOption("Express", "Tomorrow, by 8 pm", "$10.00", true), top = 10)
        sheet.stack(label("DELIVERING TO", 11f, muted, true), top = 21)
        sheet.stack(
            label("Alex Example · 123 Demo Street", 13f, ink).apply { tag = "ph-no-capture" },
            top = 7,
        )

        val dialog =
            BottomSheetDialog(activity).apply {
                setContentView(sheet)
                window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(0.48f)
            }
        Log.i("WindowCompositionProof", "checkout windowClass=${dialog.javaClass.name}")

        fun selectExpress() {
            deliveryName.text = "Express delivery"
            deliveryEta.text = "Arrives tomorrow, by 8 pm"
            shippingAmount.text = "$10.00"
            totalAmount.text = "$99.00"
            dialog.dismiss()
        }
        sheet.stack(action("Use express delivery") { selectExpress() }, top = 22)
        body.stack(action("Change delivery option") { dialog.show() }, top = 20)
        body.stack(label("Secure checkout · Demo order", 12f, muted).apply { gravity = Gravity.CENTER }, top = 13)
        activity.setContentView(screen)

        handler.postDelayed({ if (!activity.isFinishing && !dialog.isShowing) dialog.show() }, 4000)
        handler.postDelayed({ if (!activity.isFinishing && dialog.isShowing) selectExpress() }, 10000)
    }

    private fun deliveryOption(
        title: String,
        subtitle: String,
        price: String,
        selected: Boolean,
    ): View {
        val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background =
                    rounded(
                        if (selected) Color.rgb(238, 245, 255) else surface,
                        13,
                        if (selected) accent else Color.rgb(218, 224, 232),
                    )
            }
        row.addView(label(if (selected) "◉" else "○", 24f, accent), LinearLayout.LayoutParams(dp(32), -2))
        val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        copy.stack(label(title, 15f, ink, true))
        copy.stack(label(subtitle, 12f, muted), top = 3)
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(price, 13f, ink, true))
        return row
    }

    private fun summaryRow(
        name: String,
        amount: String,
    ): View = summaryRow(name, label(amount, 14f, ink))

    private fun summaryRow(
        name: String,
        amount: TextView,
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(name, 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(amount)
        }

    private fun panel(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(16), dp(17), dp(16))
            background = rounded(surface, 15)
            elevation = dp(2).toFloat()
        }

    private fun action(
        title: String,
        onClick: () -> Unit,
    ): TextView =
        label(title, 15f, surface, true).apply {
            gravity = Gravity.CENTER
            background = rounded(navy, 12)
            setOnClickListener { onClick() }
            minimumHeight = dp(53)
        }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(
        color: Int,
        radius: Int,
        border: Int? = null,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(1), border)
        }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun LinearLayout.stack(
        child: View,
        top: Int = 0,
    ) {
        addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
}
