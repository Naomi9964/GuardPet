package com.max.guardpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Watches messaging-app notifications and scores the text with the
// baked-in scam SMS model. Enable in system notification-access settings.
class GuardNotificationListener : NotificationListenerService() {

    private val smsPkgs = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.sonyericsson.conversations"
    )

    private var smsModel: RiskEngine.SmsModel? = null

    override fun onListenerConnected() {
        smsModel = RiskEngine.loadSms(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in smsPkgs) return
        if (sbn.isOngoing) return
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return
        if (text.length < 8) return
        val p = smsModel?.let { RiskEngine.smsScore(it, text) }
        val scam = if (p != null) p >= 0.5 else keywordHit(text)
        if (!scam) return
        postScamNotification()
        try {
            startService(
                Intent(this, GuardService::class.java)
                    .setAction(GuardService.ACTION_ALERT_SMS)
                    .putExtra("score", p ?: -1.0)
            )
        } catch (_: Exception) {
        }
    }

    private fun keywordHit(t: String): Boolean {
        val kws = listOf(
            "中獎", "匯款", "點擊連結", "點此連結", "帳戶異常", "積分兌換",
            "恭喜獲得", "winner", "claim your prize", "verify your account", "suspended"
        )
        return kws.any { t.contains(it, ignoreCase = true) } &&
            t.contains("http", ignoreCase = true)
    }

    private fun postScamNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("guardpet_alerts", "GuardPet alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "guardpet_alerts")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(getString(R.string.app_name))
            setContentText(getString(R.string.alert_sms_scam).take(120))
            setSmallIcon(R.drawable.cat_alert_1)
            setAutoCancel(true)
        }
        try { nm.notify(44, builder.build()) } catch (_: Exception) {}
    }
}
