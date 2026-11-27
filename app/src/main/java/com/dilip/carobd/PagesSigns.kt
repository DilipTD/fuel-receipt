package com.dilip.carobd

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

/** Draws a box and label over every sign the camera found. */
class SignOverlay(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 15 * d }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    var onTap: ((SignEngine.Det) -> Unit)? = null
    var hud = true

    override fun onDraw(c: Canvas) {
        val fw = SignEngine.frameW; val fh = SignEngine.frameH
        if (hud) drawHud(c)
        if (fw == 0 || fh == 0) return
        val sx = width.toFloat() / fw; val sy = height.toFloat() / fh
        for (dt in SignEngine.dets) {
            val col = when (dt.state) { 0 -> Ui.GOOD; 1 -> Ui.CYAN; 3 -> Ui.DIM; 4 -> Ui.VIOLET; else -> Ui.WARN }
            box.color = col
            val x = dt.c.x * sx; val y = dt.c.y * sy
            c.drawRect(x, y, x + dt.c.w * sx, y + dt.c.h * sy, box)
            val lab = when (dt.state) { 3 -> "ignored"; 2 -> "? tap to teach"; 4 -> "\u2713 " + dt.label; else -> dt.label }
            val tw = txt.measureText(lab)
            bg.color = Ui.alpha(Color.BLACK, 170)
            c.drawRect(x, y - 20 * d, x + tw + 10 * d, y, bg)
            txt.color = col
            c.drawText(lab, x + 5 * d, y - 5 * d, txt)
        }
    }

    /** Heads-up display: your speed, the road limit and the last sign read. */
    private fun drawHud(c: Canvas) {
        val spd = RoadAssist.speedKmh
        val lim = RoadAssist.info?.limit
        val over = lim != null && spd > lim + Prefs.speedMargin
        bg.color = Ui.alpha(Color.BLACK, 150)
        c.drawRoundRect(8 * d, 8 * d, 120 * d, 62 * d, 12 * d, 12 * d, bg)
        big.color = if (over) Color.rgb(255, 80, 80) else Color.WHITE
        big.textSize = 30 * d; c.drawText("%.0f".format(Units.spd(spd)), 64 * d, 42 * d, big)
        big.textSize = 11 * d; big.color = Ui.DIM; c.drawText(Units.spdLabel, 64 * d, 57 * d, big)
        val cx = width - 38 * d; val cy = 40 * d
        val shown = if (SignEngine.lastLimit > 0 && System.currentTimeMillis() - SignEngine.lastLimitAt < 20_000) SignEngine.lastLimit else lim
        if (shown != null) {
            bg.color = Color.rgb(205, 20, 30); c.drawCircle(cx, cy, 30 * d, bg)
            bg.color = Color.WHITE; c.drawCircle(cx, cy, 22 * d, bg)
            big.color = Color.BLACK; big.textSize = (if (shown >= 100) 19 else 24) * d
            c.drawText(shown.toString(), cx, cy + 8 * d, big)
            if (SignEngine.lastLimit > 0 && shown == SignEngine.lastLimit) { big.color = Ui.GOOD; big.textSize = 10 * d; c.drawText("CAMERA", cx, cy + 44 * d, big) }
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.action != android.view.MotionEvent.ACTION_UP) return true
        val fw = SignEngine.frameW; val fh = SignEngine.frameH
        if (fw == 0 || fh == 0) return true
        val fx = e.x * fw / width; val fy = e.y * fh / height
        SignEngine.dets.firstOrNull { fx >= it.c.x - 6 && fx <= it.c.x + it.c.w + 6 && fy >= it.c.y - 6 && fy <= it.c.y + it.c.h + 6 }?.let { onTap?.invoke(it) }
        return true
    }
}

