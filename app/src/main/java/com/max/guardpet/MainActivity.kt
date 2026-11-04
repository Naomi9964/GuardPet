package com.max.guardpet

import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.max.guardpet.llm.LlmSettings
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var blockList: TextView

    companion object {
        private const val REQ_PICK = 2001
        private const val MAX_DIM = 512
        private const val TOL = 110
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("guardpet", MODE_PRIVATE).getString("lang", null)
        if (lang != null && Build.VERSION.SDK_INT < 33) {
            val conf = newBase.resources.configuration
            conf.setLocale(if (lang == "en") Locale.ENGLISH else Locale.TRADITIONAL_CHINESE)
            super.attachBaseContext(newBase.createConfigurationContext(conf))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("guardpet", MODE_PRIVATE)
        LlmSettings.migrateApiKey(prefs)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)
        blockList = findViewById(R.id.block_list)

        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            askNotificationPermissionIfNeeded()
            prefs.edit().putBoolean("autostart", true).apply()
            val i = Intent(this, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            startService(Intent(this, GuardService::class.java).setAction(GuardService.ACTION_STOP))
        }
        findViewById<Button>(R.id.btn_call_screen).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                when {
                    !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                        Toast.makeText(this, R.string.call_screen_unavailable, Toast.LENGTH_SHORT).show()
                    rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ->
                        Toast.makeText(this, R.string.call_screen_on, Toast.LENGTH_SHORT).show()
                    else -> startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                }
            } else {
                Toast.makeText(this, "Android 10+ required", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_notif_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btn_mic).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1002)
            } else {
                Toast.makeText(this, R.string.mic_on, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_pet).setOnClickListener { showPetPicker() }
        findViewById<Button>(R.id.btn_stay).setOnClickListener { toggleStay() }
        findViewById<Button>(R.id.btn_language).setOnClickListener { toggleLanguage() }
        findViewById<Button>(R.id.btn_block_add).setOnClickListener { addBlock() }
        findViewById<Switch>(R.id.switch_ai).setOnCheckedChangeListener { _, on -> onAiSwitchChanged(on) }
        findViewById<Button>(R.id.btn_ai_key_save).setOnClickListener { saveAiKey() }
        findViewById<Button>(R.id.btn_ai_key_clear).setOnClickListener { clearAiKey() }
        refreshBlockList()
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (Settings.canDrawOverlays(this)) getString(R.string.status_ready)
        else getString(R.string.status_no_perm)
        refreshStayButton()
        refreshBlockList()
        refreshAiUi()
        refreshPermissionButtons()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(this, if (granted) R.string.mic_on else R.string.mic_denied, Toast.LENGTH_SHORT).show()
            refreshPermissionButtons()
        }
    }

    // Button labels show the live state, so a granted role or permission is
    // visible without tapping the button to find out.
    private fun refreshPermissionButtons() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            findViewById<Button>(R.id.btn_call_screen).text =
                if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) getString(R.string.btn_call_screen_on)
                else getString(R.string.btn_call_screen)
        }
        findViewById<Button>(R.id.btn_mic).text =
            if (Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) getString(R.string.btn_mic_on)
            else getString(R.string.btn_mic)
    }

    // ---------- stay / wander ----------

    // Button shows the ACTION: wander on -> offer to stay, wander off -> offer to walk.
    private fun refreshStayButton() {
        findViewById<Button>(R.id.btn_stay).text =
            if (prefs.getBoolean("wander", true)) getString(R.string.btn_stay)
            else getString(R.string.btn_wander)
    }

    private fun toggleStay() {
        prefs.edit().putBoolean("wander", !prefs.getBoolean("wander", true)).apply()
        refreshStayButton()
        // GuardService reads the same prefs on its next tick; no intent needed.
    }

    // ---------- language ----------

    // Effective current language: explicit choice first, then system per-app
    // locale (API 33+), then whatever the resources resolved to. The default
    // resources (res/values) are Chinese, so a null pref does NOT mean English.
    private fun currentLang(): String {
        prefs.getString("lang", null)?.let { return it }
        if (Build.VERSION.SDK_INT >= 33) {
            val appLocales = getSystemService(LocaleManager::class.java).applicationLocales
            if (!appLocales.isEmpty) return if (appLocales[0].language == "zh") "zh" else "en"
        }
        return if (resources.configuration.locales[0].language == "zh") "zh" else "en"
    }

    private fun toggleLanguage() {
        val next = if (currentLang() == "zh") "en" else "zh"
        prefs.edit().putString("lang", next).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(next)
        }
        restartPetIfDeployed()
        recreate()
    }

    // The pet's overlay views were inflated with the old strings; bounce the
    // service so they pick up the new language. Only if it was deployed.
    private fun restartPetIfDeployed() {
        if (!GuardService.isRunning) return
        try {
            startService(Intent(this, GuardService::class.java).setAction(GuardService.ACTION_STOP))
            val i = Intent(this, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (_: Exception) {
        }
    }

    // ---------- AI opt-in (hackathon, default OFF) ----------

    // Off-path is idempotent, so programmatic setChecked(false) on dialog cancel
    // safely re-enters here with on=false.
    private fun refreshAiUi() {
        val optedIn = prefs.getBoolean(LlmSettings.KEY_OPT_IN, false)
        val hasKey = !prefs.getString(LlmSettings.KEY_API_KEY, null).isNullOrBlank()
        // A stored key that no longer decrypts must not show as enabled while
        // AI silently falls back to scripted copy.
        val keyOk = !hasKey || LlmSettings.apiKey(prefs) != null
        findViewById<Switch>(R.id.switch_ai).isChecked = optedIn
        findViewById<TextView>(R.id.ai_status).text = when {
            optedIn && hasKey && keyOk -> getString(R.string.ai_status_on)
            optedIn && hasKey -> getString(R.string.ai_status_key_error)
            optedIn -> getString(R.string.ai_status_no_key)
            else -> getString(R.string.ai_status_off)
        }
        findViewById<Button>(R.id.btn_ai_key_clear).isEnabled = hasKey
    }

    private fun onAiSwitchChanged(on: Boolean) {
        if (!on) {
            prefs.edit().putBoolean(LlmSettings.KEY_OPT_IN, false).apply()
            refreshAiUi()
            return
        }
        if (prefs.getBoolean(LlmSettings.KEY_PRIVACY_OK, false)) {
            prefs.edit().putBoolean(LlmSettings.KEY_OPT_IN, true).apply()
            refreshAiUi()
            if (prefs.getString(LlmSettings.KEY_API_KEY, null).isNullOrBlank()) {
                Toast.makeText(this, R.string.ai_need_key, Toast.LENGTH_SHORT).show()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ai_privacy_title))
            .setMessage(getString(R.string.ai_privacy_body))
            .setPositiveButton(getString(R.string.ai_privacy_ok)) { _, _ ->
                prefs.edit()
                    .putBoolean(LlmSettings.KEY_PRIVACY_OK, true)
                    .putBoolean(LlmSettings.KEY_OPT_IN, true)
                    .apply()
                refreshAiUi()
                if (prefs.getString(LlmSettings.KEY_API_KEY, null).isNullOrBlank()) {
                    Toast.makeText(this, R.string.ai_need_key, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.ai_privacy_cancel)) { _, _ ->
                findViewById<Switch>(R.id.switch_ai).isChecked = false
                refreshAiUi()
            }
            .show()
    }

    private fun saveAiKey() {
        val input = findViewById<EditText>(R.id.ai_key_input)
        val key = input.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, R.string.ai_need_key, Toast.LENGTH_SHORT).show()
            return
        }
        val encrypted = LlmSettings.saveApiKey(prefs, key)
        input.text.clear()
        Toast.makeText(this, R.string.ai_key_saved, Toast.LENGTH_SHORT).show()
        if (!encrypted) {
            Toast.makeText(this, R.string.ai_key_saved_plain, Toast.LENGTH_LONG).show()
        }
        refreshAiUi()
    }

    // Clearing the key also turns opt-in off: without a key there is nothing to enable.
    private fun clearAiKey() {
        prefs.edit()
            .remove(LlmSettings.KEY_API_KEY)
            .putBoolean(LlmSettings.KEY_OPT_IN, false)
            .apply()
        Toast.makeText(this, R.string.ai_key_cleared, Toast.LENGTH_SHORT).show()
        refreshAiUi()
    }

    // ---------- pet picker ----------

    private fun showPetPicker() {
        val items = arrayOf(
            getString(R.string.pet_tabby),
            getString(R.string.pet_wolf),
            getString(R.string.pet_photo),
            getString(R.string.pet_photo_clear)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_pet))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setPet("cat")
                    1 -> setPet("wolf")
                    2 -> pickPhoto()
                    3 -> {
                        if (File(filesDir, "pet_custom.png").delete()) {
                            Toast.makeText(this, R.string.toast_back_default, Toast.LENGTH_SHORT).show()
                        }
                        reloadPet()
                    }
                }
            }
            .show()
    }

    private fun setPet(kind: String) {
        prefs.edit().putString("pet", kind).apply()
        reloadPet()
    }

    private fun reloadPet() {
        try {
            startService(Intent(this, GuardService::class.java).setAction(GuardService.ACTION_RELOAD))
        } catch (_: Exception) {
        }
    }

    private fun pickPhoto() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        startActivityForResult(Intent.createChooser(intent, "photo"), REQ_PICK)
    }

    @Deprecated("classic picker without extra deps")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { processPhoto(it) }
        }
    }

    // ---------- blocklist ----------

    private fun addBlock() {
        val input = findViewById<EditText>(R.id.block_number)
        val num = input.text.toString().filter { it.isDigit() }
        if (num.length < 4) return
        val set = (prefs.getStringSet("blocklist", emptySet()) ?: emptySet()).toMutableSet()
        set.add(num)
        prefs.edit().putStringSet("blocklist", set).apply()
        input.text.clear()
        Toast.makeText(this, R.string.block_added, Toast.LENGTH_SHORT).show()
        refreshBlockList()
    }

    private fun refreshBlockList() {
        val set = prefs.getStringSet("blocklist", emptySet()) ?: emptySet()
        blockList.text = if (set.isEmpty()) getString(R.string.block_empty)
        else set.sorted().joinToString("\n")
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    // ---------- photo -> transparent sprite (paper background removal) ----------

    private fun processPhoto(uri: Uri) {
        Toast.makeText(this, R.string.toast_photo_processing, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val bmp = decodeSampled(uri, MAX_DIM) ?: throw Exception("decode failed")
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                floodRemovePaper(px, w, h)
                val trimmed = trimAndSquare(px, w, h) ?: throw Exception("empty after cutout")
                FileOutputStream(File(filesDir, "pet_custom.png")).use {
                    trimmed.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bmp.recycle()
                trimmed.recycle()
                runOnUiThread {
                    Toast.makeText(this, R.string.toast_photo_done, Toast.LENGTH_LONG).show()
                    reloadPet()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_photo_fail, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun decodeSampled(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val m = maxOf(bounds.outWidth, bounds.outHeight)
        while (m / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun paperColor(px: IntArray, w: Int, h: Int): IntArray {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        val s = 8
        val corners = arrayOf(
            intArrayOf(0, 0), intArrayOf(w - s, 0),
            intArrayOf(0, h - s), intArrayOf(w - s, h - s)
        )
        for (c in corners) {
            for (y in 0 until s) for (x in 0 until s) {
                val p = px[(c[1] + y) * w + (c[0] + x)]
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
        }
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun nearPaper(c: Int, paper: IntArray): Boolean {
        val dr = Color.red(c) - paper[0]
        val dg = Color.green(c) - paper[1]
        val db = Color.blue(c) - paper[2]
        return dr * dr + dg * dg + db * db < TOL * TOL
    }

    private fun floodRemovePaper(px: IntArray, w: Int, h: Int) {
        val paper = paperColor(px, w, h)
        val seen = BooleanArray(w * h)
        val q: ArrayDeque<Int> = ArrayDeque()
        fun seed(x: Int, y: Int) {
            val i = y * w + x
            if (!seen[i] && nearPaper(px[i], paper)) {
                seen[i] = true
                q.add(i)
            }
        }
        for (x in 0 until w) {
            seed(x, 0); seed(x, h - 1)
        }
        for (y in 0 until h) {
            seed(0, y); seed(w - 1, y)
        }
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            px[i] = 0
            val x = i % w
            val y = i / w
            if (x > 0) {
                val j = i - 1
                if (!seen[j] && nearPaper(px[j], paper)) {
                    seen[j] = true; q.add(j)
                }
            }
            if (x < w - 1) {
                val j = i + 1
                if (!seen[j] && nearPaper(px[j], paper)) {
                    seen[j] = true; q.add(j)
                }
            }
            if (y > 0) {
                val j = i - w
                if (!seen[j] && nearPaper(px[j], paper)) {
                    seen[j] = true; q.add(j)
                }
            }
            if (y < h - 1) {
                val j = i + w
                if (!seen[j] && nearPaper(px[j], paper)) {
                    seen[j] = true; q.add(j)
                }
            }
        }
    }

    private fun trimAndSquare(px: IntArray, w: Int, h: Int): Bitmap? {
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (px[y * w + x] ushr 24 != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        val side = maxOf(cw, ch)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        out.setPixels(px, minY * w + minX, w, (side - cw) / 2, (side - ch) / 2, cw, ch)
        return out
    }
}
