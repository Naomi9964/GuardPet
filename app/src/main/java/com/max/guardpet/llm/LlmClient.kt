// Day 1 (2026-10-27) — LlmClient, wired to the opt-in UI on 10/28 via LlmSettings.
// Zero-dependency HttpURLConnection implementation, plus:
//   1. failure / no key / no network -> null (caller falls back to scripted copy, silently)
//   2. one API call per alert id (in-memory cache, never persisted)
//   3. 10-second debounce for conversational chat
// Model id "muse-spark-1.3" verified live: smoke test returned 200 on 2026-10-27.
// Default is OFF: no key -> FakeLlmClient, so nothing ever calls the network unprompted.

package com.max.guardpet.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface LlmClient {
    // Blocking network call — always invoke off the main thread.
    @Throws(LlmException::class)
    fun chat(system: String, user: String, temperature: Double = 0.0, maxTokens: Int = 1000): String
}

class MetaModelClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL
) : LlmClient {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.meta.ai/v1"
        const val DEFAULT_MODEL = "muse-spark-1.3" // verified live 2026-10-27
    }

    override fun chat(system: String, user: String, temperature: Double, maxTokens: Int): String {
        // Reasoning is always on and counts toward max_tokens. Minimal effort
        // keeps short explanations under the cap (verified 2026-10-29: ~313
        // reasoning tokens for an alert explanation, vs ~1312 at default).
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("reasoning_effort", "minimal")
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString()

        val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw LlmException("Meta Model API HTTP $code: ${text.take(200)}")
            }
            return try {
                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } catch (e: Exception) {
                throw LlmException("Unparseable chat response: ${text.take(200)}", e)
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Network call failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}

// Offline stand-in for UI dev before the API key exists. Never ships as the real path.
class FakeLlmClient(private val canned: String = "[LLM off — opt-in 未開啟，這是假回覆]") : LlmClient {
    override fun chat(system: String, user: String, temperature: Double, maxTokens: Int): String = canned
}

// Checklist item 1: never throw to the UI. Null means "use the scripted copy".
// Blank key also returns null here (no network attempt at all).
// Deliberately does NOT implement LlmClient: its only entry point is chatOrNull,
// so no caller can accidentally get an exception instead of a null.
class SafeLlmClient(private val inner: LlmClient?) {
    // Null-returning entry point for callers: null -> scripted copy, no crash, no retry loop.
    fun chatOrNull(system: String, user: String, temperature: Double = 0.0, maxTokens: Int = 1000): String? {
        return try {
            inner?.chat(system, user, temperature, maxTokens)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}

// Checklist item 2: one API call per alert id. In-memory only — cleared on process death,
// never written to disk, so no alert history is persisted by this layer.
// A null result is NOT cached: a failed call may succeed on the next alert.
class AlertExplanationCache(private val safe: SafeLlmClient) {
    private val cache = HashMap<String, String>()

    @Synchronized
    fun explain(alertId: String, system: String, user: String): String? {
        cache[alertId]?.let { return it }
        val text = safe.chatOrNull(system, user, temperature = 0.0, maxTokens = 1000) ?: return null
        cache[alertId] = text
        return text
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}

// Checklist item 3: conversational chat, 10-second debounce.
// A call inside the window returns null (caller shows scripted copy / waits) instead of
// firing a second request. System clock only; no persisted state.
class DebouncedChat(private val safe: SafeLlmClient) {
    private var lastCallMs: Long = 0L

    @Synchronized
    fun chat(system: String, user: String, temperature: Double = 0.7, maxTokens: Int = 1500): String? {
        val now = System.currentTimeMillis()
        if (now - lastCallMs < 10_000L) return null
        lastCallMs = now
        return safe.chatOrNull(system, user, temperature, maxTokens)
    }
}

// Wiring helper for the opt-in UI: blank / missing key -> Fake, never a real client.
// The real client is only built after the user turns opt-in ON and enters a key.
object LlmClientFactory {
    fun clientFor(apiKey: String?): LlmClient {
        return if (apiKey.isNullOrBlank()) FakeLlmClient() else MetaModelClient(apiKey)
    }

    fun safeClientFor(apiKey: String?): SafeLlmClient {
        return SafeLlmClient(if (apiKey.isNullOrBlank()) null else MetaModelClient(apiKey))
    }
}
