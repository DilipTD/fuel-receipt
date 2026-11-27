package com.dilip.carobd

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Button that centres its icon and label together. */
class IconButton(ctx: Context) : Button(ctx) {
    override fun onDraw(canvas: Canvas) {
        val dr = compoundDrawablesRelative[0]
        if (dr != null) {
            val tw = paint.measureText(text.toString())
            val content = tw + dr.intrinsicWidth + compoundDrawablePadding
            val avail = width - paddingLeft - paddingRight
            val dx = ((avail - content) / 2f).coerceAtLeast(0f)
            canvas.save(); canvas.translate(dx, 0f); super.onDraw(canvas); canvas.restore()
        } else super.onDraw(canvas)
    }
}

/** View-building toolkit (no AndroidX): consistent cards, icon badges, tiles, switches and buttons. */
class Ui(val a: Activity) {
    companion object {
        val BG = Color.parseColor("#0A0F14")
        val CARD = Color.parseColor("#131B24")
        val STROKE = Color.parseColor("#233140")
        val ACC = Color.parseColor("#2F80ED")
        val SEL = Color.parseColor("#17406E")
        val DIM = Color.parseColor("#8E9BAA")
        val GOOD = Color.parseColor("#1FA463")
        val WARN = Color.parseColor("#FFB300")
        val BAD = Color.parseColor("#E5484D")
        val CYAN = Color.parseColor("#00D4E8")
        val VIOLET = Color.parseColor("#8E6BFF")
        val ORANGE = Color.parseColor("#FF8A3D")
        val PINK = Color.parseColor("#FF5C93")
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

        /** Symbol for a driving event. */
        fun eventIcon(type: String): Ic = when (type) {
            DriveEvent.EV_OVERSPEED -> Ic.GAUGE
            DriveEvent.EV_ACC -> Ic.BOLT
            DriveEvent.EV_BRAKE -> Ic.STOP
            DriveEvent.EV_SUDDEN -> Ic.WARN
            DriveEvent.EV_CORNER -> Ic.NAV
            DriveEvent.EV_OVERREV -> Ic.ENGINE
            DriveEvent.EV_COLDREV -> Ic.THERM
            DriveEvent.EV_IDLE -> Ic.TIMER
            else -> Ic.FLAG
        }
        /** Plain-language name used next to the symbol. */
        fun eventName(type: String): String = when (type) {
            DriveEvent.EV_ACC -> "Rapid acceleration"
            DriveEvent.EV_BRAKE -> "Rapid deceleration (hard braking)"
            DriveEvent.EV_SUDDEN -> "Sudden braking"
            else -> type
        }
        /** Green / yellow / red by speed in km/h. */
        fun speedColor3(kmh: Double): Int = when { kmh < 50 -> Color.parseColor("#2EBD85"); kmh < 80 -> Color.parseColor("#FFB020"); else -> Color.parseColor("#E5383B") }

        fun lighten(c: Int, f: Float): Int {
            val h = FloatArray(3); Color.colorToHSV(c, h); h[2] = minOf(1f, h[2] * f); h[1] = h[1] * 0.95f
            return Color.HSVToColor(h)
        }

        fun darken(c: Int, f: Float): Int {
            val h = FloatArray(3); Color.colorToHSV(c, h); h[2] *= f
            return Color.HSVToColor(h)
        }

        fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        fun clean(t: String) = t.replace(Regex("^[^A-Za-z0-9]+"), "")

        private val btnIcons = listOf(
            "disconnect" to Ic.POWER, "connect" to Ic.PLUG, "bluetooth" to Ic.BLUETOOTH, "refresh" to Ic.REFRESH,
            "stop" to Ic.STOP, "start" to Ic.PLAY, "new trip" to Ic.PLAY, "scan" to Ic.SCAN, "read dtc" to Ic.SCAN,
            "clear" to Ic.TRASH, "delete" to Ic.TRASH, "save" to Ic.SAVE, "backup" to Ic.DOWNLOAD, "export" to Ic.DOWNLOAD,
            "restore" to Ic.UPLOAD, "share" to Ic.SHARE, "add" to Ic.PLUS, "edit" to Ic.EDIT, "history" to Ic.CLOCK,
            "health" to Ic.SHIELD, "readiness" to Ic.CHECK, "freeze" to Ic.THERM, "info" to Ic.INFO, "send" to Ic.PLAY,
            "hud" to Ic.MONITOR, "analysis" to Ic.CHART, "performance" to Ic.TIMER, "launch" to Ic.FLAG,
            "braking" to Ic.STOP, "use" to Ic.CHECK, "test" to Ic.BELL, "allow" to Ic.BOLT, "mark" to Ic.CHECK,
            "calibrate" to Ic.GAUGE, "apply" to Ic.CHECK, "update" to Ic.REFRESH, "garage" to Ic.CAR, "fuel" to Ic.FUEL,
            "maint" to Ic.WRENCH, "alert" to Ic.BELL, "battery" to Ic.BATTERY, "settings" to Ic.GEAR, "about" to Ic.INFO,
            "where" to Ic.PIN, "open" to Ic.CHEV, "kml" to Ic.PIN, "gpx" to Ic.ROUTE, "csv" to Ic.DOC, "report" to Ic.DOC
        )

        private val titleIcons = listOf(
            "gps" to Ic.PIN, "screen" to Ic.MONITOR, "voice" to Ic.VOICE, "beep" to Ic.BELL, "fault" to Ic.WARN,
            "dtc" to Ic.WARN, "bluetooth" to Ic.BLUETOOTH, "alert" to Ic.BELL, "notif" to Ic.BELL, "suggest" to Ic.LEAF,
            "driving" to Ic.PULSE, "behaviour" to Ic.PULSE, "terminal" to Ic.TERMINAL, "backup" to Ic.DATABASE,
            "about" to Ic.INFO, "custom" to Ic.SLIDERS, "what's new" to Ic.FLAG, "results" to Ic.TIMER,
            "performance" to Ic.TIMER, "readiness" to Ic.CHECK, "health" to Ic.SHIELD, "battery" to Ic.BATTERY,
            "fuel" to Ic.FUEL, "maint" to Ic.WRENCH, "service" to Ic.WRENCH, "vehicle" to Ic.CAR, "garage" to Ic.CAR,
            "automat" to Ic.BOLT, "trip" to Ic.ROUTE, "log" to Ic.DOC, "history" to Ic.CLOCK, "live" to Ic.CHART,
            "adapter" to Ic.PLUG, "connect" to Ic.PLUG, "device" to Ic.BLUETOOTH, "threshold" to Ic.SLIDERS,
            "unit" to Ic.SLIDERS, "month" to Ic.CALENDAR, "route" to Ic.ROUTE, "speed" to Ic.GAUGE, "event" to Ic.WARN,
            "analysis" to Ic.CHART, "summary" to Ic.DOC, "version" to Ic.INFO
        )

        fun iconForButton(t: String): Ic? {
            val s = t.lowercase()
            if (t.trimStart().startsWith("+")) return Ic.PLUS
            return btnIcons.firstOrNull { s.contains(it.first) }?.second
        }

        fun iconForTitle(t: String): Ic? {
            val s = t.lowercase()
            return titleIcons.firstOrNull { s.contains(it.first) }?.second
        }
    }

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun tv(t: String = "", size: Float = 14f, color: Int = WHITE, bold: Boolean = false) = TextView(a).apply {
        text = Lang.t(t)
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun mono(t: String = "", size: Float = 13f) = tv(t, size).apply {
        typeface = Typeface.MONOSPACE
        background = rounded(CARD, 20)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setTextIsSelectable(true)
    }

    fun rounded(c: Int, r: Int = 12) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(r).toFloat()
        if (c == CARD) setStroke(dp(1), STROKE)
    }

