package com.max.guardpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService

// Blocks numbers on the user's blocklist. Must be set as the call
// screening role in system settings to take effect.
class GuardCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = (callDetails.handle?.schemeSpecificPart ?: "").filter { it.isDigit() }
        val prefs = getSharedPreferences("guardpet", MODE_PRIVATE)
        val blocked = prefs.getStringSet("blocklist", emptySet()) ?: emptySet()
        val hit = number.isNotEmpty() && blocked.any { b ->
            b.isNotEmpty() && (number.endsWith(b) || b.endsWith(number))
        }
        val resp = CallResponse.Builder()
        if (hit) {
            resp.setDisallowCall(true)
            if (Build.VERSION.SDK_INT >= 29) resp.setRejectCall(true)
            else resp.setSilenceCall(true)
            postBlockedNotification(number)
            try {
                startService(
                    Intent(this, GuardService::class.java)
                        .setAction(GuardService.ACTION_ALERT_CALL)
                        .putExtra("number", number)
                )
            } catch (_: Exception) {
            }
        } else {
            resp.setDisallowCall(false)
        }
        respondToCall(callDetails, resp.build())
    }

    private fun postBlockedNotification(number: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("guardpet_alerts", "GuardPet alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val text = getString(R.string.alert_call_blocked, number.ifEmpty { "?" })
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "guardpet_alerts")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(getString(R.string.app_name))
            setContentText(text.take(120))
            setSmallIcon(R.drawable.cat_alert_1)
            setAutoCancel(true)
        }
        try { nm.notify(43, builder.build()) } catch (_: Exception) {}
    }
}
