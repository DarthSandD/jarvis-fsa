package com.darrenai.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fork of the Hermes routing behavior for the app.
 *
 * Mirrors the live chain (see hermes config.yaml):
 *   1. primary  — OmniRoute on the LAN (combo, e.g. darren-1212)
 *   2. fallback — Nous direct (free tier, e.g. meituan/longcat-2.0:free)
 *   3. offline  — S23's own llama.cpp (no network needed)
 *
 * Ordered failover like Hermes fallback_providers: a backend that
 * errors (network/server) is skipped and the next is tried. SSE
 * streaming is preserved end-to-end; the transport is injectable so
 * the ordering logic is unit-testable without a network.
 */
class JarvisRouter(
    private val primaryBaseUrl: String = DEFAULT_PRIMARY,
    private val primaryModel: String = DEFAULT_MODEL,
    private val apiKey: String = "",
    private val transport: StreamTransport = HttpStreamTransport()
) {

    data class Backend(
        val name: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String = ""
    )

    interface StreamTransport {
        suspend fun stream(
            backend: Backend,
            messages: List<Pair<String, String>>,
            systemExtra: String,
            maxTokens: Int,
            onDelta: suspend (String) -> Unit,
            onDone: suspend (String) -> Unit,
            onError: suspend (String) -> Unit
        )
    }

    companion object {
        const val DEFAULT_PRIMARY = "http://10.212.104.124:20128"
        const val DEFAULT_MODEL = "darren-1212"
        const val NOUS_BASE = "https://inference-api.nousresearch.com"
        const val NOUS_MODEL = "meituan/longcat-2.0:free"
        const val LOCAL_BASE = "http://10.212.104.140:8081"
        const val LOCAL_MODEL = "qwen2.5"
    }

    fun backends(): List<Backend> = listOf(
        Backend("OmniRoute", primaryBaseUrl, primaryModel, apiKey),
        Backend("Nous", NOUS_BASE, NOUS_MODEL),
        Backend("On-device", LOCAL_BASE, LOCAL_MODEL)
    )

    /**
     * Stream a turn with failover. onBackend fires with the name of the
     * backend that produced output (for the UI label). Callbacks run on IO.
     */
    suspend fun chatStream(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 1024,
        systemExtra: String = "",
        onBackend: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit,
        onDone: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var lastError = "No backend available"
            for (backend in backends()) {
                var produced = false
                var failed = false
                var failure = ""
                try {
                    transport.stream(
                        backend, messages, systemExtra, maxTokens,
                        onDelta = {
                            produced = true
                            onDelta(it)
                        },
                        onDone = {
                            produced = true
                            onDone(it)
                        },
                        onError = { e ->
                            failed = true
                            failure = e
                        }
                    )
                } catch (e: Exception) {
                    failed = true
                    failure = e.message ?: e.javaClass.simpleName
                }
                if (produced) {
                    onBackend(backend.name)
                    return@withContext
                }
                if (failed) {
                    lastError = "${backend.name}: $failure"
                }
            }
            onError(lastError)
        }
    }

    /** Fast health probe used by Home (short timeout, names only). */
    suspend fun probe(): List<Pair<String, String?>> {
        return withContext(Dispatchers.IO) {
            backends().map { b ->
                val err = try {
                    val url = URL(b.baseUrl.trimEnd('/') + "/v1/models")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3000
                        readTimeout = 3000
                        if (b.apiKey.isNotEmpty()) {
                            setRequestProperty("Authorization", "Bearer ${b.apiKey}")
                        }
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) null else "HTTP $code"
                } catch (e: Exception) {
                    e.message ?: "unreachable"
                }
                b.name to err
            }
        }
    }
}

/** Default transport: OmniClient SSE against a given backend. */
class HttpStreamTransport : JarvisRouter.StreamTransport {
    override suspend fun stream(
        backend: JarvisRouter.Backend,
        messages: List<Pair<String, String>>,
        systemExtra: String,
        maxTokens: Int,
        onDelta: suspend (String) -> Unit,
        onDone: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        val client = OmniClient(
            endpoint = backend.baseUrl.trimEnd('/') + "/v1/chat/completions",
            apiKey = backend.apiKey,
            model = backend.model
        )
        client.chatStream(messages, maxTokens, systemExtra, onDelta, onDone, onError)
    }
}
