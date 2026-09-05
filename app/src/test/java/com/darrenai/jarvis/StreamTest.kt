package com.darrenai.jarvis

import org.junit.Assert.*
import org.junit.Test

class StreamTest {

    @Test
    fun `parseChunk extracts delta content`() {
        val line = """data: {"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
        assertEquals("Hello", OmniClient.parseChunk(line))
    }

    @Test
    fun `parseChunk ignores reasoning and control lines`() {
        assertNull(OmniClient.parseChunk(""))
        assertNull(OmniClient.parseChunk("data: [DONE]"))
        assertNull(OmniClient.parseChunk(": keep-alive"))
        val reasoning = """data: {"choices":[{"index":0,"delta":{"reasoning_content":"1"},"finish_reason":null}]}"""
        assertNull(OmniClient.parseChunk(reasoning))
        val empty = """data: {"choices":[{"index":0,"delta":{"content":""},"finish_reason":null}]}"""
        assertNull(OmniClient.parseChunk(empty))
    }

    @Test
    fun `splitSentences emits complete sentences only`() {
        val (done, rest) = OmniClient.splitSentences(
            "All systems online, sir. What are we working on today? Let me che"
        )
        assertEquals(
            listOf("All systems online, sir.", "What are we working on today?"),
            done
        )
        assertEquals("Let me che", rest)
    }

    @Test
    fun `splitSentences holds short fragments`() {
        val (done, rest) = OmniClient.splitSentences("Yes. I can help with that today")
        assertTrue(done.isEmpty())
        assertEquals("Yes. I can help with that today", rest)
    }

    @Test
    fun `recall finds relevant notes`() {
        val dir = createTempDir("vault")
        try {
            val vault = MemoryVault(dir)
            vault.saveNote("reactor", "build the arc reactor this weekend")
            val hit = vault.recall("when do we build the reactor")
            assertTrue(hit.contains("reactor"))
            assertEquals("", vault.recall("xyzzy nothing"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
