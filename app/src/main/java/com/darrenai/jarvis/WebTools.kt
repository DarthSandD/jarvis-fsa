package com.darrenai.jarvis

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Live internet data with zero API keys. Jarvis fetches first,
 * then the LLM speaks the fresh facts — like Hey Google, but sir's.
 *
 * Sources (all free, keyless):
 *  - Open-Meteo: weather + geocoding
 *  - open.er-api.com: fiat forex rates
 *  - CoinGecko: crypto prices
 *  - Wikipedia REST: knowledge summaries
 */
object WebTools {

    sealed class WebIntent {
        data class Weather(val place: String) : WebIntent()
        data class Forex(val from: String, val to: String) : WebIntent()
        data class Crypto(val symbol: String) : WebIntent()
        data class Wiki(val topic: String) : WebIntent()
    }

    private val FIAT = mapOf(
        "dollar" to "USD", "usd" to "USD", "greenback" to "USD",
        "rupiah" to "IDR", "idr" to "IDR",
        "euro" to "EUR", "eur" to "EUR",
        "yen" to "JPY", "jpy" to "JPY", "yuan" to "CNY", "cny" to "CNY",
        "pound" to "GBP", "gbp" to "GBP", "sterling" to "GBP",
        "singapore dollar" to "SGD", "sgd" to "SGD", "sing dollar" to "SGD",
        "ringgit" to "MYR", "myr" to "MYR", "baht" to "THB", "thb" to "THB",
        "peso" to "PHP", "php" to "PHP", "rupee" to "INR", "inr" to "INR",
        "won" to "KRW", "krw" to "KRW", "aussie" to "AUD", "aud" to "AUD"
    )

    private val CRYPTO = mapOf(
        "bitcoin" to "bitcoin", "btc" to "bitcoin",
        "ethereum" to "ethereum", "eth" to "ethereum",
        "solana" to "solana", "sol" to "solana",
        "xrp" to "ripple", "ripple" to "ripple",
        "doge" to "dogecoin", "dogecoin" to "dogecoin",
        "bnb" to "binancecoin", "cardano" to "cardano", "ada" to "cardano"
    )

