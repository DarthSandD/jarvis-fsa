package com.darrenai.jarvis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darrenai.jarvis.DeviceActions
import com.darrenai.jarvis.AgentLoop
import com.darrenai.jarvis.ApprovalGate
import com.darrenai.jarvis.TermuxBridge
import com.darrenai.jarvis.JarvisRouter
import com.darrenai.jarvis.WebTools
import com.darrenai.jarvis.MemoryVault
import com.darrenai.jarvis.OmniClient
import com.darrenai.jarvis.R
import com.darrenai.jarvis.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat: typing is a first-class turn (like backtalk) — same
 * conversation, spoken reply. Typing while it talks interrupts it.
 */
class ChatFragment : Fragment() {

    data class Msg(val text: String, val isUser: Boolean, val ts: Long = System.currentTimeMillis())

    private val messages = mutableListOf<Msg>()
    private lateinit var adapter: ChatAdapter
    private lateinit var vault: MemoryVault

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vault = MemoryVault(File(requireContext().filesDir, "vault"))

        adapter = ChatAdapter(messages)
        view.findViewById<RecyclerView>(R.id.recycler_chat)?.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = this@ChatFragment.adapter
        }
        updateEmpty(view)

        val edit = view.findViewById<EditText>(R.id.edit_message)
        view.findViewById<Button>(R.id.btn_send)?.setOnClickListener {
            val text = edit.text.toString().trim()
            if (text.isNotEmpty()) {
                VoiceEngine.get(requireContext()).interrupt()
                send(view, text)
                edit.text.clear()
            }
        }
    }

    private fun send(view: View, text: String) {
        messages.add(Msg(text, true))
        runCatching { adapter.notifyItemInserted(messages.size - 1) }
        updateEmpty(view)
        scroll(view)
        vault.appendTurn("user", text)

        // Agentic loop: "agent: <goal>" plans + acts across tools, self-verifies.
        if (text.startsWith(AgentLoop.PREFIX, ignoreCase = true)) {
            runAgent(view, text.substringAfter(":").trim().ifBlank { text })
            return
        }

        // Local device commands bypass the LLM — instant action.
        val local = DeviceActions.parseCommand(text)
        if (local != null) {
            val answer = DeviceActions.execute(requireContext(), local, vault)
            messages.add(Msg(answer, false))
            runCatching { adapter.notifyItemInserted(messages.size - 1) }
            updateEmpty(view)
            scroll(view)
            vault.appendTurn("jarvis", answer)
            view.findViewById<TextView>(R.id.txt_chat_model)?.text = "device · local"
            if (ttsOn()) VoiceEngine.get(requireContext()).speak(answer)
            return
        }

        val history = messages.map { (if (it.isUser) "user" else "assistant") to it.text }
        val web = WebTools.parseIntent(text)?.let { WebTools.fetch(it) }
        val memory = listOf(vault.recall(text), DeviceActions.snapshot(requireContext()), web ?: "")
            .filter { it.isNotBlank() }.joinToString("\n")
        // Placeholder bubble fills in live as the stream arrives.
        messages.add(Msg("…", false))
        val aiIdx = messages.size - 1
        runCatching { adapter.notifyItemInserted(aiIdx) }
        scroll(view)
        val shown = StringBuilder()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            router().chatStream(
                messages = history,
                systemExtra = memory,
                onBackend = { via ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        view.findViewById<TextView>(R.id.txt_chat_model)?.text =
                            "darren-1212 · $via"
                    }
                },
                onDelta = { piece ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        shown.append(piece)
                        messages[aiIdx] = Msg(shown.toString(), false, messages[aiIdx].ts)
                        runCatching { adapter.notifyItemChanged(aiIdx) }
                        scroll(view)
                    }
                },
                onDone = { full ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        messages[aiIdx] = Msg(full, false, messages[aiIdx].ts)
                        runCatching { adapter.notifyItemChanged(aiIdx) }
                        vault.appendTurn("jarvis", full)
                        if (ttsOn()) VoiceEngine.get(requireContext()).speak(full)
                        scroll(view)
                    }
                },
                onError = { err ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        messages[aiIdx] = Msg("⚠️ $err", false, messages[aiIdx].ts)
                        runCatching { adapter.notifyItemChanged(aiIdx) }
                        scroll(view)
                    }
                }
            )
        }
    }

    /** Approval dialog: suspends until Darren taps Allow or Deny. Dismiss = deny. */
    private suspend fun askApproval(cmd: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val act = activity
            if (act == null || !isAdded) {
                cont.resume(false) {}
                return@suspendCancellableCoroutine
            }
            act.runOnUiThread {
                runCatching {
                    android.app.AlertDialog.Builder(act)
                        .setTitle("Agent requests shell")
                        .setMessage(cmd.take(500))
                        .setCancelable(false)
                        .setPositiveButton("Allow once") { d, _ ->
                            d.dismiss()
                            if (cont.isActive) cont.resume(true) {}
                        }
                        .setNegativeButton("Deny") { d, _ ->
                            d.dismiss()
                            if (cont.isActive) cont.resume(false) {}
                        }
                        .setOnCancelListener {
                            if (cont.isActive) cont.resume(false) {}
                        }
                        .show()
                }.getOrElse {
                    if (cont.isActive) cont.resume(false) {}
                }
            }
        }

    /** Agentic run: ReAct loop with on-device tools, progress shown live. */
    private fun runAgent(view: View, goal: String) {
        messages.add(Msg("◌ agent working: $goal", false))
        val aiIdx = messages.size - 1
        runCatching { adapter.notifyItemInserted(aiIdx) }
        scroll(view)

        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val bridge = TermuxBridge(
            baseUrl = prefs.getString("bridge_url", TermuxBridge.DEFAULT_URL)
                ?: TermuxBridge.DEFAULT_URL,
            token = prefs.getString("bridge_token", "") ?: ""
        )
        val gate = ApprovalGate()
        val tools: Map<String, suspend (String) -> String> = mapOf(
            "device_state" to { DeviceActions.snapshot(ctx).ifBlank { "no device snapshot" } },
            "device_act" to { cmd ->
                val intent = DeviceActions.parseCommand(cmd)
                if (intent == null) "not a device command; try chat instead"
                else DeviceActions.execute(ctx, intent, vault)
            },
            "memory_search" to { q ->
                vault.search(q).take(3).joinToString("\n") { "- ${it.name}: ${it.preview}" }
                    .ifBlank { "no matching memories" }
            },
            "memory_save" to { arg ->
                val title = arg.substringBefore("|").trim().ifBlank { "agent-note" }.take(60)
                val body = arg.substringAfter("|", arg).trim()
                val ok = runCatching { vault.saveNote(title, body) }.getOrDefault(false)
                if (ok) "saved under '$title'" else "save failed"
            },
            "web" to { q ->
                WebTools.parseIntent(q)?.let { WebTools.fetch(it) } ?: "no web intent detected"
            },
            "shell" to { cmd ->
                // Approval gate: dialog on Main, deny-by-default, then bridge exec.
                val allowed = gate.request(cmd) { askApproval(it) }
                if (!allowed) {
                    "denied by Darren — do not retry shell, finish with phone-safe tools"
                } else {
                    val r = bridge.exec(cmd)
                    when {
                        !r.ok && r.error.isNotBlank() -> "bridge failed: ${r.error}"
                        else -> "exit=${r.code}\n${r.stdout}\n${r.stderr}".trim().take(800)
                    }
                }
            }
        )

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val loop = AgentLoop(router(), tools)
            try {
                val answer = loop.run(
                    goal = goal,
                    systemExtra = listOf(
                        vault.recall(goal),
                        DeviceActions.snapshot(ctx)
                    ).filter { it.isNotBlank() }.joinToString("\n"),
                    onStatus = { s ->
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            messages[aiIdx] = Msg("◌ agent $s", false, messages[aiIdx].ts)
                            runCatching { adapter.notifyItemChanged(aiIdx) }
                        }
                    },
                    onStep = { st ->
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            messages[aiIdx] = Msg(
                                "◌ agent ${st.action}: ${st.arg.take(80)}",
                                false, messages[aiIdx].ts
                            )
                            runCatching { adapter.notifyItemChanged(aiIdx) }
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    messages[aiIdx] = Msg(answer, false, messages[aiIdx].ts)
                    runCatching { adapter.notifyItemChanged(aiIdx) }
                    vault.appendTurn("jarvis", answer)
                    view.findViewById<TextView>(R.id.txt_chat_model)?.text = "agent · loop"
                    if (ttsOn()) VoiceEngine.get(ctx).speak(answer)
                    scroll(view)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    messages[aiIdx] = Msg("⚠️ agent failed: ${e.message}", false, messages[aiIdx].ts)
                    runCatching { adapter.notifyItemChanged(aiIdx) }
                    scroll(view)
                }
            }
        }
    }

    private fun updateEmpty(view: View) {
        runCatching {
            view.findViewById<View>(R.id.txt_chat_empty)?.visibility =
                if (messages.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun scroll(view: View) {
        if (messages.isEmpty()) return
        runCatching {
            view.findViewById<RecyclerView>(R.id.recycler_chat)
                ?.scrollToPosition(messages.size - 1)
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
        val base = ep.replace("/v1/chat/completions", "").ifBlank { JarvisRouter.DEFAULT_PRIMARY }
        return JarvisRouter(
            primaryBaseUrl = base,
            apiKey = prefs.getString("api_key", "") ?: "",
            nousKey = prefs.getString("nous_key", "") ?: ""
        )
    }

    private fun ttsOn(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean("tts_enabled", true)
    }

    class ChatAdapter(private val items: List<Msg>) :
        RecyclerView.Adapter<ChatAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val msg: TextView = v.findViewById(R.id.txt_msg)
            val time: TextView = v.findViewById(R.id.txt_time)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val m = items[position]
            h.msg.text = m.text
            h.msg.setTextColor(
                if (m.isUser) 0xFF020705.toInt() else 0xFFE8F0F2.toInt()
            )
            h.msg.setBackgroundResource(
                if (m.isUser) R.drawable.bubble_user else R.drawable.bubble_jarvis
            )
            h.msg.textAlignment =
                if (m.isUser) View.TEXT_ALIGNMENT_TEXT_END else View.TEXT_ALIGNMENT_TEXT_START
            h.time.text = try {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts))
            } catch (e: Exception) { "" }
        }

        override fun getItemCount() = items.size
    }
}
