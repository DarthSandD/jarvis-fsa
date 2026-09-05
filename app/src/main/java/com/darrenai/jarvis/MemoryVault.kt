package com.darrenai.jarvis

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The mind — mirrors ai-memory-vault: plain markdown files, no ceiling.
 *
 * vault/
 *   VAULT-INDEX.md    profile + map (seeded on first run)
 *   MEMORY.md         pointer: vault is the single source of truth
 *   daily/YYYY-MM-DD.md  append-only log of turns worth keeping
 *
 * Takes a root File so it is pure-JVM testable.
 */
class MemoryVault(private val root: File) {

    private val dailyDir = File(root, "daily")

    init {
        runCatching {
            root.mkdirs()
            dailyDir.mkdirs()
            if (!File(root, "VAULT-INDEX.md").exists()) {
                File(root, "VAULT-INDEX.md").writeText(SEED_INDEX)
            }
            if (!File(root, "MEMORY.md").exists()) {
                File(root, "MEMORY.md").writeText(SEED_MEMORY)
            }
        }
    }

    fun indexExists(): Boolean = File(root, "VAULT-INDEX.md").exists()

    fun readIndex(): String = runCatching {
        File(root, "VAULT-INDEX.md").readText()
    }.getOrDefault("")

    /** Append a turn to today's daily note. Never throws. */
    fun appendTurn(role: String, text: String) {
        runCatching {
            val name = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val f = File(dailyDir, "$name.md")
            if (!f.exists()) {
                f.writeText("# $name\n\n")
            }
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            f.appendText("## $time · $role\n\n${text.trim()}\n\n")
        }
    }

    /** Append a standalone memory note. Returns false on failure. */
    fun saveNote(title: String, body: String): Boolean {
        return runCatching {
            val safe = title.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().take(60)
                .ifBlank { "note" }
            File(root, "$safe.md").writeText("# $title\n\n$body\n")
            true
        }.getOrDefault(false)
    }

    data class Doc(val name: String, val preview: String, val modified: Long)

    fun listDocs(): List<Doc> {
        return runCatching {
            val out = mutableListOf<Doc>()
            root.listFiles()
                ?.filter { it.isFile && it.extension == "md" }
                ?.forEach { f ->
                    out.add(
                        Doc(
                            name = f.nameWithoutExtension,
                            preview = f.readText().take(160).replace("\n", " "),
                            modified = f.lastModified()
                        )
                    )
                }
            dailyDir.listFiles()
                ?.filter { it.isFile && it.extension == "md" }
                ?.forEach { f ->
                    out.add(
                        Doc(
                            name = "daily/${f.nameWithoutExtension}",
                            preview = f.readText().take(160).replace("\n", " "),
                            modified = f.lastModified()
                        )
                    )
                }
            out.sortedByDescending { it.modified }
        }.getOrDefault(emptyList())
    }

    fun readDoc(name: String): String {
        val safe = name.replace("\\", "/").split("/")
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .take(2)
        if (safe.isEmpty()) return ""
        return runCatching {
            var dir = root
            for (i in 0 until safe.size - 1) {
                dir = File(dir, safe[i])
            }
            val f = File(dir, "${safe.last()}.md")
                .takeIf { it.isFile && it.canonicalPath.startsWith(root.canonicalPath) }
            f?.readText() ?: ""
        }.getOrDefault("")
    }

    fun search(query: String): List<Doc> {
        if (query.isBlank()) return listDocs()
        return listDocs().filter {
            it.name.contains(query, ignoreCase = true) ||
                it.preview.contains(query, ignoreCase = true)
        }
    }

    /**
     * Recall top relevant notes for a query (keyword overlap score).
     * Injected into the agent prompt so it remembers across turns.
     */
    fun recall(query: String, limit: Int = 3, maxChars: Int = 1500): String {
        val words = query.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return ""
        data class Scored(val doc: Doc, val score: Int)
        val scored = listDocs().mapNotNull { d ->
            if (d.name.startsWith("daily/")) {
                // Daily logs are noisy — only exact-ish hits count.
                val hits = words.count {
                    d.preview.lowercase().contains(it)
                }
                if (hits >= 2) Scored(d, hits) else null
            } else {
                val hay = (d.name + " " + d.preview).lowercase()
                val hits = words.count { hay.contains(it) }
                if (hits > 0) Scored(d, hits * 2) else null
            }
        }.sortedByDescending { it.score }.take(limit)
        val out = StringBuilder()
        for ((doc, _) in scored) {
            val body = readDoc(doc.name).take(600)
            if (body.isBlank()) continue
            if (out.length + body.length > maxChars) break
            out.append("### ").append(doc.name).append("\n").append(body).append("\n")
        }
        return out.toString().trim()
    }

    companion object {
        const val SEED_INDEX = """# VAULT-INDEX

## Profile
Darren Lieu — Power System Engineer (WIB/UTC+7). Building an automated AI business.

## Map
- VAULT-INDEX.md — this file: profile + map.
- MEMORY.md — pointer: the vault is the single source of truth.
- daily/ — append-only log of turns worth keeping.
- Notes live at the vault root as plain markdown.
"""
        const val SEED_MEMORY = """# MEMORY

There is no separate memory layer here. The single source of truth is this vault.
Read VAULT-INDEX.md at the start of every session. To remember something, write it here.
"""
    }
}
