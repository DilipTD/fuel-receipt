package com.dilip.carobd

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small view-building toolkit (no AndroidX). */
class Ui(val a: Activity) {
    companion object {
        val BG = Color.parseColor("#101418")
        val CARD = Color.parseColor("#1C232B")
        val ACC = Color.parseColor("#2F7FD0")
        val SEL = Color.parseColor("#1F4F7A")
        val DIM = Color.parseColor("#9AA7B4")
        val GOOD = Color.parseColor("#2E9E4F")
        val WARN = Color.parseColor("#FFB300")
        val BAD = Color.parseColor("#D64545")
        val WHITE = Color.WHITE
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun eventColor(type: String) = when (type) {
            DriveEvent.EV_SUDDEN -> Color.parseColor("#FF1744")
            DriveEvent.EV_BRAKE -> Color.parseColor("#FF7043")
            DriveEvent.EV_ACC -> Color.parseColor("#FFB300")
            DriveEvent.EV_CORNER -> Color.parseColor("#AB47BC")
            DriveEvent.EV_OVERSPEED -> Color.parseColor("#E53935")
            else -> Color.parseColor("#90A4AE")
        }
    }

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun tv(t: String = "", size: Float = 14f, color: Int = WHITE, bold: Boolean = false) = TextView(a).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun mono(t: String = "", size: Float = 13f) = tv(t, size).apply {
        typeface = Typeface.MONOSPACE
        background = rounded(CARD)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextIsSelectable(true)
    }

    fun rounded(c: Int, r: Int = 10) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(r).toFloat()
    }

    fun btn(t: String, color: Int = ACC, onClick: () -> Unit) = Button(a).apply {
        text = t
        isAllCaps = false
        setTextColor(WHITE)
        textSize = 14f
        background = rounded(color)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    fun lp(top: Int = 8) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    fun row(vararg v: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x ->
            addView(x, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    fun col(padTop: Int = 4) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(padTop), dp(12), dp(28))
    }

    fun vbox() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    fun scroll(v: View) = ScrollView(a).apply {
        isFillViewport = true
        addView(v)
    }

    fun section(title: String) = tv(title, 16f, WHITE, true).apply { setPadding(0, dp(16), 0, dp(6)) }

    fun edit(hint: String, value: String, numeric: Boolean = false) = EditText(a).apply {
        setText(value)
        this.hint = hint
        setTextColor(WHITE)
        setHintTextColor(DIM)
        background = rounded(CARD, 8)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isSingleLine = true
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    fun labeled(label: String, v: View): LinearLayout = vbox().apply {
        addView(tv(label, 12f, DIM))
        addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun check(label: String, value: Boolean) = CheckBox(a).apply {
        text = label
        isChecked = value
        setTextColor(WHITE)
    }

    fun card(text: String, size: Float = 13f, color: Int = WHITE, bg: Int = CARD) = tv(text, size, color).apply {
        background = rounded(bg, 8)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()

    fun f(v: Double?, dec: Int, unit: String = "") =
        if (v == null || v.isNaN() || v.isInfinite()) "--" else "%.${dec}f%s".format(v, unit)

    fun hms(s: Double): String {
        val t = s.toLong()
        return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    fun date(t: Long, pattern: String = "dd MMM yy HH:mm") = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))

    /** Economy formatted per the user's unit choice (km/L or L/100km; kg for CNG). */
    fun econ(kmpl: Double?, unit: String = ObdManager.vehicle.spec.unit): String {
        if (kmpl == null || kmpl <= 0 || kmpl.isInfinite()) return "--"
        return if (Prefs.economyUnit == "L/100km") "%.1f %s/100km".format(100.0 / kmpl, unit)
        else "%.1f km/%s".format(kmpl, unit)
    }

    fun money(v: Double) = "₹%.0f".format(v)
}

/** A screen inside MainActivity. */
abstract class Page(val a: MainActivity) {
    val ui: Ui get() = a.ui
    open val parent: String? = null
    lateinit var view: View
    abstract fun build(): View
    open fun onShow() {}
    open fun refresh() {}
}
