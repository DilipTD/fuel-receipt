package com.dilip.carobd

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A vehicle document (registration, insurance policy, PUC …) with an optional attached photo / PDF. */
class VDoc(
    var id: Long = 0, var vid: Long = 0, var type: String = "", var title: String = "", var number: String = "",
    var issuer: String = "", var issued: Long = 0, var expiry: Long = 0, var note: String = "",
    var file: String = "", var mime: String = "", var added: Long = 0
)

class DocType(val name: String, val ic: Ic, val color: Int, val key: Boolean)

object Docs {
    val types = listOf(
        DocType("Registration certificate (RC)", Ic.CAR, Ui.ACC, true),
        DocType("Insurance policy", Ic.SHIELD, Ui.GOOD, true),
        DocType("Pollution certificate (PUC)", Ic.LEAF, Ui.CYAN, true),
        DocType("Driving licence", Ic.USER, Ui.VIOLET, true),
        DocType("Fitness / permit", Ic.FLAG, Ui.ORANGE, false),
        DocType("Warranty / service book", Ic.WRENCH, Ui.WARN, false),
        DocType("Purchase invoice", Ic.DATABASE, Ui.PINK, false),
        DocType("Other", Ic.DOC, Ui.DIM, false)
    )
    fun type(n: String) = types.firstOrNull { it.name == n } ?: types.last()

    fun dir(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)
    fun delete(ctx: Context, name: String) { if (name.isNotEmpty()) try { file(ctx, name).delete() } catch (_: Exception) {} }
    fun purge(ctx: Context, vid: Long) { for (d in Db.docs(vid)) delete(ctx, d.file) }

    private val fmt = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { isLenient = false }
    fun date(t: Long) = if (t <= 0) "" else fmt.format(Date(t))
    fun parse(s: String): Long = try { if (s.isBlank()) 0L else fmt.parse(s.trim())!!.time } catch (_: Exception) { -1L }

    /** Copies a photo (scaled) or a PDF into private storage. Returns (file name, mime) or null. */
    fun save(ctx: Context, uri: Uri): Pair<String, String>? = try {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/")) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > 3200) s *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            if (bmp == null) null else {
                val k = 2000f / maxOf(bmp.width, bmp.height)
                val out = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
                val name = "d_${System.currentTimeMillis()}.jpg"
                file(ctx, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                name to "image/jpeg"
            }
        } else if (mime == "application/pdf") {
            val name = "d_${System.currentTimeMillis()}.pdf"
            var n = 0L
            file(ctx, name).outputStream().use { os -> ctx.contentResolver.openInputStream(uri)?.use { n = it.copyTo(os) } }
            if (n <= 0 || n > 25_000_000) { file(ctx, name).delete(); null } else name to "application/pdf"
        } else null
    } catch (_: Exception) { null }

    /** days until expiry (negative = expired), null when no expiry set. */
    fun days(d: VDoc): Long? = if (d.expiry <= 0) null else Math.floorDiv(d.expiry - System.currentTimeMillis(), 86_400_000L)

    /** (text, level) level: 0 valid, 1 expiring soon, 2 expired, 3 no expiry recorded. */
    fun status(d: VDoc): Pair<String, Int> {
        val n = days(d) ?: return "No expiry set" to 3
        return when {
            n < 0 -> "Expired ${-n} day(s) ago" to 2
            n <= 30 -> "Expires in $n day(s)" to 1
            else -> "Valid until ${date(d.expiry)}" to 0
        }
    }

    fun due(vid: Long): List<VDoc> = Db.docs(vid).filter { (days(it) ?: 999) <= 30 }
}

class DocsPage(a: MainActivity) : Page(a) {
    override val parent = "More"
    companion object { const val REQ_CAM = 95; const val REQ_PICK = 96 }
    private lateinit var box: LinearLayout
    private var camUri: Uri? = null
    private var pend: VDoc? = null
    private var pendAfter: (() -> Unit)? = null

    override fun build(): View { box = ui.col(); return ui.scroll(box) }
    override fun onShow() = render()

    private fun lvlColor(l: Int) = when (l) { 0 -> Ui.GOOD; 1 -> Ui.WARN; 2 -> Ui.BAD; else -> Ui.DIM }

