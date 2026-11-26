package com.dilip.carobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    lateinit var ui: Ui
    private lateinit var header: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var stDot: View
    private lateinit var stTv: TextView
    private lateinit var stBox: LinearLayout
    private lateinit var backBtn: android.widget.ImageView
    private lateinit var logo: LogoView
    private val navIcons = HashMap<String, IconDrawable>()
    private val navLabels = HashMap<String, TextView>()
    private val titles = mapOf(
        "Connect" to "Connect", "Dash" to "Dashboard", "Live" to "Live data", "Trips" to "Trips", "Diag" to "Diagnostics",
        "More" to "My car", "Garage" to "Garage", "Fuel" to "Fuel log", "Maint" to "Maintenance", "Analysis" to "Analysis",
        "Settings" to "Settings", "Alerts" to "Alert centre", "Perf" to "Performance", "Battery" to "Battery",
        "About" to "About", "Health" to "Car health", "Car" to "Drive mode", "TripDetail" to "Trip details", "HUD" to "HUD"
    )
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    val pages = LinkedHashMap<String, Page>()
    private val navButtons = HashMap<String, LinearLayout>()
    var current = "Dash"
        private set
    private var lastState = ObdManager.State.DISCONNECTED
    var btAdapter: BluetoothAdapter? = null
    var selectedTripId = 0L
    private val tabs = listOf("Dash", "Live", "Trips", "Diag", "More")
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ObdManager.init(this)
        ui = Ui(this)
        btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.CARD

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        logo = LogoView(this)
        backBtn = ui.iconView(Ic.BACK, Ui.WHITE, 22).apply {
            background = ui.rounded(Ui.CARD, 12)
            setPadding(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7))
            setOnClickListener { onBackPressed() }
            visibility = View.GONE
        }
        titleTv = ui.tv("", 19f, Ui.WHITE, true)
        subTv = ui.tv("", 11.5f, Ui.DIM).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        stDot = View(this)
        stTv = ui.tv("", 11.5f, Ui.WHITE, true)
        stBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(6), ui.dp(12), ui.dp(6))
            addView(stDot, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply { rightMargin = ui.dp(6) })
            addView(stTv)
            setOnClickListener { show("Connect") }
        }
        header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(8))
            addView(backBtn, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { rightMargin = ui.dp(10) })
            addView(logo, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { rightMargin = ui.dp(10) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleTv); addView(subTv)
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(stBox)
        }
        root.addView(header)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        nav = LinearLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0F161D")); setStroke(ui.dp(1), Ui.STROKE)
            }
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        val tabIcons = mapOf("Dash" to Ic.GAUGE, "Live" to Ic.CHART, "Trips" to Ic.ROUTE, "Diag" to Ic.PULSE, "More" to Ic.GRID)
        val tabNames = mapOf("Dash" to "Dash", "Live" to "Live", "Trips" to "Trips", "Diag" to "Diagnose", "More" to "My car")
        for (name in tabs) {
            val dr = IconDrawable(tabIcons[name] ?: Ic.GRID, Ui.DIM, ui.dp(24))
            val lbl = ui.tv(tabNames[name] ?: name, 10.5f, Ui.DIM).apply { gravity = Gravity.CENTER }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(7), 0, ui.dp(6))
                addView(android.widget.ImageView(this@MainActivity).apply { setImageDrawable(dr) },
                    LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
                addView(lbl, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = ui.dp(2) })
                setOnClickListener { show(name) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = ui.dp(2); rightMargin = ui.dp(2) })
            navButtons[name] = item; navIcons[name] = dr; navLabels[name] = lbl
        }
        root.addView(nav)
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val splash = buildSplash()
        frame.addView(splash, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        setContentView(frame)
        handler.postDelayed({
            splash.animate().alpha(0f).setDuration(450).withEndAction { splash.visibility = View.GONE }.start()
        }, if (savedInstanceState == null) 1700L else 0L)

        pages["Connect"] = ConnectPage(this)
        pages["Dash"] = DashPage(this)
        pages["Live"] = LivePage(this)
        pages["Trips"] = TripsPage(this)
        pages["Diag"] = DiagPage(this)
        pages["More"] = MorePage(this)
        pages["HUD"] = HudPage(this)
        pages["TripDetail"] = TripDetailPage(this)
        pages["Garage"] = GaragePage(this)
        pages["Fuel"] = FuelPage(this)
        pages["Maint"] = MaintPage(this)
        pages["Analysis"] = AnalysisPage(this)
        pages["Settings"] = SettingsPage(this)
        pages["Alerts"] = AlertsPage(this)
        pages["Perf"] = PerformancePage(this)
        pages["Battery"] = BatteryPage(this)
        pages["About"] = AboutPage(this)
        pages["Health"] = HealthPage(this)
        pages["Car"] = CarDashPage(this)
        for (p in pages.values) {
            p.view = p.build()
            content.addView(p.view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        val firstRun = Prefs.lastAddr.isEmpty() && Prefs.lastType != 2
        show(if (firstRun && ObdManager.state != ObdManager.State.CONNECTED) "Connect" else "Dash")
        intent?.getStringExtra("open")?.let { show(it) }
        requestPerms()
        Maint.schedule(this)
        if (Prefs.autoConnect && ObdManager.state == ObdManager.State.DISCONNECTED) {
            handler.postDelayed({ (pages["Connect"] as ConnectPage).autoConnect() }, 1500)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("open")?.let { show(it) }
    }

    private fun buildSplash(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#13263A"), Color.parseColor("#0A0D10"))
            )
            isClickable = true
        }
        box.addView(LogoView(this), LinearLayout.LayoutParams(ui.dp(150), ui.dp(150)))
        box.addView(ui.tv(AppInfo.NAME, 30f, Ui.WHITE, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = ui.dp(18) })
        box.addView(ui.tv("Universal OBD-II diagnostics & trip logger", 14f, Ui.DIM).apply { gravity = Gravity.CENTER })
        box.addView(ui.tv("Version ${AppInfo.VERSION}", 13f, Color.parseColor("#00E5FF")).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = ui.dp(10) })
        box.addView(ui.tv("Developed by ${AppInfo.DEVELOPER}", 15f, Ui.WHITE, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = ui.dp(40) })
        return box
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val p = pages[current]?.parent
        if (p != null) show(p)
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SettingsPage.REQ_RESTORE && resultCode == RESULT_OK) {
            data?.data?.let { (pages["Settings"] as SettingsPage).restore(it) }
        }
    }

    /* ---------------- permissions ---------------- */

    private fun neededPerms(): Array<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_CONNECT
        }
        l += Manifest.permission.ACCESS_FINE_LOCATION
        l += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) l += Manifest.permission.WRITE_EXTERNAL_STORAGE
        return l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    fun requestPerms() {
        val p = neededPerms()
        if (p.isNotEmpty()) requestPermissions(p, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        (pages["Connect"] as? ConnectPage)?.refreshDevices()
    }

    fun hasBtPerms(): Boolean =
        if (Build.VERSION.SDK_INT >= 31)
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else true

    /* ---------------- navigation ---------------- */

    fun show(name: String) {
        if (!pages.containsKey(name)) return
        current = name
        pages.forEach { (k, p) -> p.view.visibility = if (k == name) View.VISIBLE else View.GONE }
        var tab = name
        var guard = 0
        while (tab !in tabs && guard++ < 4) tab = pages[tab]?.parent ?: "More"
        navButtons.forEach { (k, v) ->
            val on = k == tab
            navIcons[k]?.color = if (on) Ui.CYAN else Ui.DIM
            navLabels[k]?.setTextColor(if (on) Ui.WHITE else Ui.DIM)
            v.background = if (on) ui.rounded(Ui.SEL, 14) else null
        }
        titleTv.text = titles[name] ?: name
        val hasBack = pages[name]?.parent != null
        backBtn.visibility = if (hasBack) View.VISIBLE else View.GONE
        logo.visibility = if (hasBack) View.GONE else View.VISIBLE
        val full = name == "HUD" || name == "Car"
        requestedOrientation = if (name == "Car") android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (name != "Perf" && PerfMeter.mode != PerfMeter.Mode.IDLE) PerfMeter.cancel()
        nav.visibility = if (full) View.GONE else View.VISIBLE
        header.visibility = if (full) View.GONE else View.VISIBLE
        pages[name]?.onShow()
        refresh()
    }

    private fun refresh() {
        val m = ObdManager
        val st = m.state
        subTv.text = when (st) {
            ObdManager.State.CONNECTED -> "${m.vehicle.name} · ${m.deviceName}" + if (m.engineOn) " · ${m.cycleMs} ms" else ""
            ObdManager.State.CONNECTING -> m.status
            ObdManager.State.DISCONNECTED -> "${m.vehicle.name} · v${AppInfo.VERSION} · by ${AppInfo.DEVELOPER}"
        }
        val (stText, stCol) = when (st) {
            ObdManager.State.CONNECTED -> if (m.engineOn) (ui.f(m.batteryV, 1, " V")) to Color.parseColor("#2EE59D") else "Ign. off" to Ui.WARN
            ObdManager.State.CONNECTING -> "Linking" to Ui.WARN
            else -> "Offline" to Ui.DIM
        }
        stTv.text = stText
        stDot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(stCol) }
        stBox.background = ui.rounded(Ui.alpha(stCol, 40), 16)
        stTv.setTextColor(Ui.lighten(stCol, 1.2f))
        if (st != lastState) {
            lastState = st
            if (st == ObdManager.State.CONNECTED && Prefs.keepScreenOn)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (st == ObdManager.State.CONNECTED && current == "Connect") {
                show("Dash"); return
            }
        }
        try {
            pages[current]?.refresh()
        } catch (e: Exception) {
            ObdManager.log("UI: ${e.message}")
        }
    }
}
