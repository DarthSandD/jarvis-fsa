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

        val history = messages.map { (if (it.isUser) "user" else "assistant") to it.text }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val res = client().chat(history)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (res) {
                    is OmniClient.Result.Ok -> {
                        messages.add(Msg(res.text, false))
                        runCatching { adapter.notifyItemInserted(messages.size - 1) }
                        vault.appendTurn("jarvis", res.text)
                        if (ttsOn()) VoiceEngine.get(requireContext()).speak(res.text)
                    }
                    is OmniClient.Result.Err -> {
                        messages.add(Msg("⚠️ ${res.message}", false))
                        runCatching { adapter.notifyItemInserted(messages.size - 1) }
                    }
                }
                updateEmpty(view)
                scroll(view)
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
            h.msg.setBackgroundColor(
                if (m.isUser) 0xFF3DDC84.toInt() else 0xFF071711.toInt()
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
