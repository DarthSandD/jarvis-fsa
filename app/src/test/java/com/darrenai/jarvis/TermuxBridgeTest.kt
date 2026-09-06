package com.darrenai.jarvis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TermuxBridgeTest {

    private fun fakeBridge(execReply: String, code: Int = 200): suspend (String, String) -> Pair<Int, String> =
        { _, _ -> code to execReply }

    @Test
    fun `health true on ok`() = runBlocking {
        val b = TermuxBridge(post = fakeBridge("""{"ok": true}"""))
        assertTrue(b.health())
    }

    @Test
    fun `health false on garbage`() = runBlocking {
        val b = TermuxBridge(post = fakeBridge("nope"))
        assertFalse(b.health())
    }

    @Test
    fun `exec parses result`() = runBlocking {
        val b = TermuxBridge(
            token = "t",
            post = fakeBridge("""{"ok": true, "code": 0, "stdout": "hi", "stderr": ""}""")
        )
        val r = b.exec("echo hi")
        assertTrue(r.ok)
        assertEquals(0, r.code)
        assertEquals("hi", r.stdout)
    }

    @Test
    fun `exec refused without token`() = runBlocking {
        val b = TermuxBridge(token = "", post = fakeBridge("{}"))
        val r = b.exec("echo hi")
        assertFalse(r.ok)
        assertTrue(r.error.contains("token"))
    }

    @Test
    fun `exec refused on empty command`() = runBlocking {
        val b = TermuxBridge(token = "t", post = fakeBridge("{}"))
        assertFalse(b.exec("   ").ok)
    }

    @Test
    fun `exec surfaces bridge denial`() = runBlocking {
        val b = TermuxBridge(
            token = "t",
            post = fakeBridge("""{"ok": false, "error": "forbidden"}""")
        )
        val r = b.exec("rm -rf /")
        assertFalse(r.ok)
        assertEquals("forbidden", r.error)
    }

    @Test
    fun `approval denies by default`() = runBlocking {
        val gate = ApprovalGate()
        assertFalse(gate.request("echo hi") { false })
        assertFalse(gate.request("   ") { true })
    }

    @Test
    fun `approval passes explicit tap only`() = runBlocking {
        val gate = ApprovalGate()
        assertTrue(gate.request("git status") { cmd -> cmd == "git status" })
    }
}