/** Sign camera: reads speed-limit signs offline, learns other signs once, and feeds the Road assistant. */
@Suppress("DEPRECATION")
@SuppressLint("SetTextI18n")
class SignPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var holder: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var overlay: SignOverlay
    private lateinit var statusTv: TextView
    private lateinit var resultBox: LinearLayout
    private lateinit var libBox: LinearLayout
    private lateinit var startBtn: android.widget.Button
    private var cam: Camera? = null
    private var running = false
    private var surface: SurfaceTexture? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var lastFrame = 0L
    private var rot = 0
    private var camErr = ""
    private var libVer = -1
    private var resSig = ""
    private var pw = 640; private var ph = 480
    private var full = false
    private var torch = false
    private var zoomLvl = 0
    private lateinit var torchBtn: android.widget.Button
    private lateinit var zoomBtn: android.widget.Button
    private lateinit var fullBtn: android.widget.Button
    private lateinit var settingsBox: LinearLayout
    private fun holderH() = (a.resources.displayMetrics.heightPixels * (if (full) 0.78 else 0.46)).toInt()

    override fun build(): View {
        val c = ui.col()
        statusTv = ui.tv("Camera off", 13f, Ui.DIM)
        holder = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        tv = TextureView(a)
        overlay = SignOverlay(a)
        holder.addView(tv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        holder.addView(overlay, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = st; if (running) openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeCamera(); surface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        c.addView(holder, LinearLayout.LayoutParams(Ui.MATCH, holderH()).apply { topMargin = ui.dp(4) })
        startBtn = ui.btn("Start camera", Ui.ACC) { toggle() }
        torchBtn = ui.btn("Light off", Ui.CARD) { torch = !torch; torchBtn.text = if (torch) "Light on" else "Light off"; applyParams() }
        zoomBtn = ui.btn("Zoom 1×", Ui.CARD) { zoomLvl = (zoomLvl + 1) % 4; zoomBtn.text = "Zoom ${zoomLvl + 1}×"; applyParams() }
        fullBtn = ui.btn("Bigger", Ui.CARD) { full = !full; fullBtn.text = if (full) "Smaller" else "Bigger"; holder.layoutParams = (holder.layoutParams as LinearLayout.LayoutParams).apply { height = holderH() }; settingsBox.visibility = if (full) View.GONE else View.VISIBLE; if (running) fit(if (rot % 180 == 0) pw else ph, if (rot % 180 == 0) ph else pw) }
        c.addView(startBtn, ui.lp(8))
        c.addView(ui.row(torchBtn, zoomBtn, fullBtn), ui.lp(6))
        overlay.onTap = { dt -> a.runOnUiThread { when (dt.state) { 2 -> if (SignEngine.hasPending()) teachDialog(null) else ui.toast("Hold steady on the sign for a moment, then tap again"); 4 -> ui.toast("Standard sign: ${dt.label}"); 1, 3 -> SignLib.entries.firstOrNull { it.name == dt.label || (dt.state == 3) && it.action == "ignore" }?.let { teachDialog(it) }; else -> ui.toast("Speed limit ${dt.label} read from the sign") } } }
        c.addView(statusTv, ui.lp(6))
        resultBox = ui.vbox()
        c.addView(resultBox, ui.lp(6))

        settingsBox = ui.vbox(); c.addView(settingsBox)

        settingsBox.addView(ui.section("Settings", Ic.SLIDERS))
        settingsBox.addView(ui.check("Apply speed-limit signs to the road automatically", Prefs.signAuto).apply { setOnCheckedChangeListener { _, v -> Prefs.signAuto = v } }, ui.lp(2))
        settingsBox.addView(ui.check("Recognise standard signs (stop, no entry, school, curves...)", Prefs.signBuiltin).apply { setOnCheckedChangeListener { _, v -> Prefs.signBuiltin = v } }, ui.lp(2))
        settingsBox.addView(ui.check("Speak warnings for signs I taught", Prefs.signSpeak).apply { setOnCheckedChangeListener { _, v -> Prefs.signSpeak = v } }, ui.lp(2))

        settingsBox.addView(ui.section("Standard signs and sign map", Ic.DATABASE))
        mapTv = ui.card("", 12.5f, Ui.WHITE)
        settingsBox.addView(mapTv, ui.lp(4))
        settingsBox.addView(ui.btn("Clear sign map", Ui.BAD) { SignMap.clear(); SignEngine.recent.clear(); ui.toast("Sign map cleared"); refresh() }, ui.lp(6))
        settingsBox.addView(ui.card("Driver aid, not a safety system. Standard signs are recognised by a small model trained on German road signs, and Indian signs that look different may not be recognised. It only gives spoken and on-screen warnings; it never steers, brakes or controls the car. Always watch the road and follow the real signs.", 11.5f, Ui.WARN), ui.lp(6))

        settingsBox.addView(ui.section("Signs I taught", Ic.DATABASE))
        libBox = ui.vbox()
        settingsBox.addView(libBox)
        settingsBox.addView(ui.tv("Everything works offline. Camera pictures are analysed on the phone and are not saved or sent anywhere; only the small picture of a sign you teach is kept. The taught signs are part of Backup & cloud, so they come back after a restore.\n\n" +
                "Mount the phone firmly behind the windscreen, with the screen on. The camera cannot run with the screen off, and a hot dashboard can make the phone slow down. It is a helper – always follow the real signs.", 11.5f, Ui.DIM), ui.lp(12))
        return ui.scroll(c)
    }

    override fun onShow() { libVer = -1; resSig = ""; renderLib() }
    override fun onHide() { stop() }

    private lateinit var mapTv: TextView

    private fun toggle() { if (running) stop() else start() }

    private fun start() {
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
            ui.toast("Allow the camera, then tap Start camera again")
            return
        }
        running = true; camErr = ""
        SignEngine.reset()
        try { SignNet.load(a.application) } catch (_: Throwable) {}
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startBtn.text = "Stop camera"
        if (surface != null) openCamera()
    }

    private fun stop() {
        running = false
        closeCamera()
        try { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}
        if (::startBtn.isInitialized) startBtn.text = "Start camera"
    }

    private fun openCamera() {
        closeCamera()
        val st = surface ?: return
        try {
            var id = -1
            val info = Camera.CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break } }
            if (id < 0) { camErr = "No rear camera found"; running = false; startBtn.text = "Start camera"; return }
            Camera.getCameraInfo(id, info)
            val c = Camera.open(id)
            val p = c.parameters
            val size = p.supportedPreviewSizes.filter { it.width <= 960 && it.height <= 720 }.maxByOrNull { it.width * it.height } ?: p.supportedPreviewSizes.first()
            p.setPreviewSize(size.width, size.height)
            p.previewFormat = android.graphics.ImageFormat.NV21
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            c.parameters = p
            val deg = when (a.windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            rot = (info.orientation - deg + 360) % 360
            c.setDisplayOrientation(rot)
            pw = size.width; ph = size.height
            fit(if (rot % 180 == 0) pw else ph, if (rot % 180 == 0) ph else pw)
            c.setPreviewTexture(st)
            val len = size.width * size.height * 3 / 2
            c.addCallbackBuffer(ByteArray(len))
            c.setPreviewCallbackWithBuffer { data, cm ->
                val now = System.currentTimeMillis()
                if (busy || now - lastFrame < 330 || !running) { cm.addCallbackBuffer(data); return@setPreviewCallbackWithBuffer }
                busy = true; lastFrame = now
                exec.execute {
                    try {
                        val (px, w, h) = SignVision.fromNv21(data, size.width, size.height, rot)
                        SignEngine.process(px, w, h)
                    } catch (_: Throwable) {
                    } finally { busy = false; try { cm.addCallbackBuffer(data) } catch (_: Throwable) {} }
                }
            }
            c.startPreview()
            cam = c
            applyParams()
        } catch (e: Throwable) {
            camErr = "Camera unavailable: ${e.message ?: "in use by another app"}"
            running = false; cam = null; startBtn.text = "Start camera"
        }
    }

    private fun applyParams() {
        val c = cam ?: return
        try {
            val p = c.parameters
            if (p.isZoomSupported) p.zoom = (p.maxZoom * zoomLvl / 3).coerceIn(0, p.maxZoom)
            val fm = p.supportedFlashModes
            if (fm != null) p.flashMode = if (torch && fm.contains(Camera.Parameters.FLASH_MODE_TORCH)) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
        } catch (_: Throwable) {}
    }

    private fun closeCamera() {
        try { cam?.setPreviewCallbackWithBuffer(null); cam?.stopPreview(); cam?.release() } catch (_: Throwable) {}
        cam = null
    }

    /** Keep the picture undistorted inside the box. */
    private fun fit(iw: Int, ih: Int) {
        holder.post {
            val bw = holder.width; val bh = holder.height
            if (bw == 0 || bh == 0) return@post
            val k = minOf(bw.toFloat() / iw, bh.toFloat() / ih)
            val lp = FrameLayout.LayoutParams((iw * k).toInt(), (ih * k).toInt(), Gravity.CENTER)
            tv.layoutParams = lp; overlay.layoutParams = FrameLayout.LayoutParams((iw * k).toInt(), (ih * k).toInt(), Gravity.CENTER)
        }
    }

    /* ---------------------------- teach ---------------------------- */

    private val actions = listOf("Speed limit sign (use the number)", "Warning (speak a message)", "Hazard marker on the map", "Info only (just log it)", "Ignore – not a road sign")
    private val actionKeys = listOf("limit", "warn", "hazard", "info", "ignore")
    private val hazKinds = listOf("bump", "pothole", "curve", "school", "hospital", "rail", "camera", "toll")

    private fun teachDialog(existing: SignEntry?) {
        val name = ui.edit("Name, e.g. School ahead", existing?.name ?: "")
        val act = Spinner(a).apply { adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, actions); setSelection(actionKeys.indexOf(existing?.action ?: "warn").coerceAtLeast(0)) }
        val value = ui.edit("Limit number, e.g. 40", if (existing != null && existing.action == "limit") existing.value.toString() else "", true)
        val say = ui.edit("What to say, e.g. School zone ahead, drive carefully", if (existing?.action == "warn") existing.arg else "")
        val haz = Spinner(a).apply { adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, hazKinds.map { RoadAssist.labelFor(it) }); setSelection(hazKinds.indexOf(existing?.arg).coerceAtLeast(0)) }
        fun sync() {
            val k = actionKeys[act.selectedItemPosition]
            value.visibility = if (k == "limit") View.VISIBLE else View.GONE
            say.visibility = if (k == "warn") View.VISIBLE else View.GONE
            haz.visibility = if (k == "hazard") View.VISIBLE else View.GONE
        }
        act.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = sync()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val thumb = existing?.thumb ?: SignEngine.pendingThumb
        val l = ui.vbox().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            if (thumb != null) addView(ImageView(a).apply { setImageBitmap(BitmapFactory.decodeByteArray(thumb, 0, thumb.size)) }, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(name); addView(act); addView(value); addView(say); addView(haz)
        }
        sync()
        AlertDialog.Builder(a).setTitle(if (existing == null) "Teach this sign once" else "Edit sign").setView(l)
            .setPositiveButton("Save") { _, _ ->
                val k = actionKeys[act.selectedItemPosition]
                val v = value.text.toString().toIntOrNull() ?: 0
                val arg = when (k) { "warn" -> say.text.toString().trim(); "hazard" -> hazKinds[haz.selectedItemPosition]; else -> "" }
                val nm = name.text.toString().trim().ifEmpty { if (k == "limit") "Speed limit $v" else if (k == "ignore") "Ignored sign" else "Sign" }
                if (k == "limit" && v !in 5..130) { ui.toast("Enter a limit between 5 and 130"); return@setPositiveButton }
                if (existing == null) {
                    if (SignEngine.teach(nm, k, v, arg) != null) ui.toast("Learned. I will recognise it offline from now on.") else ui.toast("The sign is no longer in view – point the camera at it and try again")
                } else { existing.name = nm; existing.action = k; existing.value = v; existing.arg = arg; SignLib.touch(); ui.toast("Saved") }
                libVer = -1; resSig = ""
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renderLib() {
        SignLib.load()
        libBox.removeAllViews()
        if (SignLib.entries.isEmpty()) {
            libBox.addView(ui.tv("Nothing taught yet. Point the camera at a sign I do not know and tap Teach. You teach each sign only once.", 12.5f, Ui.DIM))
            return
        }
        for (e in SignLib.entries.reversed()) {
            val what = when (e.action) { "limit" -> "Speed limit ${e.value}"; "warn" -> "Says: ${e.arg}"; "hazard" -> "Marks ${RoadAssist.labelFor(e.arg)}"; "ignore" -> "Ignored"; else -> "Info" }
            libBox.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.rounded(Ui.CARD, 14); setPadding(ui.dp(10), ui.dp(8), ui.dp(12), ui.dp(8))
                e.thumb?.let { t -> addView(ImageView(a).apply { setImageBitmap(BitmapFactory.decodeByteArray(t, 0, t.size)) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { rightMargin = ui.dp(10) }) }
                addView(ui.vbox().apply { addView(ui.tv(e.name, 14f, Ui.WHITE, true)); addView(ui.tv("$what · seen ${e.seen}×", 11.5f, Ui.DIM)) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                setOnClickListener {
                    AlertDialog.Builder(a).setTitle(e.name).setItems(arrayOf("Edit", "Delete")) { _, k ->
                        if (k == 0) teachDialog(e) else { SignLib.delete(e.id); libVer = -1 }
                    }.show()
                }
            }, ui.lp(6))
        }
    }

    /* ---------------------------- live status ---------------------------- */

    override fun refresh() {
        if (!::statusTv.isInitialized) return
        if (SignLib.version != libVer) { libVer = SignLib.version; renderLib() }
        val now = System.currentTimeMillis()
        statusTv.text = when {
            camErr.isNotEmpty() -> camErr
            !running -> "Camera off. Tap Start camera when the phone is mounted."
            SignEngine.dets.isEmpty() -> "Looking for signs…"
            else -> "${SignEngine.dets.size} sign(s) in view"
        }
        overlay.invalidate()
        if (::mapTv.isInitialized) {
            val rec = SignEngine.recent.take(4).joinToString("\n") { "• " + it.name }
            mapTv.text = (if (!SignNet.ready && Prefs.signBuiltin) "Standard sign model loads when the camera starts.\n" else "") +
                "Sign map: ${SignMap.count()} saved, ${SignMap.confirmedCount()} confirmed (seen on 2 or more passes) and used to warn ahead.\n" +
                (if (rec.isEmpty()) "No standard signs seen yet." else "Recently seen:\n$rec")
        }
        val sig = "${SignEngine.lastLimit}|${SignEngine.lastLimitAt / 2000}|${SignEngine.lastKnown}|${SignEngine.hasPending()}|${SignEngine.applied}|${RoadAssist.info?.road}"
        if (sig == resSig) return
        resSig = sig
        resultBox.removeAllViews()
        if (SignEngine.lastLimit > 0 && now - SignEngine.lastLimitAt < 15_000)
            resultBox.addView(ui.iconCard(Ic.FLAG, Ui.GOOD, "Speed limit ${Units.sStr(SignEngine.lastLimit.toDouble())} read", SignEngine.applied.ifEmpty { "Seen" } + (RoadAssist.info?.road?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")), ui.lp(6))
        if (SignEngine.lastKnown.isNotEmpty() && now - SignEngine.lastKnownAt < 15_000)
            resultBox.addView(ui.iconCard(Ic.CHECK, Ui.CYAN, "Recognised: ${SignEngine.lastKnown}", "A sign you taught"), ui.lp(6))
        if (SignEngine.hasPending()) {
            val t = SignEngine.pendingThumb
            resultBox.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.outlined(Ui.CARD, Ui.WARN, 14, 2); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                if (t != null) addView(ImageView(a).apply { setImageBitmap(BitmapFactory.decodeByteArray(t, 0, t.size)) }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { rightMargin = ui.dp(12) })
                addView(ui.vbox().apply { addView(ui.tv("New sign", 14.5f, Ui.WHITE, true)); addView(ui.tv("I do not know this one yet. Teach it once.", 12f, Ui.DIM)) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(ui.tv("Teach", 13f, Ui.WARN, true).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); setOnClickListener { teachDialog(null) } })
                addView(ui.tv("Not a sign", 12f, Ui.DIM).apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(2), ui.dp(8)); setOnClickListener {
                    if (SignEngine.teach("Ignored object", "ignore", 0, "") != null) ui.toast("Okay, I will ignore it from now on"); resSig = ""; libVer = -1 } })
            }, ui.lp(6))
        }
    }
}
