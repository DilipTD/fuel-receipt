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

    override fun onDraw(c: Canvas) {
        val fw = SignEngine.frameW; val fh = SignEngine.frameH
        if (fw == 0 || fh == 0) return
        val sx = width.toFloat() / fw; val sy = height.toFloat() / fh
        for (dt in SignEngine.dets) {
            val col = when (dt.state) { 0 -> Ui.GOOD; 1 -> Ui.CYAN; 3 -> Ui.DIM; else -> Ui.WARN }
            box.color = col
            val x = dt.c.x * sx; val y = dt.c.y * sy
            c.drawRect(x, y, x + dt.c.w * sx, y + dt.c.h * sy, box)
            val lab = if (dt.state == 3) "ignored" else dt.label
            val tw = txt.measureText(lab)
            bg.color = Ui.alpha(Color.BLACK, 170)
            c.drawRect(x, y - 20 * d, x + tw + 10 * d, y, bg)
            txt.color = col
            c.drawText(lab, x + 5 * d, y - 5 * d, txt)
        }
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
        c.addView(holder, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(250)).apply { topMargin = ui.dp(4) })
        startBtn = ui.btn("Start camera", Ui.ACC) { toggle() }
        c.addView(startBtn, ui.lp(8))
        c.addView(statusTv, ui.lp(6))
        resultBox = ui.vbox()
        c.addView(resultBox, ui.lp(6))

        c.addView(ui.section("Settings", Ic.SLIDERS))
        c.addView(ui.check("Apply speed-limit signs to the road automatically", Prefs.signAuto).apply { setOnCheckedChangeListener { _, v -> Prefs.signAuto = v } }, ui.lp(2))
        c.addView(ui.check("Speak warnings for signs I taught", Prefs.signSpeak).apply { setOnCheckedChangeListener { _, v -> Prefs.signSpeak = v } }, ui.lp(2))

        c.addView(ui.section("Signs I taught", Ic.DATABASE))
        libBox = ui.vbox()
        c.addView(libBox)
        c.addView(ui.tv("Everything works offline. Camera pictures are analysed on the phone and are not saved or sent anywhere; only the small picture of a sign you teach is kept. The taught signs are part of Backup & cloud, so they come back after a restore.\n\n" +
                "Mount the phone firmly behind the windscreen, with the screen on. The camera cannot run with the screen off, and a hot dashboard can make the phone slow down. It is a helper – always follow the real signs.", 11.5f, Ui.DIM), ui.lp(12))
        return ui.scroll(c)
    }

    override fun onShow() { libVer = -1; resSig = ""; renderLib() }
    override fun onHide() { stop() }

    private fun toggle() { if (running) stop() else start() }

    private fun start() {
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
            ui.toast("Allow the camera, then tap Start camera again")
            return
        }
        running = true; camErr = ""
        SignEngine.reset()
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
        } catch (e: Throwable) {
            camErr = "Camera unavailable: ${e.message ?: "in use by another app"}"
            running = false; cam = null; startBtn.text = "Start camera"
        }
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
