package com.dilip.carobd

import android.content.Context
import android.net.Uri
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Full backup: database (vehicles, trips, routes, fuel, service, codes, alerts) + all settings/parameters, in one .carobd file. */
object Backup {
    private val prefFiles = listOf("car_obd", "nav_places")
    private val skipKeys = setOf("cloudUri", "cloudName", "lastBackupT", "lastBackupWhere", "mqttPass", "welcomed", "permsPrompted", "setupDone")

    fun fileName() = "carobd_backup_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".carobd"

    fun write(ctx: Context, out: OutputStream) {
        val tmp = File(ctx.cacheDir, "snap.db")
        // a fresh install may not have created the database file yet
        if (!Db.file(ctx).exists()) try { Db.reopen(ctx); Db.count("trips") } catch (_: Exception) {}
        Db.close()
        val hasDb = try { Db.file(ctx).exists() && run { Db.file(ctx).copyTo(tmp, overwrite = true); true } } catch (_: Exception) { false } finally { Db.reopen(ctx) }
        ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(JSONObject().put("app", "CarOBD").put("version", AppInfo.VERSION).put("time", System.currentTimeMillis())
                .put("trips", Db.count("trips")).put("fuel", Db.count("fuel")).put("vehicles", Db.count("vehicles")).toString().toByteArray())
            z.closeEntry()
            if (hasDb) { z.putNextEntry(ZipEntry("carobd.db")); tmp.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            Receipts.dir(ctx).listFiles()?.forEach { r -> z.putNextEntry(ZipEntry("receipts/" + r.name)); r.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            Docs.dir(ctx).listFiles()?.forEach { r -> z.putNextEntry(ZipEntry("docs/" + r.name)); r.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            RoadAssist.mapFile()?.takeIf { it.length() < 60L * 1024 * 1024 }?.let { m ->
                try { z.putNextEntry(ZipEntry("maps/road_areas.json")); m.inputStream().use { it.copyTo(z) }; z.closeEntry() } catch (_: Exception) {}
            }
            val all = JSONObject()
            for (n in prefFiles) {
                val o = JSONObject()
                for ((k, v) in ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all) {
                    if (n == "car_obd" && k in skipKeys) continue
                    val e = JSONObject()
                    when (v) {
                        is Boolean -> e.put("t", "b").put("v", v); is Int -> e.put("t", "i").put("v", v); is Long -> e.put("t", "l").put("v", v)
                        is Float -> e.put("t", "f").put("v", v.toDouble()); is String -> e.put("t", "s").put("v", v)
                        is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                        else -> continue
                    }
                    o.put(k, e)
                }
                all.put(n, o)
            }
            z.putNextEntry(ZipEntry("settings.json")); z.write(all.toString().toByteArray()); z.closeEntry()
        }
        tmp.delete()
    }

    /** Returns null on success, else an error message. */
    fun restore(ctx: Context, input: InputStream): String? {
        val tmpDb = File(ctx.cacheDir, "restore.db"); var settings: JSONObject? = null
        val gotReceipts = ArrayList<String>()
        val gotDocs = ArrayList<String>()
        var gotMaps = false
        val buf = input.buffered(); buf.mark(20)
        val head = ByteArray(16); val n = buf.read(head); buf.reset()
        val legacy = n >= 15 && String(head, Charsets.US_ASCII).startsWith("SQLite format 3")
        try {
            if (legacy) buf.use { i -> tmpDb.outputStream().use { i.copyTo(it) } }
            else ZipInputStream(buf).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    when (e.name) {
                        "carobd.db" -> tmpDb.outputStream().use { z.copyTo(it) }
                        "settings.json" -> settings = try { JSONObject(String(z.readBytes())) } catch (_: Exception) { null }
                        "maps/road_areas.json" -> { File(ctx.cacheDir, "restore_maps.json").outputStream().use { z.copyTo(it) }; gotMaps = true }
                        else -> if (e.name.startsWith("docs/") && !e.isDirectory) {
                            val nm = e.name.substringAfter("docs/").replace("/", "").replace("..", "")
                            if (nm.isNotEmpty()) { File(ctx.cacheDir, "dc_$nm").outputStream().use { z.copyTo(it) }; gotDocs += nm }
                        } else if (e.name.startsWith("receipts/") && !e.isDirectory) {
                            val nm = e.name.substringAfter("receipts/").replace("/", "").replace("..", "")
                            if (nm.isNotEmpty()) { File(ctx.cacheDir, "rc_$nm").outputStream().use { z.copyTo(it) }; gotReceipts += nm }
                        }
                    }
                    e = z.nextEntry
                }
            }
        } catch (ex: Exception) { tmpDb.delete(); return "Not a Car OBD backup file" }
        val dbOk = tmpDb.exists() && tmpDb.length() >= 100 && tmpDb.inputStream().use { s -> ByteArray(16).also { s.read(it) } }.let { String(it, Charsets.US_ASCII).startsWith("SQLite format 3") }
        if (!dbOk && settings == null) { tmpDb.delete(); return "Not a Car OBD backup file" }
        if (dbOk) try {
            Db.close()
            val f = Db.file(ctx)
            File(f.path + "-wal").delete(); File(f.path + "-shm").delete()
            tmpDb.copyTo(f, overwrite = true); tmpDb.delete()
        } catch (ex: Exception) { return "Restore failed: ${ex.message}" } finally { Db.reopen(ctx); ObdManager.reloadVehicle() }
        for (nm in gotReceipts) try { File(ctx.cacheDir, "rc_$nm").let { it.copyTo(File(Receipts.dir(ctx), nm), true); it.delete() } } catch (_: Exception) {}
        for (nm in gotDocs) try { File(ctx.cacheDir, "dc_$nm").let { it.copyTo(File(Docs.dir(ctx), nm), true); it.delete() } } catch (_: Exception) {}
        if (gotMaps) try {
            val mf = File(ctx.cacheDir, "restore_maps.json")
            RoadAssist.installMapFile(mf)
            mf.delete()
        } catch (_: Exception) {}
        settings?.let { all ->
            for (name in prefFiles) {
                val o = all.optJSONObject(name) ?: continue
                val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val keep = if (name == "car_obd") skipKeys.associateWith { sp.all[it] } else emptyMap()
                val ed = sp.edit().clear()
                for (k in o.keys()) {
                    try {
                        val e = o.getJSONObject(k)
                        when (e.getString("t")) {
                            "b" -> ed.putBoolean(k, e.getBoolean("v")); "i" -> ed.putInt(k, e.getInt("v")); "l" -> ed.putLong(k, e.getLong("v"))
                            "f" -> ed.putFloat(k, e.getDouble("v").toFloat()); "s" -> ed.putString(k, e.getString("v"))
                            "set" -> ed.putStringSet(k, (0 until e.getJSONArray("v").length()).map { e.getJSONArray("v").getString(it) }.toSet())
                        }
                    } catch (_: Exception) { /* one odd value from an older version must not stop the restore */ }
                }
                for ((k, v) in keep) when (v) { is String -> ed.putString(k, v); is Long -> ed.putLong(k, v); is Boolean -> ed.putBoolean(k, v) }
                ed.commit()
            }
        }
        try { RoadAssist.reloadUserData(); if (gotMaps) RoadAssist.reloadMaps(); SignLib.load(true) } catch (_: Exception) {}
        Prefs.setupDone = true
        return null
    }

