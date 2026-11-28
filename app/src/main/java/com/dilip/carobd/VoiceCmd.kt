package com.dilip.carobd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Voice commands: "report pothole", "speed breaker", "speed report"… Uses the phone's own speech recogniser (offline when the phone has the language pack). */
object VoiceCmd {
    const val REQ = 42
    private var rec: SpeechRecognizer? = null
    @Volatile var listening = false

    private val words = listOf(
        "pothole" to listOf("pothole", "pot hole", "hole"),
        "bump" to listOf("speed breaker", "breaker", "bump", "hump", "speed bump"),
        "curve" to listOf("curve", "sharp turn", "bend"),
        "camera" to listOf("camera", "speed camera"),
        "school" to listOf("school"),
        "rail" to listOf("rail", "railway", "crossing", "level crossing"),
        "accident" to listOf("accident", "crash"),
        "flood" to listOf("flood", "water", "waterlogging", "waterlogged"),
        "work" to listOf("road work", "roadwork", "construction", "work"),
        "animal" to listOf("animal", "cattle", "cow", "dog", "animals"),
        "police" to listOf("police", "checking", "checkpost"),
        "fog" to listOf("fog", "mist"),
        "narrow" to listOf("narrow", "bridge"),
        "hospital" to listOf("hospital"),
        "toll" to listOf("toll"),
    )

    /** Returns the spoken reply for a recognised phrase (also performs the action). */
    fun handle(text: String): String {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return "I did not hear anything"
        val askSpeed = listOf("speed report", "my speed", "how fast", "speed limit", "what is the speed", "what's the speed", "report speed", "current speed", "limit").any { t.contains(it) } ||
                t == "speed"
        val kind = words.firstOrNull { (_, ws) -> ws.any { t.contains(it) } }?.first
        val wantsReport = t.contains("report") || t.contains("mark") || t.contains("there is") || t.contains("there's") || t.contains("save") || t.contains("hazard")
        return when {
            kind != null && (wantsReport || !askSpeed) -> HazardQuick.save(kind)
            askSpeed -> speedReport()
            t.contains("hazard") -> { "Say the hazard, for example pothole or speed breaker" }
            else -> "I did not understand: $text"
        }
    }

    fun speedReport(): String {
        val spd = RoadAssist.speedKmh
        val lim = RoadAssist.info?.limit
        val shown = if (SignEngine.lastLimit > 0 && System.currentTimeMillis() - SignEngine.lastLimitAt < 20_000) SignEngine.lastLimit else lim
        val sp = Units.sStr(spd)
        return if (shown != null) "Speed $sp. Limit ${Units.sStr(shown.toDouble())}" + (if (spd > shown + Prefs.speedMargin) ". You are over the limit" else "") else "Speed $sp. No limit known here"
    }

    private fun say(a: MainActivity, msg: String) {
        a.runOnUiThread { try { a.ui.toast(msg) } catch (_: Throwable) {} }
        try { Alerts.announce("voice-reply-${System.currentTimeMillis() / 1500}", msg, 0) } catch (_: Throwable) {}
    }

    /** Listens once. Asks for the microphone the first time it is used (never during setup). */
    fun listen(a: MainActivity) {
        if (listening) return
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ)
            a.ui.toast("Allow the microphone, then tap the voice button again")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(a)) { say(a, "No speech recogniser on this phone. Use the hazard buttons instead."); return }
        try {
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(a); rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { listening = true; a.ui.toast("Listening… say “report pothole” or “speed report”") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { listening = false; say(a, if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "I did not hear a command" else "Voice not available (error $e)") }
                override fun onResults(b: Bundle?) {
                    listening = false
                    val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    var reply = "I did not hear a command"
                    if (!list.isNullOrEmpty()) {
                        reply = handle(list[0])
                        if (reply.startsWith("I did not understand")) list.drop(1).firstNotNullOfOrNull { h -> handle(h).takeIf { !it.startsWith("I did not understand") } }?.let { reply = it }
                    }
                    say(a, reply)
                }
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (e: Throwable) { listening = false; say(a, "Voice command failed: ${e.message}") }
    }

    fun stop() { try { rec?.destroy() } catch (_: Throwable) {}; rec = null; listening = false }
}
