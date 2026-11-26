package com.dilip.carobd

import android.app.Activity
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.Locale

/** Interface language. English is built in; Hindi covers the main labels (the rest stays English). */
object Lang {
    val options = listOf("system" to "System default", "en" to "English", "hi" to "हिन्दी (Hindi)")
    val code: String get() {
        val p = Prefs.language
        if (p != "system") return p
        return if (Locale.getDefault().language == "hi") "hi" else "en"
    }
    val locale: Locale get() = if (code == "hi") Locale("hi", "IN") else Locale.getDefault()

    fun t(s: String): String {
        if (code != "hi" || s.isEmpty()) return s
        return HI[s] ?: HI[s.trim()] ?: s
    }

    private val HI = mapOf(
        "Home" to "होम", "Drives" to "ड्राइव", "Fuel" to "ईंधन", "Nav" to "नेविगेट", "Navigate" to "नेविगेट", "More" to "और",
        "My car" to "मेरी कार", "Settings" to "सेटिंग्स", "Save settings" to "सेटिंग्स सहेजें", "Settings saved" to "सेटिंग्स सहेजी गईं",
        "Connect" to "कनेक्ट", "Connection" to "कनेक्शन", "Trips" to "यात्राएँ", "Alerts" to "अलर्ट", "Driving" to "ड्राइविंग",
        "Auto" to "ऑटो", "Data" to "डेटा", "About" to "परिचय", "Garage" to "गैराज", "Fuel log" to "ईंधन लॉग", "Maintenance" to "रखरखाव",
        "Live data" to "लाइव डेटा", "Gauges" to "गेज", "Diagnosis" to "निदान", "Diagnostics" to "निदान", "Drive mode" to "ड्राइव मोड",
        "Car health" to "कार की सेहत", "Driving style" to "ड्राइविंग शैली", "Performance" to "प्रदर्शन", "Battery" to "बैटरी",
        "HUD" to "HUD", "Parking" to "पार्किंग", "Permissions" to "अनुमतियाँ", "Privacy & legal" to "गोपनीयता और कानूनी",
        "Documents" to "दस्तावेज़", "Backup & cloud" to "बैकअप और क्लाउड", "Road assistant" to "रोड सहायक", "Tools" to "उपकरण",
        "Driving replay" to "ड्राइविंग रीप्ले", "Trip details" to "यात्रा विवरण", "Fill-up" to "फ़िल-अप", "Alert centre" to "अलर्ट केंद्र",
        "Display & units" to "डिस्प्ले और इकाइयाँ", "Corrections" to "सुधार", "Language" to "भाषा", "Theme" to "थीम", "Units" to "इकाइयाँ",
        "System" to "सिस्टम", "Light" to "लाइट", "Dark" to "डार्क", "Currency" to "मुद्रा", "Temperature" to "तापमान", "Pressure" to "दबाव",
        "Torque" to "टॉर्क", "Distance" to "दूरी", "Speed" to "गति", "Time format" to "समय प्रारूप", "Economy unit" to "माइलेज इकाई",
        "Fuel volume" to "ईंधन मात्रा", "FAQ" to "सामान्य प्रश्न", "Help & FAQ" to "मदद और सामान्य प्रश्न", "Calculator" to "कैलकुलेटर",
        "Trip cost" to "यात्रा खर्च", "Consumption" to "खपत", "Required fuel" to "ज़रूरी ईंधन", "All" to "सभी", "General" to "सामान्य",
        "Function" to "फ़ंक्शन", "Troubleshooting" to "समस्या निवारण", "Data & privacy" to "डेटा और गोपनीयता",
        "Speed correction" to "गति सुधार", "Distance measurement" to "दूरी मापन", "Battery voltage measurement" to "बैटरी वोल्टेज मापन",
        "Fuel efficiency correction" to "ईंधन दक्षता सुधार", "Calculate" to "गणना करें", "Result" to "परिणाम", "Total cost" to "कुल खर्च",
        "Fuel price" to "ईंधन मूल्य", "Fuel needed" to "ईंधन चाहिए", "Cancel" to "रद्द करें", "Delete" to "हटाएँ", "Save" to "सहेजें",
        "Edit" to "संपादित करें", "Add" to "जोड़ें", "Share" to "साझा करें", "Search" to "खोजें", "Events on this drive" to "इस ड्राइव की घटनाएँ",
        "Rapid acceleration" to "तेज़ त्वरण", "Rapid deceleration (hard braking)" to "तेज़ मंदन (हार्ड ब्रेकिंग)", "Sudden braking" to "अचानक ब्रेक",
        "Over speed" to "ओवर स्पीड", "Engine" to "इंजन", "Coolant" to "कूलेंट", "Distance driven" to "तय की गई दूरी",
        "Service history" to "सर्विस इतिहास", "Home Assistant" to "होम असिस्टेंट", "Custom PIDs" to "कस्टम PID", "Connecting…" to "कनेक्ट हो रहा है…",
        "Connected" to "कनेक्ट हो गया", "Disconnected" to "डिस्कनेक्ट", "Start" to "शुरू", "Stop" to "रोकें", "Reset" to "रीसेट"
    )

    fun ttsLocale(): Locale = locale
}

/** System / light / dark appearance. Light uses a hue-preserving colour inversion on the root view. */
object Theme {
    val options = listOf("system" to "System setting", "light" to "Light mode", "dark" to "Dark mode")
    private val INV = floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
    var applied = ""

    fun isLight(act: Activity): Boolean = when (Prefs.theme) {
        "light" -> true
        "system" -> (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    private fun paint() = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(INV)) }

    fun applyRoot(act: Activity, root: View) {
        val light = isLight(act)
        applied = Prefs.theme + Prefs.language
        if (light) root.setLayerType(View.LAYER_TYPE_HARDWARE, paint())
        else root.setLayerType(View.LAYER_TYPE_NONE, null)
        val flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        act.window.decorView.systemUiVisibility = if (light) act.window.decorView.systemUiVisibility or flag
        else act.window.decorView.systemUiVisibility and flag.inv()
        act.window.statusBarColor = if (light) android.graphics.Color.WHITE else Ui.BG
        act.window.navigationBarColor = if (light) android.graphics.Color.WHITE else Ui.CARD
    }

    /** Maps and photos must not be inverted: apply the same (self-inverse) filter to cancel the root filter. */
    fun counter(act: Activity, v: View) {
        if (!isLight(act)) return
        if (v is TileMapView || (v is ImageView && v.drawable is BitmapDrawable)) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, paint())
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) counter(act, v.getChildAt(i))
    }
}
