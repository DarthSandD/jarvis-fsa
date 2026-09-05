package com.darrenai.jarvis

import org.junit.Assert.*
import org.junit.Test

class WebToolsTest {

    @Test fun weatherIntent() {
        val i = WebTools.parseIntent("What's the weather in Bandung?")
        assertEquals(WebTools.WebIntent.Weather("bandung"), i)
    }

    @Test fun weatherDefaultCity() {
        assertEquals(
            WebTools.WebIntent.Weather("Jakarta"),
            WebTools.parseIntent("is it going to rain today?")
        )
    }

    @Test fun forexPair() {
        assertEquals(
            WebTools.WebIntent.Forex("USD", "IDR"),
            WebTools.parseIntent("dollar to rupiah rate?")
        )
    }

    @Test fun forexSingleDefaultsIdr() {
        assertEquals(
            WebTools.WebIntent.Forex("EUR", "IDR"),
            WebTools.parseIntent("what's the euro exchange rate")
        )
    }

    @Test fun cryptoIntent() {
        assertEquals(
            WebTools.WebIntent.Crypto("bitcoin"),
            WebTools.parseIntent("what is the bitcoin price now?")
        )
    }

    @Test fun wikiIntent() {
        assertEquals(
            WebTools.WebIntent.Wiki("nikola tesla"),
            WebTools.parseIntent("Who is Nikola Tesla?")
        )
    }

    @Test fun noIntentForChitchat() {
        assertNull(WebTools.parseIntent("how are you today?"))
        assertNull(WebTools.parseIntent("what time is it?"))
        assertNull(WebTools.parseIntent("turn on the flashlight"))
    }

    @Test fun parseWeatherJson() {
        val body = """{"current":{"temperature_2m":29.4,"relative_humidity_2m":78,"weather_code":80}}"""
        val out = WebTools.parseWeatherJson("Bandung, Indonesia", body)!!
        assertTrue(out.contains("Bandung"))
        assertTrue(out.contains("29 degrees"))
        assertTrue(out.contains("showers"))
        assertTrue(out.contains("78 percent"))
    }

    @Test fun parseForexJson() {
        val body = """{"rates":{"IDR":16250.5,"USD":1.0}}"""
        val out = WebTools.parseForexJson("USD", "IDR", body)!!
        assertTrue(out.contains("1 USD equals 16,251 IDR"))
        assertTrue(out.contains("Live exchange rate"))
    }

    @Test fun parseCryptoJson() {
        val body = """{"bitcoin":{"usd":97412.0}}"""
        val out = WebTools.parseCryptoJson("bitcoin", body)!!
        assertTrue(out.contains("97,412"))
    }

    @Test fun parseWikiJson() {
        val body = """{"type":"standard","extract":"Nikola Tesla was a Serbian-American inventor."}"""
        val out = WebTools.parseWikiJson("nikola tesla", body)!!
        assertTrue(out.contains("Wikipedia"))
        assertTrue(out.contains("inventor"))
    }

    @Test fun parseWikiRejectsGarbage() {
        assertNull(WebTools.parseWikiJson("x", """{"type":"disambiguation"}"""))
        assertNull(WebTools.parseWikiJson("x", """{"type":"standard"}"""))
    }
}
