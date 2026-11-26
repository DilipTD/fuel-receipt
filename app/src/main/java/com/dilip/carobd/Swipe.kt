package com.dilip.carobd

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Swipe a row to the left to reveal Delete; releasing past the threshold asks for confirmation. */
class SwipeRow(ctx: Context, private val ui: Ui, private val content: View, private val title: String,
               private val message: String, private val onDelete: () -> Unit) : FrameLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private val bg = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        background = ui.rounded(Color.parseColor("#C62F3B"), 20); alpha = 0f
        setPadding(0, 0, ui.dp(26), 0)
        addView(ui.iconView(Ic.TRASH, Color.WHITE, 22))
        addView(TextView(ctx).apply { text = "  Delete"; setTextColor(Color.WHITE); textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
    }

    init {
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        clipChildren = false
    }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, h)
        // red background follows the content height
        bg.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(content.measuredHeight, MeasureSpec.EXACTLY))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (!dragging && dx < -slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true; parent?.requestDisallowInterceptTouchEvent(true)
                    content.animate().cancel(); return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = (e.x - downX).coerceIn(-width * 0.7f, 0f)
                content.translationX = dx; bg.alpha = (abs(dx) / ui.dp(28)).coerceAtMost(1f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (content.translationX < -width * 0.33f && e.actionMasked == MotionEvent.ACTION_UP) {
                    content.animate().translationX(-width * 0.28f).setDuration(120).start()
                    AlertDialog.Builder(context).setTitle(title).setMessage(message)
                        .setPositiveButton("Delete") { _, _ ->
                            content.animate().translationX(-width.toFloat()).alpha(0f).setDuration(180).withEndAction { onDelete() }.start()
                        }
                        .setNegativeButton("Cancel", null)
                        .setOnDismissListener { if (content.alpha > 0.5f) reset() }
                        .show()
                } else reset()
                return true
            }
        }
        return dragging || super.onTouchEvent(e)
    }

    private fun reset() {
        content.animate().translationX(0f).setDuration(220).start()
        bg.animate().alpha(0f).setDuration(220).start()
    }
}

fun Ui.swipeDelete(content: View, title: String, message: String, onDelete: () -> Unit): View = SwipeRow(a, this, content, title, message, onDelete)
