package com.dilip.carobd

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.Manifest
import android.content.Context
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

/** Draws one road sign as a clean icon (used for the sign list in the Drive vision screen). */
object SignIcon {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private fun arrow(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float) {
        c.save(); c.rotate(deg, cx, cy)
        val pa = Path().apply {
            moveTo(cx, cy - r * 0.62f); lineTo(cx + r * 0.34f, cy - r * 0.16f); lineTo(cx + r * 0.12f, cy - r * 0.16f)
            lineTo(cx + r * 0.12f, cy + r * 0.6f); lineTo(cx - r * 0.12f, cy + r * 0.6f); lineTo(cx - r * 0.12f, cy - r * 0.16f); lineTo(cx - r * 0.34f, cy - r * 0.16f); close()
        }
        c.drawPath(pa, p); c.restore()
    }

    fun draw(c: Canvas, s: SignInfo, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.FILL
        when {
            s.id == 14 -> {
                val o = Path(); for (i in 0 until 8) { val a = Math.toRadians(22.5 + i * 45.0); val x = cx + r * Math.cos(a).toFloat(); val y = cy + r * Math.sin(a).toFloat(); if (i == 0) o.moveTo(x, y) else o.lineTo(x, y) }; o.close()
                p.color = Color.WHITE; c.drawPath(o, p)
                c.save(); c.scale(0.88f, 0.88f, cx, cy); p.color = Color.rgb(205, 20, 30); c.drawPath(o, p); c.restore()
                t.color = Color.WHITE; t.textSize = r * 0.62f; c.drawText("STOP", cx, cy + r * 0.22f, t)
            }
            s.id == 13 -> {
                val tri = Path().apply { moveTo(cx - r, cy - r * 0.8f); lineTo(cx + r, cy - r * 0.8f); lineTo(cx, cy + r * 0.9f); close() }
                p.color = Color.rgb(205, 20, 30); c.drawPath(tri, p)
                c.save(); c.scale(0.62f, 0.62f, cx, cy - r * 0.25f); p.color = Color.WHITE; c.drawPath(tri, p); c.restore()
            }
            s.id == 17 -> { p.color = Color.rgb(205, 20, 30); c.drawCircle(cx, cy, r, p); p.color = Color.WHITE; c.drawRect(cx - r * 0.7f, cy - r * 0.16f, cx + r * 0.7f, cy + r * 0.16f, p) }
            s.id == 12 -> {
                val d = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
                p.color = Color.WHITE; c.drawPath(d, p); c.save(); c.scale(0.78f, 0.78f, cx, cy); p.color = Color.rgb(255, 196, 0); c.drawPath(d, p); c.restore()
            }
            s.id in 33..40 -> {
                p.color = Color.rgb(20, 90, 200); c.drawCircle(cx, cy, r, p)
                p.color = Color.WHITE
                when (s.id) {
                    33 -> arrow(c, cx, cy, r, 90f); 34 -> arrow(c, cx, cy, r, -90f); 35 -> arrow(c, cx, cy, r, 0f)
                    36 -> { arrow(c, cx - r * 0.12f, cy, r, 0f); arrow(c, cx + r * 0.1f, cy + r * 0.05f, r * 0.7f, 90f) }
                    37 -> { arrow(c, cx + r * 0.12f, cy, r, 0f); arrow(c, cx - r * 0.1f, cy + r * 0.05f, r * 0.7f, -90f) }
                    38 -> arrow(c, cx, cy, r, 140f); 39 -> arrow(c, cx, cy, r, -140f)
                    else -> { t.color = Color.WHITE; t.textSize = r * 1.2f; c.drawText("↻", cx, cy + r * 0.4f, t) }
                }
            }
            s.kind == 'L' -> {
                p.color = Color.rgb(205, 20, 30); c.drawCircle(cx, cy, r, p); p.color = Color.WHITE; c.drawCircle(cx, cy, r * 0.74f, p)
                t.color = Color.BLACK; t.textSize = r * (if (s.value >= 100) 0.7f else 0.9f); c.drawText(s.value.toString(), cx, cy + r * 0.32f, t)
            }
            s.kind == 'W' -> {
                val tri = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy + r * 0.8f); lineTo(cx - r, cy + r * 0.8f); close() }
                p.color = Color.rgb(205, 20, 30); c.drawPath(tri, p)
                c.save(); c.scale(0.68f, 0.68f, cx, cy + r * 0.3f); p.color = Color.WHITE; c.drawPath(tri, p); c.restore()
                t.color = Color.BLACK; t.textSize = r * 0.9f; c.drawText("!", cx, cy + r * 0.62f, t)
            }
            s.kind == 'R' -> { p.color = Color.rgb(205, 20, 30); c.drawCircle(cx, cy, r, p); p.color = Color.WHITE; c.drawCircle(cx, cy, r * 0.74f, p); p.color = Color.rgb(60, 60, 60); c.drawRect(cx - r * 0.4f, cy - r * 0.1f, cx + r * 0.4f, cy + r * 0.1f, p) }
            else -> { p.color = Color.rgb(120, 125, 135); c.drawCircle(cx, cy, r, p); p.color = Color.WHITE; p.strokeWidth = r * 0.14f; p.style = Paint.Style.STROKE; c.drawLine(cx - r * 0.6f, cy + r * 0.6f, cx + r * 0.6f, cy - r * 0.6f, p); p.style = Paint.Style.FILL }
        }
    }
}

