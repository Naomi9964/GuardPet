package com.max.guardpet

import android.content.Context
import kotlin.math.exp

// Baked-in logistic-regression inference with zero dependencies.
// Models are trained offline; only vocab/weights/bias ship in assets.
object RiskEngine {

    data class SmsModel(
        val index: Map<String, Int>,
        val idf: DoubleArray,
        val weights: DoubleArray,
        val bias: Double
    )

    data class AppModel(
        val index: Map<String, Int>,
        val weights: DoubleArray,
        val bias: Double
    )

    fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

    fun loadSms(ctx: Context): SmsModel? = try {
        val t = ctx.assets.open("sms_model.json").bufferedReader().readText()
        val vocab = strArray(t, "vocab")
        SmsModel(
            vocab.mapIndexed { i, s -> s to i }.toMap(),
            dblArray(t, "idf"),
            dblArray(t, "weights"),
            dbl(t, "bias")
        )
    } catch (_: Exception) {
        null
    }

    fun loadApp(ctx: Context): AppModel? = try {
        val t = ctx.assets.open("app_model.json").bufferedReader().readText()
        val perms = strArray(t, "permissions")
        AppModel(
            perms.mapIndexed { i, s -> s to i }.toMap(),
            dblArray(t, "weights"),
            dbl(t, "bias")
        )
    } catch (_: Exception) {
        null
    }

    // P(scam) for a message. null when no usable tokens.
    fun smsScore(m: SmsModel, text: String): Double? {
        val counts = HashMap<Int, Int>()
        var total = 0
        for (tok in tokenize(text)) {
            val i = m.index[tok] ?: continue
            counts[i] = (counts[i] ?: 0) + 1
            total++
        }
        if (total == 0) return null
        var s = m.bias
        for ((i, c) in counts) s += (c.toDouble() / total) * m.idf[i] * m.weights[i]
        return sigmoid(s)
    }

    // P(malicious) for a set of granted permissions.
    fun appScore(m: AppModel, granted: Collection<String>): Double {
        var s = m.bias
        for (p in granted) {
            val i = m.index[p] ?: continue
            s += m.weights[i]
        }
        return sigmoid(s)
    }

    fun tokenize(text: String): List<String> =
        text.lowercase().split("[^a-z0-9]+".toRegex()).filter { it.length >= 2 }

    // Offline lexical phishing score. >= 2.5 is treated as risky.
    fun urlRisk(raw: String): Double {
        val u = raw.lowercase()
        var s = 0.0
        if (Regex("https?://\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(u)) s += 2.0
        if (u.contains("xn--")) s += 2.0
        if (u.contains("@")) s += 2.0
        // TLD must come from the host only: "top/verify" is not the TLD "top".
        val host = u.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
        val tld = host.substringAfterLast(".", "")
        if (tld in setOf("tk", "ml", "ga", "cf", "gq", "buzz", "top", "xyz", "click", "link")) s += 2.0
        if (u.length > 75) s += 1.0
        if (host.count { it == '.' } > 3) s += 1.0
        // Exact host match: substring "t.co" also matches "microsoft.com".
        // A shortener hides the real destination, so it warns on its own.
        if (host == "bit.ly" || host.endsWith(".bit.ly") ||
            host == "tinyurl.com" || host.endsWith(".tinyurl.com") ||
            host == "t.co" || host.endsWith(".t.co")) s += 2.0
        return s
    }

    // Plain phrases for the rules that fired. Mirrors urlRisk's checks one by one;
    // only scoring rules are listed, so the reasons never claim more than the verdict.
    fun urlReasons(raw: String): List<String> {
        val u = raw.lowercase()
        val out = mutableListOf<String>()
        if (Regex("https?://\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(u)) out.add("IP address used as host")
        if (u.contains("xn--")) out.add("punycode (xn--) in domain")
        if (u.contains("@")) out.add("@ in URL")
        val host = u.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
        val tld = host.substringAfterLast(".", "")
        if (tld in setOf("tk", "ml", "ga", "cf", "gq", "buzz", "top", "xyz", "click", "link")) {
            out.add("suspicious TLD .$tld")
        }
        if (u.length > 75) out.add("very long URL")
        if (host.count { it == '.' } > 3) out.add("many subdomains")
        if (host == "bit.ly" || host.endsWith(".bit.ly") ||
            host == "tinyurl.com" || host.endsWith(".tinyurl.com") ||
            host == "t.co" || host.endsWith(".t.co")) out.add("URL shortener hides the destination")
        return out
    }

    fun extractUrls(text: String): List<String> =
        Regex("https?://[^\\s]+").findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?') }.toList()

    // --- minimal JSON readers for our fixed format ---

    private fun section(t: String, key: String): String {
        val k = t.indexOf("\"$key\"")
        val c = t.indexOf(":", k)
        val arrStart = t.indexOf("[", c)
        if (arrStart < 0 || (t.indexOf("{", c) in 0 until arrStart)) {
            // scalar value
            val end = t.indexOfFirstFrom(setOf(',', '}'), c + 1)
            return t.substring(c + 1, end)
        }
        var depth = 0
        var inStr = false
        for (i in arrStart until t.length) {
            val ch = t[i]
            if (ch == '"' && t.getOrNull(i - 1) != '\\') inStr = !inStr
            if (!inStr) {
                if (ch == '[') depth++
                if (ch == ']') {
                    depth--
                    if (depth == 0) return t.substring(arrStart, i + 1)
                }
            }
        }
        return "[]"
    }

    private fun String.indexOfFirstFrom(chars: Set<Char>, from: Int): Int {
        for (i in from until length) if (this[i] in chars) return i
        return length
    }

    private fun strArray(t: String, key: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(section(t, key)).map {
            it.groupValues[1].replace("\\\"", "\"")
        }.toList()

    private fun dblArray(t: String, key: String): DoubleArray =
        section(t, key).trim().removePrefix("[").removeSuffix("]")
            .split(",").filter { it.isNotBlank() }.map { it.trim().toDouble() }.toDoubleArray()

    private fun dbl(t: String, key: String): Double = section(t, key).trim().toDouble()
}
