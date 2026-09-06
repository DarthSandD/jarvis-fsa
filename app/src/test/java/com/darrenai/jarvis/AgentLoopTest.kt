package com.darrenai.jarvis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentLoopTest {

    /** Scripted planner: emits the given lines in order, then DONE. */
    private fun scriptedTransport(script: List<String>): JarvisRouter.StreamTransport =
        object : JarvisRouter.StreamTransport {
            var i = 0
            override suspend fun stream(
                backend: JarvisRouter.Backend,
                messages: List<Pair<String, String>>,
                systemExtra: String,
                maxTokens: Int,
                onDelta: suspend (String) -> Unit,
                onDone: suspend (String) -> Unit,
                onError: suspend (String) -> Unit
            ) {
                val line = if (i < script.size) script[i++] else "DONE: finished, sir."
                onDelta(line)
                onDone(line)
            }
        }

    private fun loop(script: List<String>, tools: Map<String, suspend (String) -> String> = emptyMap()): AgentLoop {
        val router = JarvisRouter(transport = scriptedTransport(script))
        val echo: suspend (String) -> String = { a -> "echo:$a" }
        return AgentLoop(router, tools.ifEmpty { mapOf("echo" to echo) })
    }

    @Test
    fun `parse action line`() {
        val p = AgentLoop.parseLine("ACTION: web | weather Jakarta")
        assertEquals(AgentLoop.Parsed.Action("web", "weather Jakarta"), p)
    }

    @Test
    fun `parse done line`() {
        val p = AgentLoop.parseLine("DONE: all set, sir.")
        assertEquals(AgentLoop.Parsed.Done("all set, sir."), p)
    }

    @Test
    fun `plain text treated as done`() {
        val p = AgentLoop.parseLine("Battery is fine.")
        assertEquals(AgentLoop.Parsed.Done("Battery is fine."), p)
    }

    @Test
    fun `immediate done returns answer`() = runBlocking {
        val ans = loop(listOf("DONE: hello, sir.")).run("hi")
        assertEquals("hello, sir.", ans)
    }

    @Test
    fun `one action then done feeds observation`() = runBlocking {
        val steps = mutableListOf<AgentLoop.Step>()
        val echo: suspend (String) -> String = { a -> "level=82%($a)" }
        val l = loop(
            listOf("ACTION: echo | battery?", "DONE: battery checked, sir."),
            mapOf("echo" to echo)
        )
        val ans = l.run("check battery", onStep = { steps.add(it) })
        assertEquals("battery checked, sir.", ans)
        assertEquals(1, steps.size)
        assertEquals("level=82%(battery?)", steps[0].observation)
    }

    @Test
    fun `unknown tool surfaces available list`() = runBlocking {
        val steps = mutableListOf<AgentLoop.Step>()
        val l = loop(listOf("ACTION: nope | x", "DONE: recovered, sir."))
        l.run("goal", onStep = { steps.add(it) })
        assertTrue(steps[0].observation.contains("unknown tool"))
    }

    @Test
    fun `max steps caps the loop`() = runBlocking {
        val echo: suspend (String) -> String = { a -> "ok:$a" }
        val l = AgentLoop(
            JarvisRouter(transport = scriptedTransport(List(20) { "ACTION: echo | $it" })),
            mapOf("echo" to echo),
            maxSteps = 3
        )
        val ans = l.run("endless goal")
        assertTrue(ans.contains("3 steps"))
    }
}