    private fun render() {
        box.removeAllViews()
        val v = ObdManager.vehicle
        val docs = Db.docs(v.id)
        box.addView(ui.tv(v.title, 18f, Ui.WHITE, true), ui.lp(2))
        box.addView(ui.tv("Vehicle identity and a documents folder kept on this phone (included in backups).", 12f, Ui.DIM), ui.lp(2))

        box.addView(ui.section("Vehicle identity", Ic.CAR))
        box.addView(ui.iconCard(Ic.CODE, Ui.ACC, "VIN (chassis number)", if (v.vin.isEmpty()) "Not set – tap Fetch to read it from the car" else v.vin, Ui.CARD), ui.lp(6))
        box.addView(ui.iconCard(Ic.CAR, Ui.CYAN, "Registration number", v.regNo.ifEmpty { "Not set" }, Ui.CARD), ui.lp(6))
        val pol = if (v.policyNo.isEmpty()) "Not set" else listOf(v.insurer, v.policyNo, if (v.policyExpiry > 0) "till ${Docs.date(v.policyExpiry)}" else "").filter { it.isNotBlank() }.joinToString(" · ")
        box.addView(ui.iconCard(Ic.SHIELD, Ui.GOOD, "Insurance policy", pol, Ui.CARD), ui.lp(6))
        box.addView(ui.row(
            ui.btn("Fetch VIN from car", Ui.GOOD) {
                ui.toast("Searching the car for its VIN…")
                ObdManager.fetchVin { found, msg -> ui.toast(if (found != null) "$msg: $found" else msg); render() }
            },
            ui.btn("Edit details", Ui.CARD) { editIdentity() }
        ), ui.lp(8))

        box.addView(ui.section("Verification", Ic.CHECK))
        val keyTypes = Docs.types.filter { it.key }
        val okCount = keyTypes.count { t -> docs.firstOrNull { it.type == t.name }?.let { it.file.isNotEmpty() && Docs.status(it).second <= 1 || it.file.isNotEmpty() && Docs.status(it).second == 3 } == true }
        box.addView(ui.tv("$okCount of ${keyTypes.size} key documents attached and valid", 12f, Ui.DIM), ui.lp(2))
        for (t in keyTypes) {
            val d = docs.firstOrNull { it.type == t.name }
            val (txt, col) = when {
                d == null -> "Missing – tap to add" to Ui.DIM
                else -> {
                    val (s, l) = Docs.status(d)
                    val f = if (d.file.isEmpty()) " · no file attached" else " · file attached ✓"
                    (s + f) to (if (d.file.isEmpty() && l == 0) Ui.WARN else lvlColor(l))
                }
            }
            box.addView(ui.iconCard(t.ic, col, t.name, txt, Ui.CARD) { if (d == null) startAdd(t.name) else viewer(d) }, ui.lp(6))
        }

        box.addView(ui.section("Documents folder", Ic.DOC))
        box.addView(ui.btn("+ Add document", Ui.GOOD) { startAdd(null) }, ui.lp(2))
        if (docs.isEmpty()) box.addView(ui.tv("No documents yet. Add the RC book, insurance policy, PUC and licence as a photo or PDF.", 12f, Ui.DIM), ui.lp(8))
        else box.addView(ui.tv("Swipe a document to the left to delete it", 11f, Ui.DIM), ui.lp(6))
        for (d in docs) {
            val t = Docs.type(d.type)
            val (s, l) = Docs.status(d)
            val file = when { d.file.isEmpty() -> "no file"; d.mime == "application/pdf" -> "PDF attached"; else -> "photo attached" }
            val body = listOf(d.type + if (d.number.isNotBlank()) " · ${d.number}" else "", if (d.issuer.isNotBlank()) d.issuer else "", "$s · $file")
                .filter { it.isNotBlank() }.joinToString("\n")
            val card = ui.iconCard(t.ic, lvlColor(if (l == 3) 0 else l).let { if (l == 3) t.color else it }, d.title.ifBlank { d.type }, body, Ui.CARD) { viewer(d) }
            box.addView(ui.swipeDelete(card, "Delete this document?", "\"${d.title.ifBlank { d.type }}\" and its attached file will be removed from this phone.") {
                Docs.delete(a, d.file); Db.deleteDoc(d.id); render()
            }, ui.lp(8))
        }
        box.addView(ui.tv("Keep the originals safe. Copies here are for quick reference and verification; they are stored only on this phone.", 11f, Ui.DIM).apply { gravity = android.view.Gravity.CENTER }, ui.lp(14))
    }