    /** Classify a message. Null = no live data needed, answer from model. */
    fun parseIntent(input: String): WebIntent? {
        val t = input.trim().lowercase()

        // Weather: "weather in Bandung", "how's the weather", "is it raining in Jakarta"
        Regex("(weather|rain|temperature|forecast|hot|cold|humid)").containsMatchIn(t).let { hit ->
            if (hit) {
                val place = Regex("(?:\\bin\\b|\\bat\\b|\\bfor\\b)\\s+([a-z' ]{2,30})").find(t)
                    ?.groupValues?.get(1)?.trim()?.trimEnd('?', '.', ' ')
                    ?.takeIf { it.isNotEmpty() } ?: "Jakarta"
                return WebIntent.Weather(place)
            }
        }

        // Crypto first (before fiat, "bitcoin price in dollars" is crypto)
        CRYPTO.keys.firstOrNull { it in t }?.let { key ->
            if (Regex("(price|worth|cost|rate|how much|value)").containsMatchIn(t))
                return WebIntent.Crypto(CRYPTO[key]!!)
        }

        // Forex: "dollar to rupiah", "usd idr rate", "euro price"
        val codes = FIAT.keys.sortedByDescending { it.length }
        val found = codes.filter { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        if (found.size >= 2 && Regex("(to|in|vs|rate|price|exchange|convert)").containsMatchIn(t)) {
            return WebIntent.Forex(FIAT[found[0]]!!, FIAT[found[1]]!!)
        }
        if (found.size == 1 && Regex("(rate|price|exchange|kurs)").containsMatchIn(t)) {
            val single = FIAT[found[0]]!!
            // Single currency mentioned: assume vs IDR for Darren, vs USD otherwise
            val other = if (single == "IDR") "USD" else "IDR"
            return WebIntent.Forex(single, other)
        }

        // Knowledge: "who is X", "what is X", "tell me about X"
        Regex("^(who is|who was|what is|what are|what was|tell me about|define)\\s+(.+)")
            .find(t)?.let { m ->
                val topic = m.groupValues[2].trim().trimEnd('?', '.', ' ')
                if (topic.length >= 3 && !Regex("^(the time|the date|my battery|it|that|this)$")
                        .matches(topic)) return WebIntent.Wiki(topic)
            }
        return null
    }

    /** Fetch + format for prompt injection. Null on any failure (fail silent). */
    fun fetch(intent: WebIntent): String? = runCatching {
        when (intent) {
            is WebIntent.Weather -> fetchWeather(intent.place)
            is WebIntent.Forex -> fetchForex(intent.from, intent.to)
            is WebIntent.Crypto -> fetchCrypto(intent.symbol)
            is WebIntent.Wiki -> fetchWiki(intent.topic)
        }
    }.getOrNull()

    // ---- sources ----

    private fun get(url: String, timeoutMs: Int = 8000): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "Jarvis/7 (DarrenAI)")
        if (c.responseCode != 200) return null
        c.inputStream.bufferedReader().readText()
    }.getOrNull()

    private fun geocode(place: String): Triple<Double, Double, String>? {
        val q = URLEncoder.encode(place, "UTF-8")
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1") ?: return null
        val r = JSONObject(body).optJSONArray("results") ?: return null
        if (r.length() == 0) return null
        val o = r.getJSONObject(0)
        return Triple(o.getDouble("latitude"), o.getDouble("longitude"),
            listOfNotNull(o.optString("name", null), o.optString("country", null))
                .joinToString(", "))
    }

    internal fun parseWeatherJson(label: String, body: String): String? = runCatching {
        val cur = JSONObject(body).getJSONObject("current")
        val t = cur.getDouble("temperature_2m")
        val h = cur.optInt("relative_humidity_2m", -1)
        val code = cur.optInt("weather_code", -1)
        val codes = mapOf(0 to "clear sky", 1 to "mostly clear", 2 to "partly cloudy",
            3 to "overcast", 45 to "fog", 48 to "icy fog", 51 to "light drizzle",
            61 to "light rain", 63 to "rain", 65 to "heavy rain", 71 to "light snow",
            80 to "showers", 81 to "showers", 82 to "violent showers", 95 to "thunderstorm")
        val desc = codes[code] ?: "changing conditions"
        buildString {
            append("Live weather in $label: $desc, ${"%.0f".format(t)} degrees Celsius")
            if (h >= 0) append(", humidity $h percent")
        }
    }.getOrNull()

    private fun fetchWeather(place: String): String? {
        val (lat, lon, label) = geocode(place) ?: geocode("Jakarta") ?: return null
        val body = get("https://api.open-meteo.com/v1/forecast?" +
            "latitude=$lat&longitude=$lon&current=temperature_2m," +
            "relative_humidity_2m,weather_code") ?: return null
        return parseWeatherJson(label, body)
    }

    internal fun parseForexJson(from: String, to: String, body: String): String? = runCatching {
        val rate = JSONObject(body).getJSONObject("rates").getDouble(to)
        val inv = 1.0 / rate
        val fmt: (Double) -> String = { v ->
            if (v >= 1000) "%,.0f".format(java.util.Locale.US, v)
            else if (v >= 1) "%,.2f".format(java.util.Locale.US, v)
            else "%.4f".format(java.util.Locale.US, v)
        }
        "Live exchange rate: 1 $from equals ${fmt(rate)} $to " +
            "(1 $to equals ${fmt(inv)} $from)."
    }.getOrNull()

    private fun fetchForex(from: String, to: String): String? {
        val body = get("https://open.er-api.com/v6/latest/$from") ?: return null
        return parseForexJson(from, to, body)
    }

    internal fun parseCryptoJson(symbol: String, body: String): String? = runCatching {
        val o = JSONObject(body).getJSONObject(symbol)
        val usd = o.getDouble("usd")
        val name = symbol.replaceFirstChar { it.uppercase() }
        "Live crypto price: $name at ${"%,.0f".format(usd)} US dollars."
    }.getOrNull()

    private fun fetchCrypto(symbol: String): String? {
        val body = get("https://api.coingecko.com/api/v3/simple/price" +
            "?ids=$symbol&vs_currencies=usd") ?: return null
        return parseCryptoJson(symbol, body)
    }

    internal fun parseWikiJson(topic: String, body: String): String? = runCatching {
        val o = JSONObject(body)
        if (o.optString("type") == "disambiguation") return null
        val extract = o.optString("extract", "").take(600)
        if (extract.isBlank()) return null
        "From Wikipedia ($topic): $extract"
    }.getOrNull()

    private fun fetchWiki(topic: String): String? {
        val q = URLEncoder.encode(topic.trim(), "UTF-8")
        val body = get("https://en.wikipedia.org/api/rest_v1/page/summary/$q") ?: return null
        return parseWikiJson(topic, body)
    }
}
