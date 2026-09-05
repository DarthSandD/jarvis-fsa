package com.darrenai.jarvis

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * The mouth — ports backtalk's voice loop to Android.
 *
 * Flow (same as backtalk): hold to talk -> STT -> agent turn ->
 * spoken reply as it completes. Typing a message is a first-class
 * turn too and interrupts speech, like the original.
 *
 * SignalBus drives the face: listening -> thinking -> speaking -> idle.
 * Every bus write is wrapped: the bus never crashes a turn.
 */
class VoiceEngine private constructor(private val appContext: Context) {

    interface TurnCallback {
        fun onTranscript(text: String)
        fun onError(error: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var callback: TurnCallback? = null
    @Volatile private var listening = false
    @Volatile private var speaking = false
    private var pendingDone: (() -> Unit)? = null
    private var waveTicks = 0

    // Synthesized waveform while speaking (drives the face ring).
    private val waveRunner = object : Runnable {
        override fun run() {
            if (!speaking) return
            waveTicks++
            val t = waveTicks / 10.0
            val arr = FloatArray(64) { i ->
                ((kotlin.math.sin(i * 0.55 + t * 9) * 0.6 +
                    kotlin.math.sin(i * 1.7 - t * 13) * 0.4) * 0.7).toFloat()
            }
            runCatching {
                SignalBus.setLevel(0.65f)
                SignalBus.setSamples(arr)
            }
            main.postDelayed(this, 100)
        }
    }

    companion object {
        @Volatile private var instance: VoiceEngine? = null

        fun get(context: Context): VoiceEngine {
            return instance ?: synchronized(this) {
                instance ?: VoiceEngine(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Turns LLM markdown output into plain speakable sentences.
         * The single biggest "robotic voice" fix: raw markdown makes TTS
         * read asterisks, hashes and emoji aloud.
         */
        fun sanitizeForSpeech(raw: String): String {
            var s = raw
            // Strip code fences and inline code, keep the words
            s = s.replace(Regex("```[\\s\\S]*?```"), " ")
            s = s.replace("`", "")
            // Images/links -> link text only
            s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("\\[([^\\]]+)]\\(([^)]*)\\)"), "$1")
            // Bold/italic/strike markers
            s = s.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            s = s.replace(Regex("__(.*?)__"), "$1")
            s = s.replace(Regex("(?<!\\w)\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)"), "$1")
            s = s.replace(Regex("(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)"), "$1")
            s = s.replace("~~", "")
            // Headings -> sentence pause
            s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
            // Bullets/numbered lists -> sentence breaks
            s = s.replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
            s = s.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
            // Blockquotes, tables, rules
            s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
            s = s.replace("|", " ")
            s = s.replace(Regex("(?m)^[\\s:|-]+\$"), " ")
            // Raw URLs / bare domains
            s = s.replace(Regex("https?://\\S+"), "")
            s = s.replace(Regex("\\b[\\w-]+\\.(com|net|org|io|ai|id)\\b\\S*"), "")
            // Emoji and misc symbols TTS mangles
            s = s.replace(Regex("[\\p{So}\\p{Sk}\\u2600-\\u27BF\\uFE0F]"), "")
            s = s.replace("&", " and ")
            s = s.replace(Regex("\\s*/\\s*"), ", ")
            // Collapse leftovers into clean sentence flow
            s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            s = s.replace(Regex("\\n{2,}"), ". ")
            s = s.replace("\n", ", ")
            s = s.replace(Regex("\\s+([.,;:!?])"), "$1")
            s = s.replace(Regex("([.,;:!?]){2,}"), "$1")
            s = s.replace(Regex("\\s+"), " ").trim()
            return s
        }
    }

    init {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    pickBestVoice()
                    applyProsody()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onError(id: String?) { finishSpeaking() }
                        override fun onDone(id: String?) { finishSpeaking() }
                    })
                    ttsReady = true
                }
            }
        }
    }

    /** Prefer a masculine English voice — closest to Jarvis on stock Android. */
    private fun pickBestVoice() {
        val engine = tts ?: return
        val voices = try { engine.voices?.toList() ?: emptyList() } catch (e: Exception) { return }
        if (voices.isEmpty()) return
        fun score(v: android.speech.tts.Voice): Int {
            val locale = v.locale ?: return -100
            if (locale.language != "en") return -100
            if (v.isNetworkConnectionRequired) return -50
            val n = v.name.lowercase()
            var s = 0
            if (locale.country.equals("GB", true)) s += 2
            if (listOf("male", "man", "daniel", "george", "david", "james", "brian")
                    .any { it in n }) s += 3
            runCatching { if (v.quality >= 400) s += 1 }
            runCatching { if (!v.isNetworkConnectionRequired) s += 1 }
            return s
        }
        val best = voices.maxByOrNull { score(it) } ?: return
        if (score(best) < 0) return
        try { engine.voice = best } catch (e: Exception) { }
    }

    /** Pitch/rate from Settings (voice_pitch, voice_rate as decimal strings). */
    fun applyProsody() {
        val prefs = try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        } catch (e: Exception) { return }
        val rate = prefs.getString("voice_rate", "0.95")?.toFloatOrNull() ?: 0.95f
        val pitch = prefs.getString("voice_pitch", "0.85")?.toFloatOrNull() ?: 0.85f
        try {
            tts?.setSpeechRate(rate.coerceIn(0.5f, 1.5f))
            tts?.setPitch(pitch.coerceIn(0.5f, 1.5f))
        } catch (e: Exception) { }
    }

    fun isRecognitionAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    }.getOrDefault(false)

    fun isListening(): Boolean = listening
    fun isSpeaking(): Boolean = speaking

    /** Hold-to-talk: begin a turn. */
    fun beginTurn(cb: TurnCallback) {
        runOnMain {
            callback = cb
            if (!isRecognitionAvailable()) {
                cb.onError("Speech recognition not available on this device")
                return@runOnMain
            }
            destroyRecognizer()
            listening = true
            runCatching { SignalBus.setState(SignalBus.LISTENING) }
            val rec = try {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            } catch (e: Exception) {
                listening = false
                cb.onError("Could not start microphone")
                return@runOnMain
            }
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    listening = false
                    destroyRecognizer()
                    runCatching { SignalBus.setState(SignalBus.IDLE) }
                    cb.onError(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error — check connection"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
                            SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again"
                            else -> "Voice error: $error"
                        }
                    )
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim() ?: ""
                    destroyRecognizer()
                    if (text.isNotEmpty()) cb.onTranscript(text)
                    else {
                        runCatching { SignalBus.setState(SignalBus.IDLE) }
                        cb.onError("No speech detected")
                    }
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(e: Int, p: Bundle?) {}
            })
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                rec.startListening(intent)
            } catch (e: Exception) {
                listening = false
                destroyRecognizer()
                cb.onError("Could not start microphone")
            }
        }
    }

    /** Release-to-talk / cancel. */
    fun endTurn() {
        runOnMain {
            listening = false
            runCatching { recognizer?.stopListening() }
            destroyRecognizer()
        }
    }

    /** Interrupt speech (typing interrupts, like backtalk). */
    fun interrupt() {
        runOnMain {
            try { tts?.stop() } catch (e: Exception) { }
            finishSpeaking()
        }
    }

    /** Speak a completed reply; drives the bus while audio plays. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        runOnMain {
            val clean = sanitizeForSpeech(text)
            if (clean.isBlank()) {
                onDone()
                return@runOnMain
            }
            if (speaking) interrupt()
            if (!ttsReady || tts == null) {
                onDone()
                return@runOnMain
            }
            speaking = true
            pendingDone = onDone
            runCatching { SignalBus.setState(SignalBus.SPEAKING) }
            waveTicks = 0
            main.post(waveRunner)
            scope.launch {
                kotlinx.coroutines.delay(20000)
                if (speaking) {
                    try { tts?.stop() } catch (e: Exception) { }
                    finishSpeaking()
                }
            }
            try {
                tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            } catch (e: Exception) {
                finishSpeaking()
            }
        }
    }

    /**
     * Enqueue one streamed sentence for immediate speech (QUEUE_ADD).
     * Call as sentences complete during streaming; call [finishStream]
     * with the tail remainder when the stream ends. First audio plays
     * ~1-2s into generation instead of waiting for the full reply.
     */
    private var streamPending = 0

    fun speakSentence(sentence: String) {
        runOnMain {
            val clean = sanitizeForSpeech(sentence)
            if (clean.isBlank() || !ttsReady || tts == null) return@runOnMain
            if (!speaking) {
                speaking = true
                runCatching { SignalBus.setState(SignalBus.SPEAKING) }
                waveTicks = 0
                main.post(waveRunner)
            }
            streamPending++
            try {
                tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            } catch (e: Exception) {
                streamPending = maxOf(0, streamPending - 1)
            }
        }
    }

    fun finishStream(tail: String, onDone: () -> Unit = {}) {
        runOnMain {
            if (tail.isNotBlank() && ttsReady && tts != null) {
                streamPending++
                try {
                    tts?.speak(tail, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                } catch (e: Exception) {
                    streamPending = maxOf(0, streamPending - 1)
                }
            }
            pendingDone = onDone
            scope.launch {
                // Wait for the queue to drain: poll speaking state via TTS.
                var waited = 0
                while (speaking && waited < 60000) {
                    kotlinx.coroutines.delay(500)
                    waited += 500
                    try {
                        if (tts?.isSpeaking == false) {
                            // Give callbacks a beat, then close out.
                            kotlinx.coroutines.delay(800)
                            if (tts?.isSpeaking == false) break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                finishSpeaking()
            }
        }
    }

    private fun finishSpeaking() {
        main.post {
            speaking = false
            main.removeCallbacks(waveRunner)
            runCatching {
                SignalBus.setState(SignalBus.IDLE)
                SignalBus.setLevel(0f)
            }
            val done = pendingDone
            pendingDone = null
            done?.invoke()
        }
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post(block)
    }
}
