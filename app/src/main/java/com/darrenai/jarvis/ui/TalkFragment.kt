package com.darrenai.jarvis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.darrenai.jarvis.DeviceActions
import com.darrenai.jarvis.JarvisRouter
import com.darrenai.jarvis.MemoryVault
import com.darrenai.jarvis.OmniClient
import com.darrenai.jarvis.R
import com.darrenai.jarvis.SignalBus
import com.darrenai.jarvis.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talk: realtime voice loop.
 *
 * - Hold-to-talk (default): hold the button and speak.
 * - Hands-free: keeps listening turn after turn, like an open mic.
 * - Replies stream in (SSE) and speak sentence-by-sentence, so first
 *   audio plays ~1-2s into generation instead of waiting.
 * - Vault recall is injected into every turn, so it remembers.
 */
class TalkFragment : Fragment(), VoiceEngine.TurnCallback {

    private lateinit var engine: VoiceEngine
    private lateinit var vault: MemoryVault
    private val history = mutableListOf<Pair<String, String>>()
    private var handsFree = false
    private var streamJob: Job? = null
    private var streamBuffer = StringBuilder()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) setStateLabel("microphone denied")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_talk, container, false)

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        engine = VoiceEngine.get(requireContext())
        vault = MemoryVault(File(requireContext().filesDir, "vault"))

        val ptt = view.findViewById<Button>(R.id.btn_ptt)
        ptt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasMic()) {
                        engine.interrupt()
                        engine.beginTurn(this)
                    } else {
                        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    engine.endTurn()
                    true
                }
                else -> false
            }
        }

        val hf = view.findViewById<Button>(R.id.btn_handsfree)
        hf?.setOnClickListener {
            handsFree = !handsFree
            hf.text = if (handsFree) "HANDS-FREE: ON" else "HANDS-FREE: OFF"
            if (handsFree && hasMic()) {
                engine.beginTurn(this)
            } else {
                engine.endTurn()
            }
        }
    }

    private fun hasMic(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setStateLabel(s: String) {
        view?.findViewById<TextView>(R.id.txt_talk_state)?.text = s
    }

    private fun log(s: String) {
        view?.findViewById<TextView>(R.id.txt_talk_log)?.let { tv ->
            val lines = (tv.text.toString().split("\n") + s).takeLast(6)
            tv.text = lines.joinToString("\n")
        }
    }

    private fun client(): OmniClient {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return OmniClient(
            endpoint = prefs.getString("endpoint", OmniClient.DEFAULT_ENDPOINT)
                ?: OmniClient.DEFAULT_ENDPOINT,
            apiKey = prefs.getString("api_key", "") ?: ""
        )
    }

    private fun router(): JarvisRouter {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val ep = prefs.getString("endpoint", OmniClient.DEFAULT_ENDPOINT)
            ?: OmniClient.DEFAULT_ENDPOINT
        // Settings stores the full chat URL; the router wants the base.
        val base = ep.replace("/v1/chat/completions", "").ifBlank { JarvisRouter.DEFAULT_PRIMARY }
        return JarvisRouter(
            primaryBaseUrl = base,
            apiKey = prefs.getString("api_key", "") ?: ""
        )
    }

    private fun ttsOn(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("tts_enabled", true)
    }

    private fun nextTurn() {
        if (!isAdded) return
        if (handsFree && hasMic() && !engine.isSpeaking()) {
            engine.beginTurn(this)
        } else {
            setStateLabel("idle")
        }
    }

    // ---- TurnCallback ----

    override fun onTranscript(text: String) {
        if (!isAdded) return
        setStateLabel("thinking")
        runCatching { SignalBus.setState(SignalBus.THINKING) }
        log("you: $text")
        vault.appendTurn("user", text)

        // Local device commands resolve instantly — Jarvis acts, not just chats.
        val local = DeviceActions.parseCommand(text)
        if (local != null) {
            val answer = DeviceActions.execute(requireContext(), local, vault)
            history.add("user" to text)
            history.add("assistant" to answer)
            vault.appendTurn("jarvis", answer)
            log("jarvis [device]: ${answer.take(120)}")
            setStateLabel("speaking")
            if (ttsOn()) engine.speak(answer) { nextTurn() } else nextTurn()
            return
        }

        history.add("user" to text)
        val device = DeviceActions.snapshot(requireContext())
        val memory = listOf(vault.recall(text), device).filter { it.isNotBlank() }
            .joinToString("\n")

        streamJob?.cancel()
        streamBuffer = StringBuilder()
        streamJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val full = StringBuilder()
            var via = ""
            router().chatStream(
                messages = history.toList(),
                systemExtra = memory,
                onBackend = { via = it },
                onDelta = { piece ->
                    val sentences: List<String>
                    synchronized(streamBuffer) {
                        streamBuffer.append(piece)
                        val (done, rest) = OmniClient.splitSentences(streamBuffer.toString())
                        streamBuffer = StringBuilder(rest)
                        sentences = done
                    }
                    full.append(piece)
                    if (ttsOn()) {
                        withContext(Dispatchers.Main) {
                            sentences.forEach { engine.speakSentence(it) }
                        }
                    }
                },
                onDone = { text ->
                    full.clear().append(text)
                    history.add("assistant" to text)
                    vault.appendTurn("jarvis", text)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        log("jarvis [$via]: ${text.take(120)}")
                        setStateLabel("speaking")
                        val (_, tail) = synchronized(streamBuffer) {
                            OmniClient.splitSentences(streamBuffer.toString())
                        }
                        if (ttsOn()) {
                            engine.finishStream(tail) { nextTurn() }
                        } else {
                            runCatching { SignalBus.setState(SignalBus.IDLE) }
                            nextTurn()
                        }
                    }
                },
                onError = { err ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        runCatching { SignalBus.setState(SignalBus.IDLE) }
                        setStateLabel("idle")
                        log("error: ${err.take(120)}")
                        if (handsFree) {
                            // Brief pause, then keep the mic open.
                            view?.postDelayed({ nextTurn() }, 1500)
                        }
                    }
                }
            )
        }
    }

    override fun onError(error: String) {
        if (!isAdded) return
        setStateLabel("idle")
        log(error)
        if (handsFree && isAdded) {
            view?.postDelayed({ nextTurn() }, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        setStateLabel(SignalBus.currentState())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handsFree = false
        streamJob?.cancel()
        engine.endTurn()
        engine.interrupt()
    }
}
