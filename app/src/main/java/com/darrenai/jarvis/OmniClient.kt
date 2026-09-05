package com.darrenai.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenAI-compatible chat client for OmniRoute.
 * Self-contained routing: default endpoint is the PC on the LAN.
 * Only internet/LAN access is needed — no other backend.
 */
class OmniClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String = "",
    private val model: String = "darren-1212"
) {

    companion object {
        const val DEFAULT_ENDPOINT = "http://10.212.104.124:20128/v1/chat/completions"

        const val JARVIS_SYSTEM = """You are JARVIS — Darren Lieu's chief of staff, in the spirit of Iron Man's Jarvis: a dry-witted British butler with total situational awareness.

CHARACTER:
- Address Darren as "sir". Confident, precise, faintly amused; never servile, never robotic.
- One dry remark per conversation at most — never forced, never repeated.
- Short spoken-style sentences. No rambling, no bullet-point voice.

AWARENESS (use what you are given):
- You receive relevant memories with each turn. Refer to them naturally ("as you noted last week…").
- If asked about device state (battery, time), answer from the live snapshot provided.
- Lines marked Live were fetched from the internet seconds ago — trust them over training memory, speak the numbers exactly, never hedge them.
- Never invent device state, news, or prices. If you don't have live data, say so plainly and offer the closest useful thing.

VOICE HYGIENE (your words are spoken aloud):
- Plain sentences only. No markdown, no asterisks, no hashtags, no emoji, no URLs.
- Numbers and units in words a voice can say ("twenty-nine amps", not "29.4A").
- Default length: 2-4 sentences unless asked for more."""

        fun escapeJson(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            return sb.toString()
        }

        fun buildBody(model: String, system: String, messages: List<Pair<String, String>>, maxTokens: Int): String {
            val sb = StringBuilder()
            sb.append("{\"model\":\"").append(escapeJson(model)).append("\",\"messages\":[")
            sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(system)).append("\"}")
            for ((role, content) in messages.takeLast(20)) {
                sb.append(",{\"role\":\"").append(role).append("\",\"content\":\"")
                sb.append(escapeJson(content)).append("\"}")
            }
            sb.append("],\"stream\":false,\"max_tokens\":").append(maxTokens).append("}")
            return sb.toString()
        }

        /** Extract assistant content from an OpenAI-compatible response. */
        fun parseContent(response: String): String? {
            return try {
                val root = JSONObject(response)
                val choices = root.optJSONArray("choices") ?: return null
                if (choices.length() == 0) return null
                val msg = choices.getJSONObject(0).optJSONObject("message") ?: return null
                msg.optString("content", null)?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }

        /** Parse one SSE chunk line. Returns content piece, or null if keep-alive/control. */
        fun parseChunk(line: String): String? {
            val t = line.trim()
            if (!t.startsWith("data:")) return null
            val payload = t.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return null
            return try {
                val root = JSONObject(payload)
                val choices = root.optJSONArray("choices") ?: return null
                if (choices.length() == 0) return null
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
                delta.optString("content", null)?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Split complete sentences off a streaming buffer.
         * Returns (complete sentences, remainder). A sentence ends with
         * . ! ? followed by whitespace/end, and must be at least 12 chars
         * so abbreviations don't trigger early speech.
         */
        fun splitSentences(buffer: String): Pair<List<String>, String> {
            val done = mutableListOf<String>()
            var rest = buffer
            val re = Regex("""(.+?[.!?])(\s+|$)""")
            while (true) {
                val m = re.find(rest) ?: break
                val sentence = m.groupValues[1].trim()
                if (sentence.length < 12) break
                done.add(sentence)
                rest = rest.substring(m.range.last + 1)
            }
            return done to rest
        }
    }

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 1024): Result {
        return withContext(Dispatchers.IO) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                    if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
                    doOutput = true
                }
                val body = buildBody(model, JARVIS_SYSTEM, messages, maxTokens)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown"
                    } catch (e: Exception) { "Unknown" }
                    return@withContext Result.Err("Server $code: ${err.take(200)}")
                }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val content = parseContent(response)
                if (content != null) Result.Ok(content)
                else Result.Err("Malformed response from server")
            } catch (e: Exception) {
                Result.Err(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Streaming chat (SSE). Calls onDelta for each content piece as it
     * arrives, then onDone(fullText) or onError. Callbacks run on IO —
     * callers marshal to main. Cancel the surrounding coroutine to stop.
     */
    suspend fun chatStream(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 1024,
        systemExtra: String = "",
        onDelta: suspend (String) -> Unit,
        onDone: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val system = if (systemExtra.isBlank()) JARVIS_SYSTEM
            else "$JARVIS_SYSTEM\n\nRelevant memory:\n$systemExtra"
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 120000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
                    doOutput = true
                }
                val body = buildBody(model, system, messages, maxTokens)
                    .replace("\"stream\":false", "\"stream\":true")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown"
                    } catch (e: Exception) { "Unknown" }
                    onError("Server $code: ${err.take(200)}")
                    return@withContext
                }
                val full = StringBuilder()
                var finished = false
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val piece = parseChunk(line)
                        if (piece != null) {
                            full.append(piece)
                            onDelta(piece)
                        }
                        if (line.contains("\"finish_reason\":\"stop\"") ||
                            line.contains("\"finish_reason\": \"stop\"") ||
                            line.trim() == "data: [DONE]"
                        ) {
                            finished = true
                        }
                    }
                }
                val text = full.toString()
                if (text.isNotEmpty()) onDone(text)
                else onError("Empty response from server")
            } catch (e: Exception) {
                // Fall back to non-streaming once before giving up.
                try {
                    when (val r = chat(messages, maxTokens)) {
                        is Result.Ok -> onDone(r.text)
                        is Result.Err -> onError(r.message)
                    }
                } catch (e2: Exception) {
                    onError(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    suspend fun healthCheck(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(endpoint.replace("/chat/completions", "/models"))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) null else "Server $code"
            } catch (e: Exception) {
                e.message ?: "Connection failed"
            }
        }
    }
}
