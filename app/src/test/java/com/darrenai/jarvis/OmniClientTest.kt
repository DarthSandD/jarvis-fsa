package com.darrenai.jarvis

import org.junit.Assert.*
import org.junit.Test

class OmniClientTest {

    @Test
    fun `parseContent extracts assistant text`() {
        val body = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"Hello sir"}}]}"""
        assertEquals("Hello sir", OmniClient.parseContent(body))
    }

    @Test
    fun `parseContent returns null on malformed body`() {
        assertNull(OmniClient.parseContent("{}"))
        assertNull(OmniClient.parseContent("not json"))
        assertNull(OmniClient.parseContent("""{"choices":[]}"""))
    }

    @Test
    fun `escapeJson escapes quotes and newlines`() {
        val out = OmniClient.escapeJson("say \"hi\"\nbye")
        assertEquals("say \\\"hi\\\"\\nbye", out)
    }

    @Test
    fun `buildBody uses lowercase roles`() {
        val body = OmniClient.buildBody("m", "sys", listOf("user" to "hi"), 10)
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"stream\":false"))
    }
}