/** The full-screen heads-up picture: lane, vehicle ahead, distance, sign icons and warnings. */
class VisionOverlay(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, col: Int = Color.argb(150, 0, 0, 0)) { p.style = Paint.Style.FILL; p.color = col; c.drawRoundRect(l, t, r, b, 10 * d, 10 * d, p) }

    override fun onDraw(c: Canvas) {
        val fw = Adas.fw.takeIf { it > 0 } ?: SignEngine.frameW; val fh = Adas.fh.takeIf { it > 0 } ?: SignEngine.frameH
        val W = width.toFloat(); val H = height.toFloat()
        if (fw > 0 && fh > 0) {
            val sx = W / fw; val sy = H / fh
            val warn = Adas.level == 2 || Adas.departing != 0
            Adas.lane?.let { ln ->
                val col = if (warn) Color.rgb(255, 70, 70) else if (Adas.level == 1) Color.rgb(255, 190, 0) else Color.rgb(0, 220, 150)
                val path = Path().apply { moveTo(ln.xl * sx, ln.yBottom * sy); lineTo(ln.xr * sx, ln.yBottom * sy); lineTo(ln.xrTop * sx, ln.yTop * sy); lineTo(ln.xlTop * sx, ln.yTop * sy); close() }
                p.style = Paint.Style.FILL; p.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col)); c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = col
                c.drawLine(ln.xl * sx, ln.yBottom * sy, ln.xlTop * sx, ln.yTop * sy, p); c.drawLine(ln.xr * sx, ln.yBottom * sy, ln.xrTop * sx, ln.yTop * sy, p)
            }
            Adas.veh?.let { v ->
                val col = when (Adas.level) { 2 -> Color.rgb(255, 70, 70); 1 -> Color.rgb(255, 190, 0); else -> Color.rgb(0, 212, 232) }
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = col
                val x = v.x * sx; val y = v.y * sy
                c.drawRect(x, y, x + v.w * sx, y + v.h * sy, p)
                val lab = "%.1f m".format(v.dist)
                tx.textSize = 15 * d; tx.color = col
                val tw = tx.measureText(lab)
                panel(c, x, y - 22 * d, x + tw + 12 * d, y - 2 * d)
                c.drawText(lab, x + 6 * d, y - 6 * d, tx)
            }
            // signs found in this picture (thin boxes)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d
            for (dt in SignEngine.dets) {
                p.color = when (dt.state) { 0 -> Ui.GOOD; 1 -> Ui.CYAN; 4 -> Ui.VIOLET; 3 -> Ui.DIM; else -> Ui.WARN }
                c.drawRect(dt.c.x * sx, dt.c.y * sy, (dt.c.x + dt.c.w) * sx, (dt.c.y + dt.c.h) * sy, p)
            }
        }
        // ---- top: speed (centre), limit (right) ----
        val spd = RoadAssist.speedKmh
        val lim = RoadAssist.info?.limit
        val shown = if (SignEngine.lastLimit > 0 && System.currentTimeMillis() - SignEngine.lastLimitAt < 20_000) SignEngine.lastLimit else lim
        val over = shown != null && spd > shown + Prefs.speedMargin
        panel(c, W / 2 - 62 * d, 6 * d, W / 2 + 62 * d, 66 * d)
        big.color = if (over) Color.rgb(255, 80, 80) else Color.WHITE; big.textSize = 36 * d
        c.drawText("%.0f".format(Units.spd(spd)), W / 2, 42 * d, big)
        big.color = Color.LTGRAY; big.textSize = 11 * d; c.drawText(Units.spdLabel, W / 2, 59 * d, big)
        if (shown != null) {
            val cx = W - 44 * d; val cy = 42 * d
            p.style = Paint.Style.FILL; p.color = Color.rgb(205, 20, 30); c.drawCircle(cx, cy, 32 * d, p)
            p.color = Color.WHITE; c.drawCircle(cx, cy, 24 * d, p)
            big.color = Color.BLACK; big.textSize = (if (shown >= 100) 20 else 26) * d; c.drawText(shown.toString(), cx, cy + 9 * d, big)
        }
        // ---- left: recent sign icons ----
        val now = System.currentTimeMillis()
        val list = SignEngine.recent.filter { now - it.t < 30_000 && it.id in SignNet.KB.indices }.take(3)
        var y = 84 * d
        for ((i, s) in list.withIndex()) {
            val r = (if (i == 0) 34 else 22) * d
            val info = SignNet.KB[s.id]
            panel(c, 8 * d, y - 6 * d, 8 * d + 2 * r + 190 * d, y + 2 * r + 6 * d)
            SignIcon.draw(c, info, 8 * d + r + 6 * d, y + r, r)
            tx.color = Color.WHITE; tx.textSize = (if (i == 0) 15 else 12) * d
            val nm = info.name.let { if (it.length > 24) it.take(23) + "…" else it }
            c.drawText(nm, 8 * d + 2 * r + 14 * d, y + r + 5 * d, tx)
            y += 2 * r + 12 * d
        }
        // ---- bottom: ADAS status chips ----
        val chips = ArrayList<Triple<String, String, Int>>()
        val ldwCol = if (Adas.departing != 0) Ui.BAD else if (!Prefs.adasLane) Ui.DIM else if (Adas.lane != null) Ui.GOOD else Ui.DIM
        chips += Triple("LDWS", if (!Prefs.adasLane) "off" else if (Adas.departing < 0) "LEFT" else if (Adas.departing > 0) "RIGHT" else if (Adas.lane != null) "lane" else "no lane", ldwCol)
        val fcwCol = if (!Prefs.adasFcw) Ui.DIM else when (Adas.level) { 2 -> Ui.BAD; 1 -> Ui.WARN; else -> if (Adas.veh != null) Ui.GOOD else Ui.DIM }
        chips += Triple("FCWS", if (!Prefs.adasFcw) "off" else if (Adas.level == 2) "BRAKE" else if (Adas.veh != null) (if (Adas.ttc < 50) "%.1f s".format(Adas.ttc) else "ok") else "clear", fcwCol)
        chips += Triple("FVDW", if (Adas.headway < 50) "%.1f s".format(Adas.headway) else "--", if (Adas.level == 1) Ui.WARN else if (Adas.headway < 50) Ui.GOOD else Ui.DIM)
        chips += Triple("PED", "n/a", Ui.DIM)
        var bx = 8 * d
        val by = H - 52 * d
        for ((n, v, col) in chips) {
            panel(c, bx, by, bx + 76 * d, by + 44 * d)
            tx.color = col; tx.textSize = 13 * d; c.drawText(n, bx + 8 * d, by + 18 * d, tx)
            tx.color = Color.WHITE; tx.textSize = 14 * d; c.drawText(v, bx + 8 * d, by + 37 * d, tx)
            bx += 82 * d
        }
        // ---- warning banner ----
        val msg = when { Adas.level == 2 -> "COLLISION WARNING"; Adas.departing < 0 -> "DRIFTING LEFT"; Adas.departing > 0 -> "DRIFTING RIGHT"; Adas.level == 1 -> "KEEP YOUR DISTANCE"; else -> "" }
        if (msg.isNotEmpty()) {
            val col = if (Adas.level == 1 && Adas.departing == 0) Color.rgb(255, 170, 0) else Color.rgb(230, 40, 50)
            val tw = big.apply { textSize = 22 * d }.measureText(msg)
            panel(c, W / 2 - tw / 2 - 16 * d, H * 0.30f, W / 2 + tw / 2 + 16 * d, H * 0.30f + 44 * d, col)
            big.color = Color.WHITE; c.drawText(msg, W / 2, H * 0.30f + 31 * d, big)
        }
    }
}