    private fun editIdentity() {
        val v = ObdManager.vehicle
        val vin = ui.edit("17 characters", v.vin)
        val reg = ui.edit("e.g. KA01AB1234", v.regNo)
        val ins = ui.edit("Insurance company", v.insurer)
        val pol = ui.edit("Policy number", v.policyNo)
        val exp = ui.edit("DD-MM-YYYY", Docs.date(v.policyExpiry))
        formDialog("Vehicle identity", listOf("VIN" to vin, "Registration number" to reg, "Insurance company" to ins, "Policy number" to pol, "Policy expiry" to exp)) {
            v.vin = vin.text.toString().trim().uppercase().replace(" ", "")
            v.regNo = reg.text.toString().trim().uppercase()
            v.insurer = ins.text.toString().trim(); v.policyNo = pol.text.toString().trim()
            val e = Docs.parse(exp.text.toString()); if (e >= 0) v.policyExpiry = e else ui.toast("Policy expiry not saved – use DD-MM-YYYY")
            if (v.vin.isNotEmpty() && v.vin.length != 17) ui.toast("Note: a VIN normally has 17 characters")
            Db.saveVehicle(v); ObdManager.reloadVehicle(); render()
        }
    }

    /* ---- attach + form ---- */

    private fun startAdd(type: String?) {
        val d = VDoc(type = type ?: Docs.types[0].name, vid = ObdManager.vehicle.id)
        chooseFile(d) { form(d) }
    }

    private fun chooseFile(d: VDoc, after: () -> Unit) {
        AlertDialog.Builder(a).setTitle("Attach document").setItems(arrayOf("Take photo", "Choose photo", "Choose PDF", "Skip – no file")) { _, w ->
            pend = d; pendAfter = after
            when (w) {
                0 -> takePhoto()
                1 -> pick("image/*")
                2 -> pick("application/pdf")
                else -> after()
            }
        }.show()
    }