    fun outlined(fill: Int, stroke: Int, r: Int = 12, w: Int = 2) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(w), stroke)
    }

    fun icon(ic: Ic, color: Int = WHITE, sizeDp: Int = 22) = IconDrawable(ic, color, dp(sizeDp))

    fun iconView(ic: Ic, color: Int = WHITE, sizeDp: Int = 22): ImageView = iconView(a, ic, color, sizeDp)

    /** Round coloured badge with a pictogram. */
    fun badge(ic: Ic, color: Int, size: Int = 40): ImageView = ImageView(a).apply {
        setImageDrawable(icon(ic, lighten(color, 1.25f), (size * 0.56f).toInt()))
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(alpha(color, 55)) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Springy press feedback: shrink on touch, overshoot back on release. Does not consume the touch. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressAnim(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** Staggered fade/slide-in of the direct children of the first LinearLayout found under [root]. */
    fun staggerIn(root: View, maxItems: Int = 14) {
        val box: android.view.ViewGroup = when {
            root is android.widget.ScrollView && root.childCount > 0 && root.getChildAt(0) is android.view.ViewGroup -> root.getChildAt(0) as android.view.ViewGroup
            root is android.view.ViewGroup -> root
            else -> return
        }
        for (i in 0 until minOf(box.childCount, maxItems)) {
            val c = box.getChildAt(i)
            c.alpha = 0f; c.translationY = dp(26).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 45L).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        }
    }

    private fun pressable(normal: Drawable, pressed: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    fun btn(t: String, color: Int = ACC, onClick: () -> Unit): Button = IconButton(a).apply {
        val ic = iconForButton(t)
        text = Lang.t(clean(t).ifEmpty { t })
        isAllCaps = false
        setTextColor(WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(46); minimumHeight = dp(46)
        background = if (color == CARD) pressable(rounded(CARD, 12), outlined(SEL, ACC, 12, 1))
        else pressable(
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(lighten(color, 1.2f), color)).apply { cornerRadius = dp(12).toFloat() },
            rounded(darken(color, 0.75f), 12)
        )
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (ic != null) {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelative(icon(ic, WHITE, 18).also { it.setBounds(0, 0, dp(18), dp(18)) }, null, null, null)
            compoundDrawablePadding = dp(8)
        } else gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        pressAnim(this)
    }

    fun lp(top: Int = 8) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    fun row(vararg v: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x ->
            addView(x, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    /** Equal-height row (for tiles). */
    fun grid(vararg v: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        v.forEachIndexed { i, x ->
            addView(x, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    fun col(padTop: Int = 4) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(padTop), dp(14), dp(28))
    }

    fun vbox() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    fun scroll(v: View) = ScrollView(a).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        // Keep focus on the scroll view itself so a text box or selectable text lower down
        // can't grab focus and drag the page to it every time the content refreshes.
        descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
        addView(v)
    }

    /** Section header: round icon badge + title + hairline. */
    fun section(title: String, ic: Ic? = null): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(20), 0, dp(8))
        val i = ic ?: iconForTitle(title)
        if (i != null) addView(badge(i, ACC, 28), LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
        else addView(View(a).apply { background = rounded(ACC, 3) }, LinearLayout.LayoutParams(dp(4), dp(18)).apply { rightMargin = dp(10) })
        addView(tv(clean(title).ifEmpty { title }, 16f, WHITE, true))
        addView(View(a).apply { setBackgroundColor(STROKE) }, LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(10) })
    }

    fun edit(hint: String, value: String, numeric: Boolean = false) = EditText(a).apply {
        setText(value)
        this.hint = hint
        setTextColor(WHITE)
        setHintTextColor(DIM)
        background = rounded(Color.parseColor("#0E151C"), 10).apply { setStroke(dp(1), STROKE) }
        setPadding(dp(12), dp(11), dp(12), dp(11))
        isSingleLine = true
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    fun labeled(label: String, v: View): LinearLayout = vbox().apply {
        addView(tv(label, 12f, DIM).apply { setPadding(dp(2), 0, 0, dp(3)) })
        addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** Toggle row (a Switch; same API as the old CheckBox: isChecked). */
    fun check(label: String, value: Boolean): Switch = Switch(a).apply {
        val ic = iconForTitle(label)
        text = Lang.t(clean(label).ifEmpty { label })
        isChecked = value
        setTextColor(WHITE)
        textSize = 14f
        showText = false
        background = InsetDrawable(rounded(CARD, 12), 0, dp(3), 0, dp(3))
        setPadding(dp(14), dp(14), dp(14), dp(14))
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(CYAN, Color.parseColor("#7A8794")))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(alpha(CYAN, 110), Color.parseColor("#2A3541")))
        if (ic != null) {
            setCompoundDrawablesRelative(icon(ic, DIM, 20).also { it.setBounds(0, 0, dp(20), dp(20)) }, null, null, null)
            compoundDrawablePadding = dp(12)
        }
    }

    fun card(text: String, size: Float = 13f, color: Int = WHITE, bg: Int = CARD) = tv(text, size, color).apply {
        background = rounded(bg, 20)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(0f, 1.1f)
    }

    /** Card with an icon badge and a two-line text block. */
    fun iconCard(ic: Ic, color: Int, title: String, body: String, bg: Int = CARD, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(bg, 14)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            addView(badge(ic, color, 42), LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            addView(vbox().apply {
                addView(tv(title, 14.5f, WHITE, true))
                if (body.isNotEmpty()) addView(tv(body, 12f, DIM))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onClick != null) {
                addView(iconView(Ic.CHEV, DIM, 18))
                setOnClickListener { onClick() }
                pressAnim(this)
            }
        }

    /** Big icon-only menu tile (icon on top, short label below). */
    fun menuTile(ic: Ic, label: String, color: Int, onClick: () -> Unit): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = pressable(rounded(CARD, 20), outlined(SEL, color, 20, 1))
        setPadding(dp(6), dp(16), dp(6), dp(14))
        minimumHeight = dp(104)
        addView(iconView(ic, color, 36))
        addView(tv(label, 12.5f, WHITE).apply { gravity = Gravity.CENTER; maxLines = 2 }, lp(10))
        setOnClickListener { onClick() }
        pressAnim(this)
    }

    /** Turns "Label    value" text lines into a rounded card of dotted rows (same style as every data list). */
    fun facts(text: String): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 20)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val re = Regex("^(.*?)\\s{2,}(.*)$")
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val m = re.find(line.trim())
            if (m != null) addView(dotRow(m.groupValues[1].trim(), m.groupValues[2].trim()))
            else addView(tv(line.trim(), 13f, DIM), lp(4))
        }
    }

    /** Row "Label ........ value" with a dotted leader line. */
    fun dotRow(label: String, value: String, ic: Ic? = null, color: Int = WHITE): LinearLayout = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        if (ic != null) addView(iconView(ic, DIM, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
        addView(tv(label, 14f, Color.parseColor("#C8D3DE")))
        addView(object : View(a) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); this.color = Color.parseColor("#4A5666")
                pathEffect = DashPathEffect(floatArrayOf(dp(3).toFloat(), dp(4).toFloat()), 0f)
            }
            override fun onDraw(c: Canvas) { c.drawLine(0f, height / 2f, width.toFloat(), height / 2f, p) }
        }, LinearLayout.LayoutParams(0, dp(12), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        addView(tv(value, 14.5f, color, true))
    }

    /** Menu tile: big badge, title, one-line subtitle. */
    fun tile(ic: Ic, title: String, sub: String, color: Int, onClick: () -> Unit): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(rounded(CARD, 16), outlined(SEL, color, 16, 1))
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(badge(ic, color, 46), LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(tv(title, 15f, WHITE, true), lp(12))
        addView(tv(sub, 11.5f, DIM))
        setOnClickListener { onClick() }
        pressAnim(this)
    }

    /** Small stat tile: icon, caption, value. */
    fun stat(ic: Ic, caption: String, color: Int = ACC): Pair<LinearLayout, TextView> {
        val v = tv("--", 16f, WHITE, true)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(ic, color, 16).let { d -> ImageView(a).apply { setImageDrawable(d) } },
                    LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) })
                addView(tv(caption, 11f, DIM))
            })
            addView(v, lp(4))
        }
        return box to v
    }

    fun chip(text: String, color: Int = ACC, ic: Ic? = null): LinearLayout = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(alpha(color, 50), 16)
        setPadding(dp(10), dp(5), dp(12), dp(5))
        if (ic != null) addView(ImageView(a).apply { setImageDrawable(icon(ic, lighten(color, 1.3f), 14)) },
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(5) })
        addView(tv(text, 12f, lighten(color, 1.4f), true))
    }

    fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()

    fun f(v: Double?, dec: Int, unit: String = "") =
        if (v == null || v.isNaN() || v.isInfinite()) "--" else "%.${dec}f%s".format(v, unit)

    fun hms(s: Double): String {
        val t = s.toLong()
        return "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    fun date(t: Long, pattern: String = "dd MMM yy HH:mm"): String {
        var p = pattern
        if (!Prefs.time24) p = p.replace("HH:mm:ss", "hh:mm:ss a").replace("HH:mm", "hh:mm a")
        return SimpleDateFormat(p, Locale.getDefault()).format(Date(t))
    }

    /** Economy formatted per the user's unit choice (km/L, L/100km, MPG). */
    fun econ(kmpl: Double?, unit: String = ObdManager.vehicle.spec.unit): String {
        if (kmpl == null || kmpl <= 0 || kmpl.isInfinite()) return "--"
        return Units.econ(kmpl, unit)
    }

    fun money(v: Double) = "${Prefs.currency}%.0f".format(v)

    /* unit-aware helpers (values are always passed in km, km/h, L, °C, kPa) */
    fun spd(v: Double?, dec: Int = 0) = if (v == null) "--" else f(Units.spd(v), dec, " " + Units.spdLabel)
    fun dist(v: Double?, dec: Int = 1) = if (v == null) "--" else f(Units.km(v), dec, " " + Units.distLabel)
    fun temp(v: Double?, dec: Int = 0) = if (v == null) "--" else f(Units.temp(v), dec, Units.tempLabel)
    fun press(v: Double?) = if (v == null) "--" else f(Units.press(v), Units.pressDec, " " + Units.pressLabel)
    fun vol(v: Double?, dec: Int = 1, spec: String = ObdManager.vehicle.spec.unit) = if (v == null) "--" else f(Units.vol(v, spec), dec, " " + Units.volLabel(spec))
}

/** A screen inside MainActivity. */
abstract class Page(val a: MainActivity) {
    val ui: Ui get() = a.ui
    open val parent: String? = null
    lateinit var view: View
    abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun refresh() {}
}
