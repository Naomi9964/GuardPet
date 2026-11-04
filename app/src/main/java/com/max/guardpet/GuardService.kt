package com.max.guardpet

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.max.guardpet.llm.AlertExplanationCache
import com.max.guardpet.llm.DebouncedChat
import com.max.guardpet.llm.LlmPrompts
import com.max.guardpet.llm.LlmSettings
import com.max.guardpet.llm.MuseVoiceTranscriber
import java.io.File
import java.util.Calendar
import kotlin.math.hypot
import kotlin.random.Random

class GuardService : Service() {

    companion object {
        const val ACTION_STOP = "com.max.guardpet.action.STOP"
        const val ACTION_RELOAD = "com.max.guardpet.action.RELOAD"
        const val ACTION_SCAN_NOW = "com.max.guardpet.action.SCAN_NOW"
        const val ACTION_ALERT_CALL = "com.max.guardpet.action.ALERT_CALL"
        const val ACTION_ALERT_SMS = "com.max.guardpet.action.ALERT_SMS"
        const val ACTION_ALERT_URL = "com.max.guardpet.action.ALERT_URL"
        const val ACTION_ALERT_WIFI = "com.max.guardpet.action.ALERT_WIFI"
        private const val CHANNEL_ID = "guardpet_channel"
        private const val ALERT_CHANNEL_ID = "guardpet_alerts"
        private const val NOTIF_ID = 42
        private const val ALERT_NOTIF_ID = 43
        private const val PET_DP = 104
        private const val TICK_MS = 50L
        private const val SCAN_INTERVAL_MS = 2 * 60 * 60 * 1000L
        @Volatile var isRunning = false
            private set
    }

    private enum class State { IDLE, WALK, SLEEP, ALERT }

    private data class PetSet(
        val idle: IntArray, val walk: IntArray, val alert: IntArray,
        val happy: Int, val sleep: IntArray
    )

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var rootView: FrameLayout
    private lateinit var petView: ImageView
    private lateinit var petParams: WindowManager.LayoutParams
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleUntil = 0L

    private var density = 1f
    private var petPx = 0
    private var maxX = 0
    private var maxY = 0

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var stateUntil = 0L
    private var frameIdx = 0
    private var lastFrameAt = 0L
    private var nextBlinkAt = 0L
    private var dragging = false
    private var targetX = 0
    private var targetY = 0
    private var speedPxPerTick = 5f

    private var customBmp: Bitmap? = null
    private val isCustom get() = customBmp != null
    private var customShown = false

    private lateinit var petSet: PetSet
    private var smsModel: RiskEngine.SmsModel? = null
    private var appModel: RiskEngine.AppModel? = null
    private var nextScanAt = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // Current alert, for tap-to-explain (feature 1). Null when no alert is showing.
    private var currentAlertId: String? = null
    private var currentVerdictFacts: String? = null
    private var currentScripted: String? = null
    private var fetchingAlertId: String? = null
    private var explanationCache: AlertExplanationCache? = null
    private var explanationCacheState = ""

