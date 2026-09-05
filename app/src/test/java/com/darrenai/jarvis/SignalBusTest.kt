package com.darrenai.jarvis

import org.junit.Assert.*
import org.junit.Test

/** Contract tests for the face signal bus (/state shape). */
class SignalBusTest {

    @Test
    fun `snapshot has all bus keys`() {
        SignalBus.setState(SignalBus.IDLE)
        val json = SignalBus.snapshotJson()
        assertTrue(json.contains("\"state\""))
        assertTrue(json.contains("\"level\""))
        assertTrue(json.contains("\"samples\""))
        assertTrue(json.contains("\"alert\""))
        assertTrue(json.contains("\"loading\""))
    }

    @Test
    fun `snapshot carries 64 samples`() {
        SignalBus.setState(SignalBus.SPEAKING)
        SignalBus.setSamples(FloatArray(64) { 0.5f })
        val json = SignalBus.snapshotJson()
        val body = json.substringAfter("\"samples\":[").substringBefore("]")
        assertEquals(64, body.split(",").size)
    }

    @Test
    fun `unknown state falls back to idle`() {
        SignalBus.setState("dancing")
        assertEquals("idle", SignalBus.currentState())
    }

    @Test
    fun `leaving speaking clears level`() {
        SignalBus.setState(SignalBus.SPEAKING)
        SignalBus.setLevel(0.9f)
        SignalBus.setState(SignalBus.IDLE)
        assertTrue(SignalBus.snapshotJson().contains("\"level\":0.0"))
    }

    @Test
    fun `all four states accepted`() {
        for (s in listOf("idle", "listening", "thinking", "speaking")) {
            SignalBus.setState(s)
            assertEquals(s, SignalBus.currentState())
        }
    }
}