    private fun pick(mime: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime }
        @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_PICK)
    }

    private fun takePhoto() {
        try {
            val cv = ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "carobd_doc_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CarOBD")
            }
            camUri = a.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, camUri)
            @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_CAM)
        } catch (e: Exception) { ui.toast("Camera not available – use Choose photo") }
    }

    fun fileResult(req: Int, ok: Boolean, uri: Uri?) {
        val src = if (req == REQ_CAM) camUri else uri
        val d = pend; val after = pendAfter
        if (!ok || src == null || d == null) { if (req == REQ_CAM) camUri?.let { try { a.contentResolver.delete(it, null, null) } catch (_: Exception) {} }; return }
        val r = Docs.save(a, src)
        if (req == REQ_CAM) try { a.contentResolver.delete(src, null, null) } catch (_: Exception) {}
        if (r == null) { ui.toast("Could not read that file – choose a photo or PDF"); return }
        Docs.delete(a, d.file)
        d.file = r.first; d.mime = r.second
        pend = null; pendAfter = null
        after?.invoke()
    }

    private fun form(d: VDoc) {
        val sp = Spinner(a).apply {
            adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, Docs.types.map { it.name })
            setSelection(Docs.types.indexOfFirst { it.name == d.type }.coerceAtLeast(0))
        }
        val title = ui.edit("e.g. Policy – ICICI Lombard", d.title)
        val num = ui.edit("Document / policy / RC number", d.number)
        val iss = ui.edit("Issuing authority or company", d.issuer)
        val dt = ui.edit("DD-MM-YYYY", Docs.date(d.issued))
        val ex = ui.edit("DD-MM-YYYY", Docs.date(d.expiry))
        val note = ui.edit("Note", d.note)
        val box2 = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        for ((l, v) in listOf("Type" to sp as View, "Title" to title, "Number" to num, "Issued by" to iss, "Issue date" to dt, "Expiry date" to ex, "Note" to note))
            box2.addView(ui.labeled(l, v), ui.lp(6))
        box2.addView(ui.tv(if (d.file.isEmpty()) "No file attached" else if (d.mime == "application/pdf") "PDF attached ✓" else "Photo attached ✓", 12f, if (d.file.isEmpty()) Ui.DIM else Ui.GOOD), ui.lp(8))
        fun apply(): Boolean {
            val i = Docs.parse(dt.text.toString()); val e = Docs.parse(ex.text.toString())
            if (i < 0 || e < 0) { ui.toast("Dates must be DD-MM-YYYY"); return false }
            d.type = Docs.types[sp.selectedItemPosition.coerceAtLeast(0)].name
            d.title = title.text.toString().trim().ifBlank { d.type }
            d.number = num.text.toString().trim(); d.issuer = iss.text.toString().trim()
            d.issued = i; d.expiry = e; d.note = note.text.toString().trim()
            d.vid = ObdManager.vehicle.id; if (d.added == 0L) d.added = System.currentTimeMillis()
            Db.saveDoc(d); sync(d); return true
        }
        AlertDialog.Builder(a).setTitle(if (d.id == 0L) "Add document" else "Edit document").setView(ui.scroll(box2))
            .setPositiveButton("Save") { _, _ -> if (apply()) render() }
            .setNegativeButton("Cancel") { _, _ -> if (d.id == 0L) Docs.delete(a, d.file) }
            .setNeutralButton(if (d.file.isEmpty()) "Attach file" else "Replace file") { _, _ ->
                if (apply()) chooseFile(d) { Db.saveDoc(d); render() }
            }.show()
    }

    /** Keeps the vehicle's registration / policy fields in step with the matching document. */
    private fun sync(d: VDoc) {
        val v = ObdManager.vehicle
        if (d.vid != v.id) return
        when (d.type) {
            Docs.types[0].name -> if (d.number.isNotBlank()) v.regNo = d.number.uppercase()
            Docs.types[1].name -> {
                if (d.number.isNotBlank()) v.policyNo = d.number
                if (d.issuer.isNotBlank()) v.insurer = d.issuer
                if (d.expiry > 0) v.policyExpiry = d.expiry
            }
            else -> return
        }
        Db.saveVehicle(v); ObdManager.reloadVehicle()
    }

    /* ---- viewer ---- */

    private fun viewer(d: VDoc) {
        val (s, _) = Docs.status(d)
        val info = listOf(d.type, if (d.number.isNotBlank()) "Number: ${d.number}" else "", if (d.issuer.isNotBlank()) "Issued by: ${d.issuer}" else "",
            if (d.issued > 0) "Issued: ${Docs.date(d.issued)}" else "", if (d.expiry > 0) "Expiry: ${Docs.date(d.expiry)} ($s)" else "", d.note).filter { it.isNotBlank() }.joinToString("\n")
        val b = AlertDialog.Builder(a).setTitle(d.title.ifBlank { d.type }).setMessage(info)
            .setNegativeButton("Close", null)
            .setNeutralButton("Edit") { _, _ -> form(d) }
        if (d.file.isNotEmpty() && Docs.file(a, d.file).exists()) b.setPositiveButton("View file") { _, _ -> showFile(d) }
        b.show()
    }

    private fun showFile(d: VDoc) {
        val f = Docs.file(a, d.file)
        val list = ui.vbox().apply { setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        val sv = ScrollView(a).apply { addView(list) }
        val dlg = AlertDialog.Builder(a).setTitle(d.title.ifBlank { d.type }).setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Save copy") { _, _ ->
                val ext = if (d.mime == "application/pdf") "pdf" else "jpg"
                val nm = "CarOBD_${d.type.take(12).replace(Regex("[^A-Za-z0-9]"), "")}_${stamp()}.$ext"
                val os = Exporter.open(a, nm, d.mime)
                if (os == null) ui.toast("Could not save") else { os.use { o -> f.inputStream().use { it.copyTo(o) } }; ui.toast("Saved to Downloads/CarOBD/$nm") }
            }.show()
        if (d.mime == "application/pdf") {
            list.addView(ui.tv("Loading pages…", 12f, Ui.DIM))
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            Thread {
                try {
                    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(pfd)
                    val n = minOf(r.pageCount, 12)
                    val wpx = (a.resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtLeast(600)
                    for (i in 0 until n) {
                        val p = r.openPage(i)
                        val bmp = Bitmap.createBitmap(wpx, (wpx.toFloat() * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close()
                        main.post {
                            if (i == 0) list.removeAllViews()
                            list.addView(ImageView(a).apply { setImageBitmap(bmp); adjustViewBounds = true }, ui.lp(6))
                        }
                    }
                    if (r.pageCount > n) main.post { list.addView(ui.tv("Showing first $n of ${r.pageCount} pages – use Save copy for the full file.", 11f, Ui.DIM), ui.lp(6)) }
                    r.close(); pfd.close()
                } catch (e: Exception) { main.post { list.removeAllViews(); list.addView(ui.tv("This PDF cannot be previewed here. Use Save copy and open it with a PDF app.", 12f, Ui.WARN)) } }
            }.start()
        } else {
            val bmp = try { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 1 }) } catch (_: Exception) { null }
            if (bmp == null) list.addView(ui.tv("Image file not found.", 12f, Ui.BAD))
            else list.addView(ImageView(a).apply { setImageBitmap(bmp); adjustViewBounds = true })
        }
    }
}
