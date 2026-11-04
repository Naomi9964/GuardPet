package com.max.guardpet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import com.max.guardpet.llm.LlmPrompts
import com.max.guardpet.llm.LlmSettings

// Target of "share link" — scores the URL offline and shows a verdict.
class UrlCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: ""
        val urls = RiskEngine.extractUrls(text)
        if (urls.isEmpty()) {
            finish()
            return
        }
        val url = urls.first()
        val risky = RiskEngine.urlRisk(url) >= 2.0
        try {
            startService(
                Intent(this, GuardService::class.java)
                    .setAction(GuardService.ACTION_ALERT_URL)
                    .putExtra("ok", !risky)
                    .putExtra("url", url)
            )
        } catch (_: Exception) {
        }
        val prefs = getSharedPreferences(LlmSettings.PREFS, MODE_PRIVATE)
        val optedIn = LlmSettings.isOptedIn(prefs)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(
                if (risky) getString(R.string.alert_url_risky)
                else getString(R.string.alert_url_ok)
            )
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .apply {
                if (risky && optedIn) setNeutralButton(getString(R.string.ai_explain)) { _, _ -> }
            }
            .show()
        // Neutral button with an empty listener above, then override the click
        // so tapping it fetches the explanation instead of dismissing.
        if (risky && optedIn) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { btn ->
                btn.isEnabled = false
                val reasons = RiskEngine.urlReasons(url)
                val system = LlmPrompts.linkExplanation(
                    LlmPrompts.langName(prefs, resources),
                    url,
                    reasons.joinToString("; ")
                )
                Thread {
                    val explanation = LlmSettings.safeClient(prefs)
                        .chatOrNull(system, "Explain why this link is suspicious.", temperature = 0.0, maxTokens = 1000)
                    // Null -> silent fallback: the scripted verdict stays, no error shown.
                    if (explanation != null) runOnUiThread {
                        try {
                            dialog.setMessage(explanation)
                        } catch (_: Exception) {
                        }
                    }
                }.start()
            }
        }
    }
}
