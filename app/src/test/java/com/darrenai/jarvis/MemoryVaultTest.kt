package com.darrenai.jarvis

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `first run seeds the index`() {
        val vault = MemoryVault(tmp.newFolder("vault"))
        assertTrue(vault.indexExists())
        assertTrue(vault.readIndex().contains("VAULT-INDEX"))
    }

    @Test
    fun `appendTurn writes daily note`() {
        val vault = MemoryVault(tmp.newFolder("vault"))
        vault.appendTurn("user", "hello jarvis")
        val docs = vault.listDocs()
        assertTrue(docs.any { it.name.matches(Regex("(daily/)?\\d{4}-\\d{2}-\\d{2}")) })
    }

    @Test
    fun `saveNote and search round-trip`() {
        val vault = MemoryVault(tmp.newFolder("vault"))
        assertTrue(vault.saveNote("ideas", "build the reactor"))
        assertEquals(1, vault.search("reactor").size)
        assertEquals(0, vault.search("nope-nothing").size)
        assertTrue(vault.readDoc("ideas").contains("reactor"))
    }

    @Test
    fun `failing disk never throws`() {
        val vault = MemoryVault(tmp.newFolder("vault"))
        vault.appendTurn("user", "x".repeat(100000))
        assertNotNull(vault.listDocs())
    }
}
