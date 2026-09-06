package com.darrenai.jarvis

import org.junit.Assert.*
import org.junit.Test

class VoiceTest {

    @Test fun stripsBoldAndHeadings() {
        val out = VoiceEngine.sanitizeForSpeech("## Status\n**Battery** at 80%")
        assertFalse(out.contains("**"))
        assertFalse(out.contains("##"))
        assertTrue(out.contains("Battery"))
    }

    @Test fun stripsBulletsAndLinks() {
        val out = VoiceEngine.sanitizeForSpeech("- first\n- second\n[docs](https://example.com/x)")
        assertFalse(out.contains("- first"))
        assertFalse(out.contains("https://"))
        assertTrue(out.contains("docs"))
        assertTrue(out.contains("first"))
    }

    @Test fun stripsCodeAndEmoji() {
        val out = VoiceEngine.sanitizeForSpeech("Run `fastboot` now ⚠️ done & dusted")
        assertFalse(out.contains("`"))
        assertFalse(out.contains("⚠"))
        assertTrue(out.contains("fastboot"))
        assertTrue(out.contains("and"))
    }

    @Test fun stripsTables() {
        val out = VoiceEngine.sanitizeForSpeech("| a | b |\n|---|---|\n| 1 | 2 |")
        assertFalse(out.contains("|"))
        assertTrue(out.contains("1"))
    }

    @Test fun blankAfterStrip() {
        assertEquals("", VoiceEngine.sanitizeForSpeech("https://x.com ⚠️"))
    }
}

class DeviceActionsTest {

    @Test fun flashlightOn() {
        assertEquals(
            DeviceActions.LocalIntent.Flashlight(true),
            DeviceActions.parseCommand("Turn on the flashlight")
        )
    }

    @Test fun flashlightOff() {
        assertEquals(
            DeviceActions.LocalIntent.Flashlight(false),
            DeviceActions.parseCommand("torch off please")
        )
    }

    @Test fun battery() {
        assertEquals(
            DeviceActions.LocalIntent.Battery,
            DeviceActions.parseCommand("what's my battery level?")
        )
    }

    @Test fun time() {
        assertEquals(
            DeviceActions.LocalIntent.Time,
            DeviceActions.parseCommand("What time is it?")
        )
    }

    @Test fun date() {
        assertEquals(
            DeviceActions.LocalIntent.Date,
            DeviceActions.parseCommand("what's the date today?")
        )
    }

    @Test fun reminder() {
        assertEquals(
            DeviceActions.LocalIntent.Reminder("call mom at five"),
            DeviceActions.parseCommand("remind me to call mom at five")
        )
    }

    @Test fun fallsThroughToLlm() {
        assertNull(DeviceActions.parseCommand("explain quantum entanglement"))
        assertNull(DeviceActions.parseCommand("remind me"))
    }

    @Test fun help() {
        assertEquals(
            DeviceActions.LocalIntent.Help,
            DeviceActions.parseCommand("help")
        )
    }

    @Test fun openFace() {
        assertEquals(
            DeviceActions.LocalIntent.OpenFace,
            DeviceActions.parseCommand("open the face")
        )
    }

    @Test fun memoryStatus() {
        assertEquals(
            DeviceActions.LocalIntent.MemoryStatus,
            DeviceActions.parseCommand("what do you remember?")
        )
    }
}
