package com.dilip.carobd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.RemoteViews

/** Home-screen widget: the animated-style car card with connection state, battery, odometer and next service. */
class CarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Prefs.init(ctx)
        push(ctx, mgr, ids)
    }

    override fun onEnabled(ctx: Context) {
        Prefs.init(ctx)
        update(ctx)
    }

    companion object {
        private var lastPush = 0L

        /** Refresh all placed widgets; throttled so live data does not drain the battery. */
        fun maybeUpdate(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - lastPush < 20_000) return
            update(ctx)
        }

        fun update(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, CarWidget::class.java))
                if (ids.isEmpty()) return
                push(app, mgr, ids)
            } catch (_: Exception) {
            }
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            lastPush = System.currentTimeMillis()
            val m = ObdManager
            val v = m.vehicle
            val d = ctx.resources.displayMetrics.density
            val hero = CarHeroView(ctx)
            hero.title = v.title
            hero.state = when (m.state) {
                ObdManager.State.CONNECTED -> if (m.engineOn) 2 else 3
                ObdManager.State.CONNECTING -> 1
                else -> 0
            }
            hero.subtitle = when (m.state) {
                ObdManager.State.CONNECTED -> if (m.engineOn) "Driving · " + Units.sStr(m.values[0x0D] ?: 0.0) else "Ignition off"
                ObdManager.State.CONNECTING -> "Connecting…"
                else -> "Tap to open"
            }
            hero.speed = m.values[0x0D] ?: 0.0
            val due = try { Maint.dueList(v).firstOrNull() } catch (_: Exception) { null }
            hero.chips = buildList {
                m.batteryV?.let { add(Ic.BATTERY to "%.1f V".format(it)) }
                if (v.odoKm > 0) add(Ic.GAUGE to Units.dStr(v.odoKm))
                due?.let { add(Ic.WRENCH to (if (it.overdue) "Service due" else it.item.name.take(10))) }
            }
            val w = (340 * d).toInt()
            val h = (215 * d).toInt()
            hero.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            hero.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            hero.draw(Canvas(bmp))

            val res = ctx.resources
            val layout = res.getIdentifier("widget_car", "layout", ctx.packageName)
            val img = res.getIdentifier("w_img", "id", ctx.packageName)
            val root = res.getIdentifier("w_root", "id", ctx.packageName)
            val rv = RemoteViews(ctx.packageName, layout)
            rv.setImageViewBitmap(img, bmp)
            val open = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(root, open)
            for (id in ids) mgr.updateAppWidget(id, rv)
        }
    }
}