/** Drive vision: the sign camera in landscape, full screen, with lane, vehicle-distance and sign display. WARNINGS ONLY. */
@Suppress("DEPRECATION")
@SuppressLint("SetTextI18n")
class VisionPage(a: MainActivity) : Page(a) {
    override val parent = "Signs"
    private lateinit var stage: FrameLayout
    private lateinit var holder: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var overlay: VisionOverlay
    private lateinit var msgTv: TextView
    private var cam: Camera? = null
    private var running = false
    private var surface: SurfaceTexture? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var lastFrame = 0L
    private var rot = 0
    private var torch = false
    private var panelOn = false
    private lateinit var panelBox: LinearLayout

    override fun build(): View {
        holder = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        stage = FrameLayout(a)
        tv = TextureView(a)
        overlay = VisionOverlay(a)
        stage.addView(tv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        stage.addView(overlay, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        holder.addView(stage, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = st; if (running) openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeCamera(); surface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        msgTv = ui.tv("", 14f, Ui.WHITE).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.argb(150, 0, 0, 0)); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)) }
        holder.addView(msgTv, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))

        // small controls, bottom right
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun small(t: String, onClick: () -> Unit) = ui.btn(t, Ui.CARD) { onClick() }
        val startB = small("Stop") {}
        startB.setOnClickListener { if (running) { stop(); startB.text = "Start" } else { start(); startB.text = "Stop" } }
        bar.addView(startB); bar.addView(small("Light") { torch = !torch; applyParams() }); bar.addView(small("Setup") { panelOn = !panelOn; panelBox.visibility = if (panelOn) View.VISIBLE else View.GONE })
        bar.addView(small("Exit") { a.show("Signs") })
        holder.addView(bar, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(8), ui.dp(8)) })

        panelBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(210, 10, 15, 20)); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); visibility = View.GONE }
        panelBox.addView(ui.check("Lane departure warning", Prefs.adasLane).apply { setOnCheckedChangeListener { _, v -> Prefs.adasLane = v } })
        panelBox.addView(ui.check("Collision and following-distance warning", Prefs.adasFcw).apply { setOnCheckedChangeListener { _, v -> Prefs.adasFcw = v } })
        panelBox.addView(ui.check("Speak warnings", Prefs.signSpeak).apply { setOnCheckedChangeListener { _, v -> Prefs.signSpeak = v } })
        panelBox.addView(ui.tv("Where the car bonnet starts in the picture (rows below are ignored)", 12f, Ui.DIM))
        panelBox.addView(SeekBar(a).apply { max = 38; progress = Prefs.adasHood - 60; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { Prefs.adasHood = 60 + v }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }) })
        panelBox.addView(ui.tv("Driver aid, not a safety system. Warnings only: it never brakes or steers. Lane and distance are estimated from one phone camera and can be wrong, especially in rain, glare, darkness, faded lines or for trucks. Pedestrian detection is not included. Always watch the road.", 11.5f, Ui.WARN))
        holder.addView(panelBox, FrameLayout.LayoutParams(ui.dp(360), Ui.WRAP, Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(0, 0, ui.dp(8), 0) })
        return holder
    }

    override fun onShow() {
        try { SignNet.load(a.application) } catch (_: Throwable) {}
        start()
    }
    override fun onHide() { stop() }

    override fun refresh() {
        if (!::overlay.isInitialized) return
        overlay.invalidate()
        msgTv.visibility = if (msgTv.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun start() {
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
            msgTv.text = "Allow the camera, then open Drive vision again"; msgTv.visibility = View.VISIBLE
            return
        }
        msgTv.text = ""
        running = true
        SignEngine.reset(); Adas.reset()
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (surface != null) openCamera()
    }

    private fun stop() {
        running = false
        closeCamera()
        try { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Throwable) {}
    }

    private fun openCamera() {
        closeCamera()
        val st = surface ?: return
        try {
            var id = -1
            val info = Camera.CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break } }
            if (id < 0) { msgTv.text = "No rear camera found"; running = false; return }
            Camera.getCameraInfo(id, info)
            val c = Camera.open(id)
            val p = c.parameters
            val size = p.supportedPreviewSizes.filter { it.width <= 960 && it.height <= 720 }.maxByOrNull { it.width * it.height } ?: p.supportedPreviewSizes.first()
            p.setPreviewSize(size.width, size.height)
            p.previewFormat = android.graphics.ImageFormat.NV21
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            else if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            c.parameters = p
            val deg = when (a.windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            rot = (info.orientation - deg + 360) % 360
            c.setDisplayOrientation(rot)
            val fovH = try { if (rot % 180 == 0) p.horizontalViewAngle else p.verticalViewAngle } catch (_: Throwable) { 60f }
            Adas.hfovDeg = fovH.toDouble().coerceIn(30.0, 120.0)
            val iw = if (rot % 180 == 0) size.width else size.height; val ih = if (rot % 180 == 0) size.height else size.width
            fit(iw, ih)
            c.setPreviewTexture(st)
            c.addCallbackBuffer(ByteArray(size.width * size.height * 3 / 2))
            c.setPreviewCallbackWithBuffer { data, cm ->
                val now = System.currentTimeMillis()
                if (busy || now - lastFrame < 200 || !running) { cm.addCallbackBuffer(data); return@setPreviewCallbackWithBuffer }
                busy = true; lastFrame = now
                exec.execute {
                    try {
                        val (px, w, h) = SignVision.fromNv21(data, size.width, size.height, rot)
                        SignEngine.process(px, w, h)
                        Adas.process(px, w, h)
                    } catch (_: Throwable) {
                    } finally { busy = false; try { cm.addCallbackBuffer(data) } catch (_: Throwable) {} }
                }
            }
            c.startPreview()
            cam = c
            applyParams()
        } catch (e: Throwable) {
            msgTv.text = "Camera unavailable: ${e.message ?: "in use by another app"}"
            running = false; cam = null
        }
    }

    private fun applyParams() {
        val c = cam ?: return
        try {
            val p = c.parameters
            val fm = p.supportedFlashModes
            if (fm != null) p.flashMode = if (torch && fm.contains(Camera.Parameters.FLASH_MODE_TORCH)) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
        } catch (_: Throwable) {}
    }

    private fun closeCamera() {
        try { cam?.setPreviewCallbackWithBuffer(null); cam?.stopPreview(); cam?.release() } catch (_: Throwable) {}
        cam = null
    }

    /** Fill the screen height and keep the picture undistorted (letterbox on the sides if needed). */
    private fun fit(iw: Int, ih: Int) {
        holder.post {
            val bw = holder.width; val bh = holder.height
            if (bw == 0 || bh == 0) return@post
            val k = minOf(bw.toFloat() / iw, bh.toFloat() / ih)
            stage.layoutParams = FrameLayout.LayoutParams((iw * k).toInt(), (ih * k).toInt(), Gravity.CENTER)
        }
    }
}