    // Chat history, in memory only: (isUser, text). Capped at 6 rounds (12 lines).
    private val chatHistory = ArrayList<Pair<Boolean, String>>()
    private var chatDebounced: DebouncedChat? = null
    private var chatDebouncedState = ""
    private var chatHistoryView: TextView? = null
    private var chatInput: EditText? = null
    private var voiceRecorder: MuseVoiceTranscriber.Recorder? = null

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ================= lifecycle =================

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("guardpet", MODE_PRIVATE)
        density = resources.displayMetrics.density
        petPx = (PET_DP * density).toInt()
        speedPxPerTick = 2.4f * density
        smsModel = RiskEngine.loadSms(this)
        appModel = RiskEngine.loadApp(this)
        loadPetSet()
        computeBounds()
        createPetView()
        createBubble()
        startForegroundCompat()
        greet()
        toIdle(1200, 2500)
        nextScanAt = SystemClock.uptimeMillis() + 5000 // first scan shortly after start
        watchWifi()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("autostart", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                loadPetSet()
                customShown = false
                showFrame(currentIdle())
                return START_STICKY
            }
            ACTION_SCAN_NOW -> {
                handler.post { runScan() }
                return START_STICKY
            }
            ACTION_ALERT_CALL -> {
                val n = intent.getStringExtra("number") ?: ""
                showAlert(
                    getString(R.string.alert_call_blocked, n.ifEmpty { "?" }),
                    false,
                    "call:$n",
                    "Call from ${n.ifEmpty { "unknown" }} blocked: the number matches the user's blocklist."
                )
                return START_STICKY
            }
            ACTION_ALERT_SMS -> {
                val score = intent.getDoubleExtra("score", -1.0)
                val facts = if (score >= 0) {
                    "SMS flagged as scam by the on-device model, P(scam)=${"%.2f".format(score)}."
                } else {
                    "SMS matched scam keywords and contains a link."
                }
                showAlert(
                    getString(R.string.alert_sms_scam),
                    false,
                    "sms:" + SystemClock.uptimeMillis(),
                    facts
                )
                return START_STICKY
            }
            ACTION_ALERT_URL -> {
                val ok = intent.getBooleanExtra("ok", true)
                val url = intent.getStringExtra("url") ?: ""
                val reasons = RiskEngine.urlReasons(url)
                showAlert(
                    if (ok) getString(R.string.alert_url_ok)
                    else getString(R.string.alert_url_risky) + "\n" + url.take(60),
                    false,
                    if (ok) null else "url:$url",
                    if (ok) null else "URL judged suspicious by the on-device checker. " +
                        "Rules triggered: " + reasons.joinToString("; ")
                )
                return START_STICKY
            }
            ACTION_ALERT_WIFI -> {
                showAlert(
                    getString(R.string.alert_wifi_open),
                    false,
                    "wifi:" + SystemClock.uptimeMillis(),
                    "Device connected to an open Wi-Fi network with no password or encryption."
                )
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tick)
        netCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) { }
        }
        bubbleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        try { wm.removeView(rootView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= setup =================

    private fun loadPetSet() {
        customBmp = null
        customShown = false
        val f = File(filesDir, "pet_custom.png")
        if (f.exists()) customBmp = BitmapFactory.decodeFile(f.absolutePath)
        petSet = when (prefs.getString("pet", "cat")) {
            "wolf" -> PetSet(
                intArrayOf(R.drawable.pet_idle_1, R.drawable.pet_idle_2),
                intArrayOf(R.drawable.pet_walk_1, R.drawable.pet_walk_2, R.drawable.pet_walk_3, R.drawable.pet_walk_4),
                intArrayOf(R.drawable.pet_atk_1, R.drawable.pet_atk_2),
                R.drawable.pet_happy,
                intArrayOf(R.drawable.pet_sleep_1, R.drawable.pet_sleep_2)
            )
            else -> PetSet(
                intArrayOf(R.drawable.cat_idle_1, R.drawable.cat_idle_2),
                intArrayOf(R.drawable.cat_walk_1, R.drawable.cat_walk_2, R.drawable.cat_walk_3, R.drawable.cat_walk_4),
                intArrayOf(R.drawable.cat_alert_1, R.drawable.cat_alert_2),
                R.drawable.cat_happy,
                intArrayOf(R.drawable.cat_sleep_1, R.drawable.cat_sleep_2)
            )
        }
    }

    private fun computeBounds() {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            w = m.widthPixels; h = m.heightPixels
        }
        maxX = (w - petPx).coerceAtLeast(0)
        maxY = (h - petPx - (48 * density).toInt()).coerceAtLeast(0)
    }

    private fun createPetView() {
        rootView = FrameLayout(this)
        petView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(petPx, petPx)
        }
        rootView.addView(petView)
        petParams = WindowManager.LayoutParams(
            petPx, petPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = maxX / 2
            y = (maxY * 0.7f).toInt()
        }
        wm.addView(rootView, petParams)
        petView.setOnTouchListener(::onPetTouch)
    }

    private fun createBubble() {
        bubbleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0xDD1A1A22.toInt())
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        bubbleParams = WindowManager.LayoutParams(
            (260 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(bubbleView, bubbleParams) } catch (_: Exception) { bubbleView = null }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GuardPet", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "GuardPet alerts", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val stopIntent = Intent(this, GuardService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(getString(R.string.app_name))
            setContentText("🐱 patrolling")
            setSmallIcon(R.drawable.cat_idle_1)
            setOngoing(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.cat_idle_1), "Stop", stopPi
                ).build()
            )
        }
        val notif = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ================= frames =================

    private fun currentIdle() = petSet.idle[0]

    private fun showFrame(resId: Int) {
        if (isCustom) {
            if (!customShown) {
                petView.setImageBitmap(customBmp)
                customShown = true
            }
        } else {
            customShown = false
            petView.setImageResource(resId)
        }
    }

    private fun resetPose() {
        petView.translationX = 0f
        petView.translationY = 0f
        petView.rotation = 0f
    }

    // ================= dialogue =================

    private fun showBubble(text: String, ms: Long) {
        val b = bubbleView ?: return
        b.text = text
        b.visibility = View.VISIBLE
        bubbleUntil = SystemClock.uptimeMillis() + ms
        positionBubble()
    }

    private fun hideBubble() {
        bubbleView?.visibility = View.GONE
    }

    private fun positionBubble() {
        val b = bubbleView ?: return
        val p = bubbleParams ?: return
        p.x = (petParams.x + petPx / 2 - 130 * density).toInt().coerceIn(0, maxX)
        p.y = (petParams.y - 12 * density).toInt() - b.measuredHeight.coerceAtLeast((40 * density).toInt())
        if (p.y < 0) p.y = petParams.y + petPx + (8 * density).toInt()
        try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
    }

    private fun greet() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val msg = when (hour) {
            in 5..11 -> getString(R.string.dlg_greet_morning)
            in 12..17 -> getString(R.string.dlg_greet_afternoon)
            else -> getString(R.string.dlg_greet_evening)
        }
        handler.postDelayed({ showBubble(msg, 5000) }, 1500)
        maybeDailyTip()
    }

    private fun maybeDailyTip() {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (prefs.getInt("last_tip_day", -1) == today) return
        prefs.edit().putInt("last_tip_day", today).apply()
        val tips = listOf(
            R.string.dlg_tip_1, R.string.dlg_tip_2, R.string.dlg_tip_3, R.string.dlg_tip_4
        )
        handler.postDelayed({ showBubble(getString(tips.random()), 6000) }, 9000)
    }

    // ================= alerts =================

    private fun showAlert(text: String, notify: Boolean = true, alertId: String? = null, verdictFacts: String? = null) {
        state = State.ALERT
        stateUntil = SystemClock.uptimeMillis() + 9000
        frameIdx = 0
        lastFrameAt = 0L
        currentAlertId = alertId
        currentVerdictFacts = verdictFacts
        currentScripted = text
        // A newer alert supersedes any in-flight explanation fetch.
        fetchingAlertId = null
        // Tap hint only when an explanation will actually work (opted in + key).
        // The notification keeps the plain verdict text, no hint.
        val bubbleText = if (alertId != null && verdictFacts != null && LlmSettings.isOptedIn(prefs)) {
            text + "\n" + getString(R.string.ai_tap_hint)
        } else text
        showBubble(bubbleText, 9000)
        if (notify) postAlertNotification(text)
    }

    // ================= AI explanation (feature 1) =================

    // Fingerprint of the opt-in state. Both caches rebuild when it changes,
    // so a toggled switch or a new key never serves a stale client.
    private fun llmState(): String =
        prefs.getBoolean(LlmSettings.KEY_OPT_IN, false).toString() + ":" +
            !prefs.getString(LlmSettings.KEY_API_KEY, null).isNullOrBlank()

    private fun explanationCache(): AlertExplanationCache {
        val state = llmState()
        if (explanationCache == null || explanationCacheState != state) {
            explanationCache = AlertExplanationCache(LlmSettings.safeClient(prefs))
            explanationCacheState = state
        }
        return explanationCache!!
    }

    private fun chatDebounced(): DebouncedChat {
        val state = llmState()
        if (chatDebounced == null || chatDebouncedState != state) {
            chatDebounced = DebouncedChat(LlmSettings.safeClient(prefs))
            chatDebouncedState = state
        }
        return chatDebounced!!
    }

    // Runs off the main thread. Null result -> restore the scripted alert
    // text (the thinking placeholder replaced it), no error is shown.
    private fun fetchAlertExplanation(alertId: String, facts: String, scripted: String) {
        val system = LlmPrompts.alertExplanation(LlmPrompts.langName(prefs, resources), facts)
        Thread {
            val text = explanationCache().explain(alertId, system, scripted)
            handler.post {
                if (fetchingAlertId == alertId) fetchingAlertId = null
                // A newer alert supersedes this fetch; never paint a stale
                // explanation over it.
                if (currentAlertId != alertId) return@post
                if (text != null) {
                    showBubble(text, 15000)
                } else {
                    val hint = if (LlmSettings.isOptedIn(prefs)) "\n" + getString(R.string.ai_tap_hint) else ""
                    showBubble(scripted + hint, 9000)
                }
            }
        }.start()
    }

    private fun postAlertNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(getString(R.string.app_name))
            setContentText(text.take(120))
            setSmallIcon(R.drawable.cat_alert_1)
            setAutoCancel(true)
        }
        try { nm.notify(ALERT_NOTIF_ID, builder.build()) } catch (_: Exception) {}
    }

    // ================= scanning =================

    private fun runScan() {
        nextScanAt = SystemClock.uptimeMillis() + SCAN_INTERVAL_MS
        val risky = scanApps()
        val known = prefs.getStringSet("alerted_pkgs", emptySet()) ?: emptySet()
        val fresh = risky.filter { it.pkg !in known }
        if (fresh.isNotEmpty()) {
            prefs.edit().putStringSet("alerted_pkgs", known + fresh.map { it.pkg }.toSet()).apply()
            val worst = fresh.first()
            val msg = if (fresh.size == 1) {
                getString(R.string.alert_app_risky, worst.label, worst.permText)
            } else {
                getString(R.string.dlg_scan_found, fresh.size)
            }
            val facts = fresh.take(3).joinToString("; ") {
                "App \"${it.label}\" flagged RISK, P=${"%.2f".format(it.score)}, granted: ${it.permText}"
            }
            showAlert(msg, true, "app:" + fresh.joinToString(",") { it.pkg }, facts)
        } else {
            showBubble(getString(R.string.dlg_scan_clean), 4000)
        }
    }

    private data class RiskyApp(val pkg: String, val label: String, val permText: String, val score: Double)

    // ================= open-wifi warning =================

    // Event-driven: fires only on network changes, no polling, no internet needed.
    private fun watchWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { wifiTransition() }
            }
            override fun onLost(network: Network) {
                handler.post { wifiTransition() }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                handler.post { wifiTransition() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) { }
        handler.post { wifiTransition() } // check current state at startup
    }

    private fun wifiTransition() {
        val open = isOnOpenWifi()
        val was = prefs.getBoolean("wifi_open", false)
        prefs.edit().putBoolean("wifi_open", open).apply()
        if (open && !was) showAlert(
            getString(R.string.alert_wifi_open),
            true,
            "wifi:" + SystemClock.uptimeMillis(),
            "Device connected to an open Wi-Fi network with no password or encryption."
        )
    }

    // Offline: reads the current Wi-Fi security type locally, no network traffic.
    // Needs API 30+ (Android 11) for WifiInfo.currentSecurityType; older APIs
    // cannot see the security type without location permission, so we stay silent.
    private fun isOnOpenWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = caps.transportInfo as? WifiInfo ?: return false
                info.currentSecurityType == WifiInfo.SECURITY_TYPE_OPEN
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun scanApps(): List<RiskyApp> {
        val out = mutableListOf<RiskyApp>()
        val pm = packageManager
        val pkgs = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) {
            return out
        }
        for (pkg in pkgs) {
            if (pkg.packageName == packageName) continue
            val ai = pkg.applicationInfo ?: continue
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val reqPerms = pkg.requestedPermissions ?: continue
            val flags = pkg.requestedPermissionsFlags
            val granted = reqPerms.filterIndexed { i, p ->
                p.startsWith("android.permission.") &&
                    (flags.getOrNull(i)?.and(PackageInfo.REQUESTED_PERMISSION_GRANTED) ?: 0) != 0
            }
            if (granted.isEmpty()) continue
            val score = appModel?.let { RiskEngine.appScore(it, granted) }
                ?: heuristicScore(granted)
            if (score >= 0.5) {
                val label = pm.getApplicationLabel(ai)?.toString() ?: pkg.packageName
                out.add(RiskyApp(pkg.packageName, label, plainPerms(granted), score))
            }
        }
        return out.sortedByDescending { it.score }
    }

    private val heuristicWeights = mapOf(
        "android.permission.SEND_SMS" to 2.5,
        "android.permission.RECEIVE_SMS" to 2.0,
        "android.permission.READ_SMS" to 2.0,
        "android.permission.READ_CONTACTS" to 1.2,
        "android.permission.READ_CALL_LOG" to 1.2,
        "android.permission.RECORD_AUDIO" to 1.0,
        "android.permission.CAMERA" to 0.6,
        "android.permission.ACCESS_FINE_LOCATION" to 0.8,
        "android.permission.CALL_PHONE" to 0.8,
        "android.permission.READ_PHONE_STATE" to 0.5
    )

    private fun heuristicScore(granted: List<String>): Double {
        var s = -2.0
        for (p in granted) s += heuristicWeights[p] ?: 0.0
        return RiskEngine.sigmoid(s)
    }

    private fun plainPerms(granted: List<String>): String {
        val names = granted.mapNotNull { p ->
            val short = p.removePrefix("android.permission.")
            val id = resources.getIdentifier("perm_$short", "string", packageName)
            if (id != 0) getString(id) else null
        }.distinct().take(3)
        return names.ifEmpty { granted.take(2).map { it.removePrefix("android.permission.") } }.joinToString("、")
    }

    // ================= behavior loop =================

    private fun toIdle(minMs: Long, maxMs: Long) {
        state = State.IDLE
        stateUntil = SystemClock.uptimeMillis() + Random.nextLong(minMs, maxMs)
        nextBlinkAt = SystemClock.uptimeMillis() + Random.nextLong(1500, 3500)
        resetPose()
        showFrame(petSet.idle[0])
    }

    private fun startWalk() {
        state = State.WALK
        frameIdx = 0
        lastFrameAt = 0L
        targetX = Random.nextInt(0, maxX + 1)
        targetY = Random.nextInt((80 * density).toInt(), maxY + 1)
    }

    private fun startSleep() {
        state = State.SLEEP
        frameIdx = 0
        lastFrameAt = 0L
        stateUntil = SystemClock.uptimeMillis() + Random.nextLong(7000, 14000)
        resetPose()
        showFrame(petSet.sleep[0])
    }

    private fun pickNext() {
        // Stay mode: never start a new walk, just idle (sleep still happens).
        if (!prefs.getBoolean("wander", true)) {
            toIdle(2000, 5000)
            return
        }
        when (Random.nextInt(100)) {
            in 0..60 -> startWalk()
            in 61..84 -> toIdle(2000, 5000)
            else -> startSleep()
        }
    }

    private fun update() {
        if (dragging) return
        val now = SystemClock.uptimeMillis()
        if (now >= nextScanAt) runScan()
        if (bubbleView?.visibility == View.VISIBLE) {
            if (now >= bubbleUntil) hideBubble() else positionBubble()
        }
        when (state) {
            State.IDLE -> {
                val blinking = now in nextBlinkAt..nextBlinkAt + 220
                showFrame(if (blinking) petSet.idle[1] else petSet.idle[0])
                if (now > nextBlinkAt + 220) nextBlinkAt = now + Random.nextLong(2200, 5200)
                if (now >= stateUntil) pickNext()
            }
            State.WALK -> {
                if (!prefs.getBoolean("wander", true)) {
                    // Toggled mid-walk: stop right away instead of finishing the walk.
                    toIdle(1200, 4000)
                } else {
                    if (now - lastFrameAt > 150) {
                        lastFrameAt = now
                        frameIdx = (frameIdx + 1) % petSet.walk.size
                        showFrame(petSet.walk[frameIdx])
                        if (isCustom) {
                            petView.translationY = -7 * density * (frameIdx % 2)
                            petView.rotation = if (frameIdx % 2 == 0) 7f else -7f
                        }
                    }
                    if (moveTowardTarget()) toIdle(1200, 4000)
                }
            }
            State.SLEEP -> {
                if (now - lastFrameAt > 1500) {
                    lastFrameAt = now
                    frameIdx = (frameIdx + 1) % petSet.sleep.size
                    showFrame(petSet.sleep[frameIdx])
                }
                if (now >= stateUntil) toIdle(1500, 3500)
            }
            State.ALERT -> {
                if (now - lastFrameAt > 450) {
                    lastFrameAt = now
                    frameIdx = (frameIdx + 1) % petSet.alert.size
                    showFrame(petSet.alert[frameIdx])
                }
                if (now >= stateUntil) toIdle(1500, 3500)
            }
        }
    }

    private fun moveTowardTarget(): Boolean {
        val dx = (targetX - petParams.x).toFloat()
        val dy = (targetY - petParams.y).toFloat()
        val dist = hypot(dx.toDouble(), dy.toDouble())
        if (dist <= speedPxPerTick + 1f) {
            petParams.x = targetX
            petParams.y = targetY
            try { wm.updateViewLayout(rootView, petParams) } catch (_: Exception) {}
            return true
        }
        petParams.x = (petParams.x + dx / dist * speedPxPerTick).toInt()
        petParams.y = (petParams.y + dy / dist * speedPxPerTick).toInt()
        petView.scaleX = if (dx >= 0) 1f else -1f
        try { wm.updateViewLayout(rootView, petParams) } catch (_: Exception) {}
        return false
    }

    // ================= touch =================

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var downAt = 0L
    private var moved = false

    private fun onPetTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = petParams.x
                startY = petParams.y
                downAt = SystemClock.uptimeMillis()
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!moved && hypot(dx.toDouble(), dy.toDouble()) > 14 * density) moved = true
                if (moved) {
                    dragging = true
                    petParams.x = (startX + dx).toInt().coerceIn(0, maxX)
                    petParams.y = (startY + dy).toInt().coerceIn(0, maxY)
                    try { wm.updateViewLayout(rootView, petParams) } catch (_: Exception) {}
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    toIdle(800, 2000)
                } else if (SystemClock.uptimeMillis() - downAt < 350) {
                    onTap()
                }
                return true
            }
        }
        return false
    }

    private fun onTap() {
        if (state == State.ALERT) {
            val id = currentAlertId
            val facts = currentVerdictFacts
            val scripted = currentScripted
            if (id != null && facts != null && scripted != null && LlmSettings.isOptedIn(prefs)) {
                if (fetchingAlertId == id) return
                fetchingAlertId = id
                showFrame(petSet.happy)
                showBubble(getString(R.string.ai_thinking), 120000)
                fetchAlertExplanation(id, facts, scripted)
            }
            return
        }
        showChatDialog()
    }

    // ================= chat (feature 3) =================

    // Dialog from a service: needs TYPE_APPLICATION_OVERLAY (overlay permission
    // is already the app's core requirement) or it cannot appear over other apps.
    private fun showChatDialog() {
        showFrame(petSet.happy)
        val historyView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = renderChatHistory()
        }
        val scroll = ScrollView(this).apply { addView(historyView) }
        val input = EditText(this).apply { hint = getString(R.string.chat_hint) }
        val sendBtn = Button(this).apply { text = getString(R.string.chat_send) }
        val micBtn = Button(this).apply { text = getString(R.string.chat_mic) }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) micBtn.visibility = View.GONE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendBtn)
            addView(micBtn)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scroll,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (320 * density).toInt())
            )
            addView(row)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_title))
            .setView(body)
            .setPositiveButton("OK", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        chatHistoryView = historyView
        chatInput = input
        sendBtn.setOnClickListener { sendChat(input.text.toString()) }
        micBtn.setOnClickListener { onMicTap(input, micBtn) }
        dialog.setOnDismissListener {
            stopVoiceRecording(transcribe = false)
            chatHistoryView = null
            chatInput = null
        }
        dialog.show()
    }

    private fun renderChatHistory(): String {
        if (chatHistory.isEmpty()) return getString(R.string.chat_greeting)
        return chatHistory.joinToString("\n") { (user, text) ->
            if (user) getString(R.string.chat_you, text) else getString(R.string.chat_cat, text)
        }
    }

    private fun appendChat(user: Boolean, text: String) {
        chatHistory.add(user to text)
        while (chatHistory.size > 12) chatHistory.removeAt(0)
        chatHistoryView?.text = renderChatHistory()
    }

    private fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        chatInput?.text?.clear()
        appendChat(true, text)
        val system = LlmPrompts.chat(LlmPrompts.langName(prefs, resources))
        val context = chatHistory.joinToString("\n") { (user, t) ->
            if (user) "User: $t" else "Cat: $t"
        }
        Thread {
            val reply = chatDebounced().chat(system, context, temperature = 0.7, maxTokens = 512)
            handler.post {
                appendChat(
                    false,
                    reply ?: getString(
                        listOf(R.string.dlg_tap_1, R.string.dlg_tap_2, R.string.dlg_tap_3).random()
                    )
                )
            }
        }.start()
    }

    // Mic tap toggles Muse Voice recording when opted in; otherwise the
    // system recognizer does one-shot input. Transcribed text only fills
    // the input box, never auto-sends.
    private fun onMicTap(input: EditText, micBtn: Button) {
        if (voiceRecorder != null) {
            stopVoiceRecording(transcribe = true, input = input, micBtn = micBtn)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.chat_mic_no_perm, Toast.LENGTH_SHORT).show()
            return
        }
        val key = if (LlmSettings.isOptedIn(prefs)) LlmSettings.apiKey(prefs) else null
        if (key != null) {
            val rec = MuseVoiceTranscriber.Recorder(File(cacheDir, "voice.wav"))
            if (rec.start()) {
                voiceRecorder = rec
                micBtn.text = getString(R.string.chat_mic_stop)
            } else {
                startSystemVoiceInput(input)
            }
        } else {
            startSystemVoiceInput(input)
        }
    }

    private fun stopVoiceRecording(transcribe: Boolean, input: EditText? = null, micBtn: Button? = null) {
        val rec = voiceRecorder ?: return
        voiceRecorder = null
        micBtn?.text = getString(R.string.chat_mic)
        val wav = rec.stop()
        if (!transcribe || wav == null) {
            wav?.delete()
            return
        }
        val key = LlmSettings.apiKey(prefs) ?: return
        Thread {
            val text = MuseVoiceTranscriber(key).transcribe(wav)
            wav.delete()
            handler.post {
                if (!text.isNullOrBlank()) {
                    input?.setText(text)
                } else {
                    // Muse Voice failed or heard nothing: system recognizer fallback.
                    input?.let { startSystemVoiceInput(it) }
                }
            }
        }.start()
    }

    // System recognizer only: fills the input box, never auto-sends, so the
    // user can edit before sending. No audio file is kept, only the text.
    private fun startSystemVoiceInput(input: EditText) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.chat_mic_no_perm, Toast.LENGTH_SHORT).show()
            return
        }
        val lang = if (LlmPrompts.langName(prefs, resources) == "English") "en-US" else "zh-TW"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!heard.isNullOrBlank()) input.setText(heard)
                    recognizer.destroy()
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                }

                override fun onReadyForSpeech(params: Bundle) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle) {}
                override fun onEvent(eventType: Int, params: Bundle) {}
            })
            recognizer.startListening(intent)
        } catch (_: Exception) {
        }
    }
}
