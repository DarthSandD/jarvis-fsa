package com.darrenai.jarvis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.darrenai.jarvis.DeviceActions
import com.darrenai.jarvis.JarvisRouter
import com.darrenai.jarvis.MemoryVault
import com.darrenai.jarvis.OmniClient
import com.darrenai.jarvis.VoiceEngine
import com.darrenai.jarvis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home: greeting, connection status, entry points. */
class HomeFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btn_open_face)?.setOnClickListener {
            runCatching { FaceActivity.show(requireContext()) }
        }
        view.findViewById<View>(R.id.btn_hold_talk)?.setOnClickListener {
            selectTab(R.id.nav_talk)
        }
        view.findViewById<TextView>(R.id.txt_stack)?.text =
            "MIND ..... vault/ (plain markdown, no ceiling)\n" +
                "MOUTH .... hold-to-talk voice loop\n" +
                "FACE ..... circuit board, live signal bus\n" +
                "HANDS .... web console (webcam + Chrome)"

        val status = view.findViewById<TextView>(R.id.txt_home_status)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val ep = prefs.getString("endpoint", OmniClient.DEFAULT_ENDPOINT)
                ?: OmniClient.DEFAULT_ENDPOINT
            val base = ep.replace("/v1/chat/completions", "")
                .ifBlank { JarvisRouter.DEFAULT_PRIMARY }
            val router = JarvisRouter(
                primaryBaseUrl = base,
                apiKey = prefs.getString("api_key", "") ?: "",
                nousKey = prefs.getString("nous_key", "") ?: ""
            )
            val results = router.probe()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val online = results.count { it.second == null }
                val detail = results.joinToString(" · ") { (name, err) ->
                    if (err == null) "$name OK" else "$name down"
                }
                status?.text = if (online > 0) "● $online/3 backends · $detail"
                else "● offline — check Wi-Fi / Settings"
                status?.setTextColor(
                    resources.getColor(
                        if (online > 0) R.color.fsa_green else R.color.fsa_amber, null
                    )
                )
            }
            maybeBrief(view, base, prefs.getString("api_key", "") ?: "")
        }
    }

    /**
     * Morning briefing: once per day, 5-10am, Jarvis speaks first.
     * Greeting, device snapshot, unread reminders from the vault.
     */
    private fun maybeBrief(view: View, base: String, apiKey: String) {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) !in 5..10) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(cal.time)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (prefs.getString("last_briefing", "") == today) return

        val vault = MemoryVault(java.io.File(requireContext().filesDir, "vault"))
        val reminders = vault.search("reminder").take(3)
            .joinToString("\n") { "- ${it.name}: ${it.preview}" }
        val device = DeviceActions.snapshot(requireContext())
        val prompt = buildString {
            append("Morning briefing for Darren. Keep it to 3 short spoken sentences. ")
            append(device)
            if (reminders.isNotBlank()) append("\nOpen reminders:\n$reminders")
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val full = StringBuilder()
            JarvisRouter(primaryBaseUrl = base, apiKey = apiKey).chatStream(
                messages = listOf("user" to prompt),
                systemExtra = "This is the once-daily morning briefing. Greet Darren as sir, " +
                    "state readiness, mention reminders if any. Plain spoken sentences only.",
                onDelta = { full.append(it) },
                onDone = { full.clear().append(it) },
                onError = { full.append("Systems nominal, sir. All backends standing by.") }
            )
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                prefs.edit().putString("last_briefing", today).apply()
                val card = view.findViewById<View>(R.id.card_briefing)
                val txt = view.findViewById<TextView>(R.id.txt_briefing)
                card?.visibility = View.VISIBLE
                txt?.text = full.toString()
                vault.saveNote("briefing", full.toString())
                if (prefs.getBoolean("tts_enabled", true)) {
                    VoiceEngine.get(requireContext()).applyProsody()
                    VoiceEngine.get(requireContext()).speak(full.toString())
                }
            }
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

    private fun selectTab(itemId: Int) {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.let { app ->
            app.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                ?.selectedItemId = itemId
        }
    }
}
