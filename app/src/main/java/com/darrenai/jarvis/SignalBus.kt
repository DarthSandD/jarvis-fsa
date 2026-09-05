package com.darrenai.jarvis

/**
 * The signal bus — mirrors backtalk's signals.py contract.
 *
 * States: idle | listening | thinking | speaking.
 * snapshotJson() emits exactly what ai-visualizer's /state serves:
 * {"state","level","samples"[64],"alert","loading"}.
 * The Face WebView polls it through request interception; the voice
 * loop writes it. Every write is wrapped: the bus never crashes a turn.
 */
object SignalBus {

    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val SPEAKING = "speaking"

    @Volatile private var state: String = IDLE
    @Volatile private var level: Float = 0f
    private val samples = FloatArray(64)
    @Volatile private var alert: Boolean = false
    @Volatile private var loading: Boolean = false

    @Synchronized
    fun setState(name: String) {
        state = if (name in setOf(IDLE, LISTENING, THINKING, SPEAKING)) name else IDLE
        if (state != SPEAKING) {
            level = 0f
            samples.fill(0f)
        }
    }

    @Synchronized
    fun setLevel(v: Float) {
        level = v.coerceIn(0f, 1f)
    }

    @Synchronized
    fun setSamples(src: FloatArray) {
        val n = minOf(src.size, 64)
        for (i in 0 until n) samples[i] = src[i]
        for (i in n until 64) samples[i] = 0f
    }

    @Synchronized
    fun setAlert(v: Boolean) { alert = v }

    @Synchronized
    fun setLoading(v: Boolean) { loading = v }

    @Synchronized
    fun snapshotJson(): String {
        val sb = StringBuilder(512)
        sb.append("{\"state\":\"").append(state).append("\",")
        sb.append("\"level\":").append(level).append(",")
        sb.append("\"samples\":[")
        for (i in 0 until 64) {
            if (i > 0) sb.append(",")
            // int16-scale floats, like the Python bus
            sb.append((samples[i] * 9000f).toInt())
        }
        sb.append("],\"alert\":").append(alert)
        sb.append(",\"loading\":").append(loading).append("}")
        return sb.toString()
    }

    @Synchronized
    fun configJson(agentName: String, facesJson: String): String {
        return "{\"name\":\"" + agentName.replace("\"", "") +
            "\",\"badge\":\"\",\"face\":\"board\"," +
            "\"thinking_sound\":false,\"faces\":" + facesJson + "}"
    }

    fun currentState(): String = state
}
