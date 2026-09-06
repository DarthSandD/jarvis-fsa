package com.darrenai.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v10 client for the Termux bridge (termux-bridge.py): localhost-only
 * command runner on the S23. Every exec is gated by [ApprovalGate] —
 * NOTHING runs without Darren's explicit tap, no auto-approve, no
 * "remember this choice". Transport is injectable for unit tests.
 */
class TermuxBridge(
    private val baseUrl: String = DEFAULT_URL,
    private val token: String = "",
    private val post: suspend (url: String, body: String) -> Pair<Int, String> = ::httpPost
) {

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8087"
    }

    data class ExecResult(
        val ok: Boolean,
        val code: Int = -1,
        val stdout: String = "",
        val stderr: String = "",
        val error: String = ""
    )

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = post(baseUrl.trimEnd('/') + "/health", "")
            code in 200..299 && JSONObject(body).optBoolean("ok")
        }.getOrDefault(false)
    }

    suspend fun exec(cmd: String, timeoutSec: Int = 30): ExecResult =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                return@withContext ExecResult(ok = false, error = "bridge token not set (Settings)")
            }
            if (cmd.isBlank()) {
                return@withContext ExecResult(ok = false, error = "empty command refused")
            }
            runCatching {
                val body = JSONObject()
                    .put("token", token)
                    .put("cmd", cmd.take(2000))
                    .put("timeout", timeoutSec.coerceIn(1, 60))
                    .toString()
                val (code, raw) = post(baseUrl.trimEnd('/') + "/exec", body)
                if (code !in 200..299) {
                    return@runCatching ExecResult(ok = false, error = "bridge HTTP $code")
                }
                val o = JSONObject(raw)
                ExecResult(
                    ok = o.optBoolean("ok"),
                    code = o.optInt("code", -1),
                    stdout = o.optString("stdout").take(800),
                    stderr = o.optString("stderr").take(800),
                    error = o.optString("error", "")
                )
            }.getOrElse {
                ExecResult(ok = false, error = "bridge unreachable: ${it.message}")
            }
        }
}

/** Approval policy: explicit tap per command. Deny is the default. */
class ApprovalGate {
    /** Returns true only when [decide] (the dialog callback) returns true. */
    suspend fun request(cmd: String, decide: suspend (String) -> Boolean): Boolean {
        if (cmd.isBlank()) return false
        return runCatching { decide(cmd) }.getOrDefault(false)
    }
}

private suspend fun httpPost(url: String, body: String): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body.isEmpty()) "GET" else "POST"
            connectTimeout = 5000
            readTimeout = 45000
            if (body.isNotEmpty()) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = conn.responseCode
        val raw = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        }.getOrDefault("")
        conn.disconnect()
        code to raw
    }
