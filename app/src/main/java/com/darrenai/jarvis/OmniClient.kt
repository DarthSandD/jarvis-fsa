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

        const val JARVIS_SYSTEM = """You are JARVIS, Darren Lieu's AI chief of staff.
Be concise, direct, and helpful. Use a calm, professional tone.
Always identify yourself as JARVIS."""

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
