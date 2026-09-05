package com.darrenai.jarvis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darrenai.jarvis.MemoryVault
import com.darrenai.jarvis.R
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Memory: browse, search, and append to the plain-file vault. */
class MemoryFragment : Fragment() {

    private lateinit var vault: MemoryVault
    private val docs = mutableListOf<MemoryVault.Doc>()
    private lateinit var adapter: MemoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_memory, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vault = MemoryVault(File(requireContext().filesDir, "vault"))

        adapter = MemoryAdapter(docs) { doc -> showDoc(doc) }
        view.findViewById<RecyclerView>(R.id.recycler_memory)?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MemoryFragment.adapter
        }

        view.findViewById<EditText>(R.id.edit_search)?.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    refresh(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        view.findViewById<Button>(R.id.btn_add_memory)?.setOnClickListener {
            val input = TextInputEditText(requireContext()).apply {
                hint = getString(R.string.memory_add)
                setPadding(32, 24, 32, 24)
            }
            AlertDialog.Builder(requireContext())
                .setTitle("New memory")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val text = input.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        val title = text.take(48).substringBefore("\n")
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            vault.saveNote(title, text)
                            withContext(Dispatchers.Main) { refresh("") }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refresh("")
    }

    private fun refresh(query: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = vault.search(query)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                docs.clear()
                docs.addAll(list)
                runCatching { adapter.notifyDataSetChanged() }
                view?.findViewById<View>(R.id.txt_memory_empty)?.visibility =
                    if (docs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showDoc(doc: MemoryVault.Doc) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val body = vault.readDoc(doc.name)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                AlertDialog.Builder(requireContext())
                    .setTitle(doc.name)
                    .setMessage(body.ifBlank { doc.preview })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    class MemoryAdapter(
        private val items: List<MemoryVault.Doc>,
        private val onTap: (MemoryVault.Doc) -> Unit
    ) : RecyclerView.Adapter<MemoryAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txt_doc_name)
            val preview: TextView = v.findViewById(R.id.txt_doc_preview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_memory, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val d = items[position]
            h.name.text = d.name
            h.preview.text = d.preview
            h.itemView.setOnClickListener { onTap(d) }
        }

        override fun getItemCount() = items.size
    }
}
