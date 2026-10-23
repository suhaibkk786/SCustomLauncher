package com.suhaib.scustomlauncher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

// Kiosk launcher: runs ONE app (or the built-in USB media player) always.
class MainActivity : Activity() {

    companion object {
        private const val NONE = -1
        private const val SPLASH = 0
        private const val HOME = 1
        private const val ADMIN = 2
        private const val MEDIA = "app.media.player"   // built-in USB image/video player
        // Master (bypass) PIN for a forgotten admin PIN, stored only as a SHA-256 hash
        private const val MASTER_HASH = "3e8b5ed63c7578320265c4da129a43279f451cf44017533187d68cf1e15d3301"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var screen = NONE
    private var okPresses = 0
    private var pendingSel: String? = null   // app chosen in the admin panel but not saved yet
    private var refocusRot = false           // keep focus on the Rotation card after the screen turns
    private var clearTaskNext = false        // next app start (after a refresh) opens the app from scratch

    private val launchRunnable = Runnable { launchTarget() }
    private val resetRunnable = Runnable { okPresses = 0; launchTarget() }

    // ---------- Saved settings ----------
    // The ONE app that runs always ("" = nothing chosen yet; MEDIA = USB player)
    private var autoStart: String
        get() = if (prefs.contains("autostart")) (prefs.getString("autostart", "") ?: "")
                else (prefs.getString("target", "") ?: "")
        set(value) { prefs.edit().putString("autostart", value).apply() }

    private var pin: String
        get() = prefs.getString("pin", "1234") ?: "1234"
        set(value) { prefs.edit().putString("pin", value).apply() }

    private var rotation: Int
        get() = prefs.getInt("rotation", 0)
        set(value) { prefs.edit().putInt("rotation", value).apply() }

    // ---------- Lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        try { Refresh.reschedule(this) } catch (e: Exception) { }
    }