    /** Writes to the linked cloud file (Google Drive, OneDrive, Dropbox … chosen in the system picker). */
    fun toCloud(ctx: Context): String? {
        val u = Prefs.cloudUri.ifEmpty { return "No cloud file linked yet" }
        return try {
            val os = ctx.contentResolver.openOutputStream(Uri.parse(u), "wt") ?: return "Cloud file not available"
            os.use { write(ctx, it) }
            Prefs.lastBackupT = System.currentTimeMillis(); Prefs.lastBackupWhere = "Cloud · " + Prefs.cloudName
            null
        } catch (e: Exception) { "Cloud backup failed: ${e.message}" }
    }

    fun toUri(ctx: Context, uri: Uri, label: String): String? = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { write(ctx, it) } ?: throw Exception("cannot open file")
        Prefs.lastBackupT = System.currentTimeMillis(); Prefs.lastBackupWhere = label; null
    } catch (e: Exception) { "Backup failed: ${e.message}" }

    /** Daily automatic backup to the linked cloud file. */
    fun auto(ctx: Context) {
        if (!Prefs.autoBackup || Prefs.cloudUri.isEmpty()) return
        if (System.currentTimeMillis() - Prefs.lastBackupT < 20 * 3600_000L) return
        toCloud(ctx)
    }
}

class BackupPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    private lateinit var box: android.widget.LinearLayout

    companion object { const val REQ_CLOUD_CREATE = 81; const val REQ_CLOUD_OPEN = 82; const val REQ_LOCAL_SAVE = 83 }

    override fun build(): View { box = ui.col(); return ui.scroll(box) }

    private fun work(msg: String, task: () -> String?) {
        ui.toast(msg)
        Thread {
            val err = try { task() } catch (e: Exception) { e.message ?: "error" }
            a.runOnUiThread { ui.toast(err ?: "Done ✅"); onShow() }
        }.start()
    }

    fun cloudCreated(uri: Uri) {
        try { a.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        Prefs.cloudUri = uri.toString()
        Prefs.cloudName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "backup"
        work("Backing up to the cloud…") { Backup.toCloud(a.applicationContext) }
    }

    fun localSaved(uri: Uri) = work("Saving backup…") { Backup.toUri(a.applicationContext, uri, "File on phone") }

    fun restoreFrom(uri: Uri) {
        if (ObdManager.state != ObdManager.State.DISCONNECTED) { ui.toast("Disconnect from the car first, then restore."); return }
        android.app.AlertDialog.Builder(a).setTitle("Replace ALL current data with this backup?")
            .setMessage("Trips, fuel log, service records, vehicles and settings on this phone will be replaced.")
            .setPositiveButton("Restore") { _, _ ->
                work("Restoring…") {
                    val err = a.contentResolver.openInputStream(uri)?.use { Backup.restore(a.applicationContext, it) } ?: "Cannot open the file"
                    if (err == null) a.runOnUiThread { a.recreate() }
                    err
                }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onShow() {
        box.removeAllViews()
        val last = Prefs.lastBackupT
        box.addView(ui.iconCard(Ic.DATABASE, Ui.VIOLET, "Your data",
            "${Db.count("vehicles")} vehicle(s) · ${Db.count("trips")} trips · ${Db.count("fuel")} fill-ups · ${Db.count("maint_rec")} service records · ${Db.count("dtc")} fault codes\n" +
                    "Includes routes, settings, thresholds, custom parameters (PIDs) and alerts."), ui.lp(6))
        box.addView(ui.iconCard(if (last > 0) Ic.CHECK else Ic.WARN, if (last > 0) Ui.GOOD else Ui.WARN,
            if (last > 0) "Last backup ${ui.date(last, "dd MMM, HH:mm")}" else "No backup yet",
            if (last > 0) Prefs.lastBackupWhere else "Link a cloud file below for automatic daily backup"), ui.lp(8))

        box.addView(ui.section("Cloud (Drive · OneDrive · Dropbox)", Ic.UPLOAD))
        if (Prefs.cloudUri.isEmpty()) {
            box.addView(ui.tv("Choose where to keep your cloud copy. The Android picker lists Google Drive, OneDrive, Dropbox and any other cloud app installed on this phone.", 12.5f, Ui.DIM))
            box.addView(ui.btn("Link cloud & back up", Ui.VIOLET) { pickCloud() }, ui.lp(10))
        } else {
            box.addView(ui.iconCard(Ic.UPLOAD, Ui.VIOLET, "Linked: ${Prefs.cloudName}", "Backed up daily in the background and on demand"))
            box.addView(ui.row(
                ui.btn("Back up now", Ui.VIOLET) { work("Backing up to the cloud…") { Backup.toCloud(a.applicationContext) } },
                ui.btn("Change", Ui.CARD) { pickCloud() }
            ), ui.lp(10))
            box.addView(ui.check("Automatic daily backup", Prefs.autoBackup).apply { setOnCheckedChangeListener { _, on -> Prefs.autoBackup = on } }, ui.lp(8))
            box.addView(ui.btn("Unlink cloud", Ui.CARD) { Prefs.cloudUri = ""; Prefs.cloudName = ""; onShow() }, ui.lp(8))
        }
        box.addView(ui.btn("Restore from cloud", Ui.GOOD) { openFile(BackupPage.REQ_CLOUD_OPEN) }, ui.lp(10))

        box.addView(ui.section("On this phone", Ic.DOWNLOAD))
        box.addView(ui.row(
            ui.btn("Save backup file") { saveLocal() },
            ui.btn("Restore from file", Ui.CARD) { openFile(REQ_CLOUD_OPEN) }
        ), ui.lp(4))
        box.addView(ui.section("Google automatic backup", Ic.SHIELD))
        box.addView(ui.iconCard(Ic.SHIELD, Ui.GOOD, "Enabled for this app",
            "If Google backup is on in your phone's settings, the app data is also backed up by Android and restored when you reinstall or set up a new phone."), ui.lp(2))
        box.addView(ui.tv("Backup files (.carobd) are a ZIP with the database and settings. Older plain database backups can still be restored.", 11.5f, Ui.DIM), ui.lp(12))
    }

    private fun pickCloud() {
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_TITLE, "CarOBD_backup.carobd")
            addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_CLOUD_CREATE)
    }

    private fun saveLocal() {
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_TITLE, Backup.fileName())
        }
        @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_LOCAL_SAVE)
    }

    private fun openFile(req: Int) {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(android.content.Intent.CATEGORY_OPENABLE); type = "*/*" }
        @Suppress("DEPRECATION") a.startActivityForResult(i, req)
    }
}
