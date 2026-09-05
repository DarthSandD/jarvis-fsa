package com.darrenai.jarvis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RouterTest {

    private fun fakeTransport(
        failOn: Set<String> = emptySet(),
        reply: String = "ok"
    ): JarvisRouter.StreamTransport = object : JarvisRouter.StreamTransport {
        override suspend fun stream(
            backend: JarvisRouter.Backend,
            messages: List<Pair<String, String>>,
            systemExtra: String,
            maxTokens: Int,
            onDelta: suspend (String) -> Unit,
            onDone: suspend (String) -> Unit,
            onError: suspend (String) -> Unit
        ) {
            if (backend.name in failOn) {
                onError("boom")
            } else {
                onDelta(reply)
                onDone(reply)
            }
        }
    }

    @Test
    fun `chain is primary fallback offline`() {
        val r = JarvisRouter(transport = fakeTransport())
        val names = r.backends().map { it.name }
        assertEquals(listOf("OmniRoute", "Nous", "On-device"), names)
    }

    @Test
    fun `primary success labels primary`() = runBlocking {
        val seen = mutableListOf<String>()
        val r = JarvisRouter(transport = fakeTransport())
        var done = ""
        r.chatStream(
            listOf("user" to "hi"),
            onBackend = { seen.add(it) },
            onDelta = {},
            onDone = { done = it },
            onError = { fail("should not error") }
        )
        assertEquals(listOf("OmniRoute"), seen)
        assertEquals("ok", done)
    }

    @Test
    fun `primary failure falls to fallback`() = runBlocking {
        val seen = mutableListOf<String>()
        val r = JarvisRouter(transport = fakeTransport(failOn = setOf("OmniRoute")))
        var done = ""
        r.chatStream(
            listOf("user" to "hi"),
            onBackend = { seen.add(it) },
            onDelta = {},
            onDone = { done = it },
            onError = { fail("should not error") }
        )
        assertEquals(listOf("Nous"), seen)
        assertEquals("ok", done)
    }

    @Test
    fun `double failure reaches on-device`() = runBlocking {
        val seen = mutableListOf<String>()
        val r = JarvisRouter(transport = fakeTransport(failOn = setOf("OmniRoute", "Nous")))
        r.chatStream(
            listOf("user" to "hi"),
            onBackend = { seen.add(it) },
            onDelta = {},
            onDone = {},
            onError = { fail("should not error") }
        )
        assertEquals(listOf("On-device"), seen)
    }

    @Test
    fun `total failure reports last error`() = runBlocking {
        val r = JarvisRouter(
            transport = fakeTransport(failOn = setOf("OmniRoute", "Nous", "On-device"))
        )
        var err = ""
        r.chatStream(
            listOf("user" to "hi"),
            onDelta = {},
            onDone = { fail("should not succeed") },
            onError = { err = it }
        )
        assertTrue(err.contains("On-device"))
    }
}
