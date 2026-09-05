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
import com.darrenai.jarvis.MemoryVault
import com.darrenai.jarvis.OmniClient
import com.darrenai.jarvis.R
import com.darrenai.jarvis.SignalBus
import com.darrenai.jarvis.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talk: backtalk-style hold-to-talk voice loop.
 * Hold the button and speak -> transcription -> agent turn ->
 * spoken reply as it completes.
 */
class TalkFragment : Fragment(), VoiceEngine.TurnCallback {

    private lateinit var engine: VoiceEngine
    private lateinit var vault: MemoryVault
    private val history = mutableListOf<Pair<String, String>>()

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

    private fun ttsOn(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("tts_enabled", true)
    }

    // ---- TurnCallback ----

    override fun onTranscript(text: String) {
        if (!isAdded) return
        setStateLabel("thinking")
        runCatching { SignalBus.setState(SignalBus.THINKING) }
        log("you: $text")
        vault.appendTurn("user", text)
        history.add("user" to text)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val res = client().chat(history)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (res) {
                    is OmniClient.Result.Ok -> {
                        history.add("assistant" to res.text)
                        vault.appendTurn("jarvis", res.text)
                        log("jarvis: ${res.text.take(120)}")
                        setStateLabel("speaking")
                        if (ttsOn()) {
                            engine.speak(res.text) {
                                if (isAdded) setStateLabel("idle")
                            }
                        } else {
                            runCatching { SignalBus.setState(SignalBus.IDLE) }
                            setStateLabel("idle")
                        }
                    }
                    is OmniClient.Result.Err -> {
                        runCatching { SignalBus.setState(SignalBus.IDLE) }
                        setStateLabel("idle")
                        log("error: ${res.message.take(120)}")
                    }
                }
            }
        }
    }

    override fun onError(error: String) {
        if (!isAdded) return
        setStateLabel("idle")
        log(error)
    }

    override fun onResume() {
        super.onResume()
        setStateLabel(SignalBus.currentState())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        engine.endTurn()
    }
}