    // Runs at TV boot, when Home is pressed, and every time the selected app is closed
    override fun onResume() {
        super.onResume()
        applyRotation()
        if (screen == ADMIN) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("req_refresh_at", 0L) < 20000L) {  // the Auto refresh timer fired
            prefs.edit().remove("req_refresh_at").apply()
            startRefresh()
        }
        backToMain()
    }

    override fun onPause() {
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        super.onPause()
    }

    // The screen turned (rotation): rebuild the admin panel so it fits the new width/height
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screen == ADMIN) showAdmin() else if (screen == HOME) showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == ADMIN && (homeMode() || autoStart.isNotEmpty())) backToMain()
        // otherwise ignored: users cannot back out of the launcher
    }

    private fun backToMain() {
        pendingSel = null
        if (homeMode()) showHome()
        else if (autoStart.isEmpty()) showAdmin()
        else showSplash()
    }

    // ---------- Rotation ----------
    private fun canWriteSettings(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)

    // System-wide rotation is used only if the permission is granted AND the "Rotate other apps" switch is On
    private fun systemRotation(): Boolean = prefs.getBoolean("sysrot", true) && canWriteSettings()

    // Rotates this launcher and the USB player, and (if systemRotation) every other app too
    private fun applyRotation() {
        if (!prefs.contains("rotation")) return
        val sys = systemRotation()
        requestedOrientation = if (sys) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED // follow the system rotation set below
        } else when (rotation) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (sys) {
            try {
                Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(contentResolver, Settings.System.USER_ROTATION, rotation / 90)
            } catch (e: Exception) { /* firmware blocked it */ }
        }
    }

    // ---------- Network info ----------
    private fun networkStatus(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val ni = try { cm.activeNetworkInfo } catch (e: Exception) { null }
        @Suppress("DEPRECATION")
        val kind = when {
            ni == null || !ni.isConnected -> "Not connected"
            ni.type == ConnectivityManager.TYPE_WIFI -> "Wi-Fi connected"
            ni.type == ConnectivityManager.TYPE_ETHERNET -> "LAN (Ethernet) connected"
            else -> "Connected"
        }
        val ip = localIp()
        return if (ip.isEmpty()) kind else kind + "   IP: " + ip
    }

    private fun localIp(): String {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: ""
                }
            }
        } catch (e: Exception) { }
        return ""
    }

    // Opens the first settings screen this TV supports (firmware differs between brands)
    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                screen = NONE // when you come back, the launcher restarts the app
                return
            } catch (e: Exception) { }
        }
        toast("Not available on this TV")
    }

    // ---------- Launching ----------
    private fun launchApp(pkg: String): Boolean {
        if (pkg == MEDIA) {
            startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (clearTaskNext) {                       // after a refresh: open the app from scratch
            clearTaskNext = false
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(i)
        return true
    }

    private fun launchTarget() {
        if (homeMode()) { showHome(); return }
        val t = autoStart
        if (t.isEmpty()) { showAdmin(); return }
        if (!launchApp(t)) handler.postDelayed(launchRunnable, 5000) // app missing: keep retrying
    }

    // ---------- Splash (black screen, no hints, no ads) ----------
    private fun showSplash() {
        screen = SPLASH
        okPresses = 0
        val tv = TextView(this)
        tv.text = "Starting…"
        tv.textSize = 22f
        tv.setTextColor(Color.DKGRAY)
        tv.gravity = Gravity.CENTER
        val box = FrameLayout(this)
        box.setBackgroundColor(Color.BLACK)
        box.addView(tv, FrameLayout.LayoutParams(-1, -1))
        val credit = TextView(this)
        credit.text = "Developed by Suhaib Hameed"
        credit.textSize = 12f
        credit.setTextColor(0xFF3A3A3A.toInt())
        credit.gravity = Gravity.CENTER
        credit.setPadding(0, 0, 0, dp(16))
        box.addView(credit, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(box)
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        handler.postDelayed(launchRunnable, 5000)
    }

    // Secret entry: press ANY remote button (or tap/click) 5 times quickly on the splash
    private fun registerAdminPress() {
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        okPresses++
        if (okPresses >= 5) { okPresses = 0; askPin() }
        else handler.postDelayed(resetRunnable, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ignored = keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (screen == SPLASH && !ignored) {
            if (event.repeatCount == 0) registerAdminPress()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (screen == SPLASH && event.action == MotionEvent.ACTION_DOWN) {
            registerAdminPress()
            return true
        }
        return super.onTouchEvent(event)
    }

    // ---------- PIN ----------
    private fun sha256(v: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(v.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // The admin PIN OR the master bypass PIN opens the admin panel
    private fun pinOk(v: String): Boolean = v == pin || (v.isNotEmpty() && sha256(v) == MASTER_HASH)

    private fun askPin() {
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Admin PIN"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Admin")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (pinOk(input.text.toString())) showAdmin()
                else { toast("Wrong PIN"); backToMain() }
            }
            .setNegativeButton("Cancel") { _, _ -> backToMain() }
            .setOnCancelListener { backToMain() }
            .create()
        // Opens by itself as soon as the right PIN is typed, so no OK button is needed
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(c: CharSequence?, st: Int, ct: Int, af: Int) {}
            override fun onTextChanged(c: CharSequence?, st: Int, bf: Int, ct: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (pinOk(e?.toString() ?: "")) { dialog.dismiss(); showAdmin() }
            }
        })
        dialog.show()
        // safety: if nobody finishes the PIN, go back to running the app
        handler.postDelayed({
            if (dialog.isShowing) { dialog.dismiss(); backToMain() }
        }, 30000)
    }

    private fun changePin() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "New PIN (4+ digits)"
        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString()
                if (v.length >= 4) { pin = v; toast("PIN changed") } else toast("PIN too short")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Admin panel (modern card UI) ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class Card(val view: View, val sub: TextView)

    // A settings card: coloured accent bar, title + subtitle; grows with a white border when selected
    private fun makeCard(titleText: String, subText: String, accent: Int, onClick: () -> Unit): Card {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(0xFF1E293B.toInt())

        val box = FrameLayout(this)
        box.background = bg
        box.isFocusable = true
        box.isClickable = true

        val strip = View(this)
        strip.setBackgroundColor(accent)
        val slp = FrameLayout.LayoutParams(dp(5), -1)
        slp.setMargins(0, dp(14), 0, dp(14))
        box.addView(strip, slp)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_VERTICAL
        col.setPadding(dp(22), dp(10), dp(14), dp(10))
        val t = TextView(this)
        t.text = titleText
        t.textSize = 16f
        t.setTextColor(Color.WHITE)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val s = TextView(this)
        s.text = subText
        s.textSize = 13f
        s.setTextColor(0xFF94A3B8.toInt())
        s.maxLines = 2
        col.addView(t)
        col.addView(s)
        box.addView(col, FrameLayout.LayoutParams(-1, -1))

        box.setOnFocusChangeListener { v, has ->
            val sc = if (has) 1.05f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            v.translationZ = if (has) dp(10).toFloat() else 0f
            bg.setStroke(if (has) dp(3) else 0, Color.WHITE)
        }
        box.setOnClickListener { onClick() }
        return Card(box, s)
    }

    // Rounded button (Save / Cancel)
    private fun makePill(text: String, fill: Int, onClick: () -> Unit): View {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(28).toFloat()
        bg.setColor(fill)
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(Color.WHITE)
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(28), dp(12), dp(28), dp(12))
        val box = FrameLayout(this)
        box.background = bg
        box.isFocusable = true
        box.isClickable = true
        box.addView(tv)
        box.setOnFocusChangeListener { v, has ->
            val sc = if (has) 1.08f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            v.translationZ = if (has) dp(10).toFloat() else 0f
            bg.setStroke(if (has) dp(3) else 0, Color.WHITE)
        }
        box.setOnClickListener { onClick() }
        return box
    }

    // An app tile in the "run this app always" picker (blue border + tick = chosen)
    private inner class AppTile(val value: String, val label: String, icon: Drawable?, onPick: (AppTile) -> Unit) {
        val view = FrameLayout(this@MainActivity)
        private val bg = GradientDrawable()
        private val badge = TextView(this@MainActivity)
        private var focused = false
        private var chosen = false

        init {
            bg.cornerRadius = dp(14).toFloat()
            view.background = bg
            view.isFocusable = true
            view.isClickable = true

            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val iv = ImageView(this@MainActivity)
            iv.setImageDrawable(icon)
            col.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
            val tv = TextView(this@MainActivity)
            tv.text = label
            tv.textSize = 14f
            tv.maxLines = 2
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            tv.setPadding(dp(8), dp(8), dp(8), 0)
            col.addView(tv)
            view.addView(col, FrameLayout.LayoutParams(-1, -1))

            badge.text = "✓"
            badge.textSize = 18f
            badge.setTextColor(Color.WHITE)
            badge.setPadding(dp(8), dp(4), dp(8), dp(4))
            badge.visibility = View.GONE
            val bl = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            bl.setMargins(0, dp(6), dp(6), 0)
            view.addView(badge, bl)

            view.setOnFocusChangeListener { v, has ->
                focused = has
                val sc = if (has) 1.06f else 1f
                v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
                v.translationZ = if (has) dp(10).toFloat() else 0f
                restyle()
            }
            view.setOnClickListener { onPick(this) }
            restyle()
        }

        fun setChosen(v: Boolean) {
            chosen = v
            badge.visibility = if (v) View.VISIBLE else View.GONE
            restyle()
        }

        private fun restyle() {
            bg.setColor(if (chosen) 0xFF1D3A6B.toInt() else 0xFF1E293B.toInt())
            if (focused) bg.setStroke(dp(3), Color.WHITE)
            else if (chosen) bg.setStroke(dp(3), 0xFF3B82F6.toInt())
            else bg.setStroke(0, 0)
        }
    }

    // ---------- Launcher mode: Kiosk (one app always) or Home launcher (tiles for all apps) ----------
    private fun homeMode(): Boolean = prefs.getString("mode", "kiosk") == "home"

    private fun modeText(): String =
        if (homeMode()) "Home launcher  –  shows all apps  –  press OK to switch to Kiosk"
        else "Kiosk  –  runs ONE app always  –  press OK to switch to Home launcher"

    private fun showHome() {
        screen = HOME
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)

        val avail = resources.displayMetrics.widthPixels - 2 * dp(48)
        val cols = Math.max(1, avail / (dp(190) + dp(16)))
        val tileW = avail / cols - dp(16)

        val grid = GridLayout(this)
        grid.columnCount = cols
        fun addTile(v: View) {
            val lp = GridLayout.LayoutParams()
            lp.width = tileW
            lp.height = dp(130)
            lp.setMargins(dp(8), dp(8), dp(8), dp(8))
            grid.addView(v, lp)
        }

        addTile(makeTile("USB Media Player", null, resources.getDrawable(android.R.drawable.ic_media_play)) { launchApp(MEDIA) })
        for (ri in installedApps()) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(packageManager).toString()
            val banner: Drawable? = try { ri.activityInfo.loadBanner(packageManager) } catch (e: Exception) { null }
            addTile(makeTile(label, banner, ri.loadIcon(packageManager)) {
                if (!launchApp(pkg)) toast("Cannot open " + label)
            })
        }
        addTile(makeTile("Admin", null, resources.getDrawable(android.R.drawable.ic_menu_preferences)) { askPin() })

        val title = TextView(this)
        title.text = "S Launcher"
        title.textSize = 26f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val clock = TextClock(this)
        clock.format24Hour = "HH:mm   EEE, d MMM"
        clock.format12Hour = "hh:mm a   EEE, d MMM"
        clock.textSize = 20f
        clock.setTextColor(Color.LTGRAY)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(clock)

        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.addView(grid)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(48), dp(24), dp(48), dp(24))
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1220.toInt(), 0xFF1B2638.toInt())
        )
        root.addView(header)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        grid.getChildAt(0)?.requestFocus()
    }

    // One focusable home tile: app banner (or icon + name); grows with a white border when selected
    private fun makeTile(label: String, banner: Drawable?, icon: Drawable?, onClick: () -> Unit): View {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(0xFF243247.toInt())

        val box = FrameLayout(this)
        box.background = bg
        box.isFocusable = true
        box.isClickable = true

        if (banner != null) {
            val iv = ImageView(this)
            iv.setImageDrawable(banner)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        } else {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER
            val iv = ImageView(this)
            iv.setImageDrawable(icon)
            col.addView(iv, LinearLayout.LayoutParams(dp(56), dp(56)))
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 15f
            tv.maxLines = 2
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            tv.setPadding(dp(8), dp(6), dp(8), 0)
            col.addView(tv)
            box.addView(col, FrameLayout.LayoutParams(-1, -1))
        }

        box.setOnFocusChangeListener { v, has ->
            val sc = if (has) 1.08f else 1f
            v.animate().scaleX(sc).scaleY(sc).setDuration(120).start()
            v.translationZ = if (has) dp(12).toFloat() else 0f
            bg.setStroke(if (has) dp(3) else 0, Color.WHITE)
        }
        box.setOnClickListener { onClick() }
        return box
    }

    // Refresh: stop the app (twice, because Android only stops apps that are already in the background)
    // and start it again from scratch
    private fun startRefresh() {
        if (homeMode() || autoStart.isEmpty() || autoStart == MEDIA) return
        clearTaskNext = true
        killTarget()
        handler.postDelayed({ killTarget() }, 2000L)
    }

    // ---------- Auto refresh (optional) ----------
    private fun refreshText(): String =
        if (Refresh.enabled(this)) "On  –  press OK to turn off" else "Off  –  press OK to turn on"

    private fun refreshHoursText(): String =
        "Every " + Refresh.hours(this) + " hours  –  press OK to change"

    // Stops the chosen app so it starts again fresh. The USB player simply restarts.
    private fun killTarget() {
        val t = autoStart
        if (t.isEmpty() || t == MEDIA) return
        try {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(t)
        } catch (e: Exception) { }
    }

    private fun askRefreshHours(card: Card) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(Refresh.hours(this).toString())
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle("Refresh the app every how many hours?")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < 1) toast("Enter 1 or more hours")
                else {
                    Refresh.setHours(this, Math.min(v, 720))
                    Refresh.reschedule(this)
                    card.sub.text = refreshHoursText()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sysText(): String =
        if (prefs.getBoolean("sysrot", true)) "On  –  the whole TV rotates (needs permission)"
        else "Off  –  only the launcher and USB player rotate"

    private fun sectionLabel(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.letterSpacing = 0.12f
        tv.setTextColor(0xFF64748B.toInt())
        tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tv.setPadding(dp(8), dp(24), 0, dp(4))
        return tv
    }

    private fun showAdmin() {
        screen = ADMIN
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)

        // ----- app picker: ONE choice (the USB player or an installed app) -----
        val apps = installedApps()
        var selected = pendingSel ?: autoStart
        var selectedLabel = ""
        val tiles = mutableListOf<AppTile>()
        val pick: (AppTile) -> Unit = { t ->
            selected = t.value
            selectedLabel = t.label
            pendingSel = t.value
            for (x in tiles) x.setChosen(x.value == selected)
        }
        tiles.add(AppTile(MEDIA, "USB Media Player", resources.getDrawable(android.R.drawable.ic_media_play), pick))
        for (ri in apps) {
            tiles.add(AppTile(ri.activityInfo.packageName, ri.loadLabel(packageManager).toString(), ri.loadIcon(packageManager), pick))
        }
        for (t in tiles) t.setChosen(t.value == selected)
        selectedLabel = tiles.firstOrNull { it.value == selected }?.label ?: ""

        // ----- layout sizes -----
        val avail = resources.displayMetrics.widthPixels - 2 * dp(48)
        val tileCols = Math.max(1, avail / (dp(160) + dp(16)))
        val cardCols = Math.max(1, avail / (dp(250) + dp(16)))
        val tileW = avail / tileCols - dp(16)
        val cardW = avail / cardCols - dp(16)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(48), dp(32), dp(48), dp(40))

        fun addTo(grid: GridLayout, v: View, w: Int, h: Int) {
            val lp = GridLayout.LayoutParams()
            lp.width = w
            lp.height = h
            lp.setMargins(dp(8), dp(8), dp(8), dp(8))
            grid.addView(v, lp)
        }
        fun section(label: String, vararg cards: Card) {
            content.addView(sectionLabel(label))
            val g = GridLayout(this)
            g.columnCount = cardCols
            for (c in cards) addTo(g, c.view, cardW, dp(78))
            content.addView(g)
        }

        // ----- header: title, network, Save / Cancel -----
        val titleTv = TextView(this)
        titleTv.text = "Admin panel"
        titleTv.textSize = 30f
        titleTv.setTextColor(Color.WHITE)
        titleTv.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val subTv = TextView(this)
        subTv.text = "Network: " + networkStatus()
        subTv.textSize = 14f
        subTv.setTextColor(0xFF94A3B8.toInt())
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(titleTv)
        left.addView(subTv)

        val save = makePill("Save & Start", 0xFF2563EB.toInt()) {
            if (selected.isEmpty()) {
                if (homeMode()) backToMain() else toast("Select the app to run first")
            } else {
                autoStart = selected
                pendingSel = null
                toast("Saved: " + selectedLabel)
                screen = NONE
                if (homeMode()) showHome() else launchTarget()
            }
        }
        val cancel = makePill("Cancel", 0xFF475569.toInt()) {
            if (homeMode() || autoStart.isNotEmpty()) backToMain() else toast("Select an app and press Save & Start")
        }
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val clp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        clp.rightMargin = dp(16)
        header.addView(cancel, clp)
        header.addView(save)
        content.addView(header)

        val hint = TextView(this)
        hint.text = "Kiosk mode: choose the ONE app that runs always (all other apps are locked). Home launcher mode: the list below is not used."
        hint.textSize = 14f
        hint.setTextColor(0xFF94A3B8.toInt())
        hint.setPadding(dp(8), dp(16), 0, 0)
        content.addView(hint)

        val modeCard = makeCard("Launcher mode", modeText(), 0xFF3B82F6.toInt()) { }
        modeCard.view.setOnClickListener {
            prefs.edit().putString("mode", if (homeMode()) "kiosk" else "home").apply()
            modeCard.sub.text = modeText()
            toast("Press Cancel or Save & Start to apply")
        }
        section("MODE", modeCard)

        content.addView(sectionLabel("RUN THIS APP ALWAYS"))
        val appGrid = GridLayout(this)
        appGrid.columnCount = tileCols
        for (t in tiles) addTo(appGrid, t.view, tileW, dp(120))
        content.addView(appGrid)

        // ----- settings cards -----
        val blue = 0xFF3B82F6.toInt()
        val purple = 0xFF8B5CF6.toInt()
        val green = 0xFF22C55E.toInt()
        val amber = 0xFFF59E0B.toInt()

        val wifi = makeCard("Wi-Fi", "Open Wi-Fi settings", blue) {
            openFirst(
                Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        val lan = makeCard("LAN / Ethernet", "Open Ethernet settings", blue) {
            openFirst(
                Intent("android.settings.ETHERNET_SETTINGS"),
                Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        val allSettings = makeCard("All settings", "Open the TV's full settings", blue) {
            openFirst(Intent(Settings.ACTION_SETTINGS))
        }
        section("NETWORK", wifi, lan, allSettings)

        val rot = makeCard("Rotation", rotation.toString() + "°  –  press OK to rotate", purple) { }
        rot.view.setOnClickListener {
            rotation = (rotation + 90) % 360
            refocusRot = true
            handler.postDelayed({ refocusRot = false }, 2000)
            rot.sub.text = rotation.toString() + "°  –  press OK to rotate"
            applyRotation()
            if (!canWriteSettings()) toast("Press 'Allow rotation control' so other apps rotate too")
        }
        val sysCard = makeCard("Rotate other apps", sysText(), purple) { }
        sysCard.view.setOnClickListener {
            val on = !prefs.getBoolean("sysrot", true)
            prefs.edit().putBoolean("sysrot", on).apply()
            if (!on && canWriteSettings()) {
                // switched off: give the other apps their normal orientation back
                try { Settings.System.putInt(contentResolver, Settings.System.USER_ROTATION, 0) } catch (e: Exception) { }
            }
            sysCard.sub.text = sysText()
            applyRotation()
        }
        val rotPerm = makeCard("Allow rotation control", "One-time permission so other apps rotate too", purple) {
            openFirst(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + packageName)))
        }
        val homeCard = makeCard("Set as default Home", "Make this the TV's home app", purple) { setAsHome() }
        val bootPerm = makeCard("Allow auto-start", "Start at boot if Home can't be changed", purple) {
            openFirst(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        section("SCREEN & STARTUP", rot, sysCard, rotPerm, homeCard, bootPerm)

        val img = makeCard("Image time", prefs.getInt("img_secs", 10).toString() + " seconds  –  press OK to change", green) { }
        img.view.setOnClickListener {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_NUMBER
            input.setText(prefs.getInt("img_secs", 10).toString())
            input.setSelection(input.text.length)
            AlertDialog.Builder(this)
                .setTitle("Seconds each image is shown")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val v = input.text.toString().toIntOrNull()
                    if (v == null || v < 1) toast("Enter 1 or more seconds")
                    else {
                        val secs = Math.min(v, 3600)
                        prefs.edit().putInt("img_secs", secs).apply()
                        img.sub.text = secs.toString() + " seconds  –  press OK to change"
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val sound = makeCard("Video sound", if (prefs.getBoolean("mute", false)) "Off" else "On", green) { }
        sound.view.setOnClickListener {
            val m = !prefs.getBoolean("mute", false)
            prefs.edit().putBoolean("mute", m).apply()
            sound.sub.text = if (m) "Off" else "On"
        }
        val usbPerm = makeCard("Allow USB access", "One-time permission to read the USB drive", green) { requestUsbAccess() }
        val test = makeCard("Test USB media", "Play the USB drive now", green) { launchApp(MEDIA) }
        section("USB MEDIA PLAYER", img, sound, usbPerm, test)

        val cyan = 0xFF06B6D4.toInt()
        val refOn = makeCard("Auto refresh", refreshText(), cyan) { }
        refOn.view.setOnClickListener {
            Refresh.setEnabled(this, !Refresh.enabled(this))
            Refresh.reschedule(this)
            refOn.sub.text = refreshText()
            if (Refresh.enabled(this) && Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                toast("Press 'Allow auto-start' so the refresh can run in the background")
            }
        }
        val refHours = makeCard("Refresh every", refreshHoursText(), cyan) { }
        refHours.view.setOnClickListener { askRefreshHours(refHours) }
        val refNow = makeCard("Refresh now (test)", "Stops the app and starts it again right now", cyan) { }
        refNow.view.setOnClickListener {
            if (homeMode()) toast("Refresh works in Kiosk mode")
            else if (autoStart.isEmpty()) toast("Select an app and press Save & Start first")
            else {
                startRefresh()
                screen = NONE
                backToMain()
            }
        }
        section("REFRESH APP", refOn, refHours, refNow)
        val refHint = TextView(this)
        refHint.text = "When On, the chosen app is stopped and started again fresh after the hours you set (the USB player restarts). Off by default. Needs the \"Allow auto-start\" permission above."
        refHint.textSize = 13f
        refHint.setTextColor(0xFF64748B.toInt())
        refHint.setPadding(dp(8), dp(8), dp(8), 0)
        content.addView(refHint)

        val pinCard = makeCard("Change PIN", "Set the admin PIN", amber) { changePin() }
        section("SECURITY", pinCard)

        val footer = TextView(this)
        footer.text = "S Launcher   •   Developed by Suhaib Hameed"
        footer.textSize = 13f
        footer.setTextColor(0xFF64748B.toInt())
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(32), 0, 0)
        content.addView(footer)

        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1220.toInt(), 0xFF111C30.toInt())
        )
        sv.addView(content)
        setContentView(sv)
        if (refocusRot) { refocusRot = false; rot.view.requestFocus() } else save.requestFocus()
    }

    // Lets the USB player read the USB drive (needs a one-time permission)
    private fun requestUsbAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) { toast("USB access already allowed"); return }
            openFirst(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName)),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        } else {
            toast("Not needed on this TV")
        }
    }

    // Makes this app the TV's Home screen (so it behaves as a real launcher)
    private fun setAsHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (rm.isRoleHeld(RoleManager.ROLE_HOME)) { toast("Already the default Home"); return }
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
                    return
                }
            }
        } catch (e: Exception) { }
        openFirst(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    }

    private fun installedApps(): List<ResolveInfo> {
        val list = mutableListOf<ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            list += packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
        }
        return list.distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
