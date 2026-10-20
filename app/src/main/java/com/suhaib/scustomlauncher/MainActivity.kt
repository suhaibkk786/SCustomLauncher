package com.suhaib.scustomlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Color
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

class MainActivity : Activity() {

    companion object {
        private const val NONE = -1
        private const val SPLASH = 0
        private const val HOME = 1
        private const val ADMIN = 2
        private const val MEDIA = "app.media.player"   // built-in USB image/video player
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var screen = NONE
    private var okPresses = 0

    private val launchRunnable = Runnable { launchTarget() }
    private val resetRunnable = Runnable { okPresses = 0; launchTarget() }

    // ---------- Saved settings ----------
    // Apps shown on the home screen (all other apps are locked)
    private fun allowedSet(): MutableSet<String> {
        val s = prefs.getStringSet("allowed", null)
        if (s != null) return HashSet(s)
        val old = prefs.getString("target", "") ?: ""   // from the earlier single-app version
        return if (old.isNotEmpty()) hashSetOf(old) else HashSet()
    }

    // App that starts by itself at boot ("" = off, show the tile home screen instead)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Runs at TV boot, when Home is pressed, and when a tile app is closed
    override fun onResume() {
        super.onResume()
        applyRotation()
        if (screen == ADMIN) return
        backToMain()
    }

    override fun onPause() {
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (screen == HOME) showHome() // re-flow tiles after a rotation
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == ADMIN && configured()) backToMain()
        // otherwise ignored: users cannot back out of the launcher
    }

    private fun configured(): Boolean = allowedSet().isNotEmpty() || autoStart == MEDIA

    private fun backToMain() {
        when {
            !configured() -> showAdmin()
            autoStart.isNotEmpty() -> showSplash()
            else -> showHome()
        }
    }

    // ---------- Rotation ----------
    private fun canWriteSettings(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)

    // Rotates this launcher AND (once permission is granted) every other app
    private fun applyRotation() {
        if (!prefs.contains("rotation")) return
        requestedOrientation = when (rotation) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        if (canWriteSettings()) {
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
                screen = NONE // when you come back, the launcher rebuilds its screen
                return
            } catch (e: Exception) { }
        }
        toast("Not available on this TV")
    }

    // ---------- Launching apps ----------
    private fun launchApp(pkg: String): Boolean {
        if (pkg == MEDIA) {
            startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return true
    }

    private fun launchTarget() {
        val t = autoStart
        if (t.isEmpty()) { backToMain(); return }
        if (!launchApp(t)) handler.postDelayed(launchRunnable, 5000) // app missing: keep retrying
    }

    // ---------- Splash (black screen, used when an auto-start app is set) ----------
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
        setContentView(box)
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        handler.postDelayed(launchRunnable, 5000)
    }

    // Secret entry on the splash: press ANY remote button (or tap) 5 times quickly
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

    // ---------- Home screen with app tiles ----------
    private fun showHome() {
        screen = HOME
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)

        val d = resources.displayMetrics.density
        val tileW = (220 * d).toInt()
        val tileH = (130 * d).toInt()
        val gap = (10 * d).toInt()
        val pad = (48 * d).toInt()
        val cols = Math.max(1, (resources.displayMetrics.widthPixels - 2 * pad) / (tileW + 2 * gap))

        val grid = GridLayout(this)
        grid.columnCount = cols

        fun addTile(view: View) {
            val lp = GridLayout.LayoutParams()
            lp.width = tileW
            lp.height = tileH
            lp.setMargins(gap, gap, gap, gap)
            grid.addView(view, lp)
        }

        for (ri in homeApps()) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(packageManager).toString()
            val banner: Drawable? = try { ri.activityInfo.loadBanner(packageManager) } catch (e: Exception) { null }
            addTile(makeTile(label, banner, ri.loadIcon(packageManager)) {
                if (!launchApp(pkg)) toast("Cannot open " + label)
            })
        }
        addTile(makeTile("USB Media", null, resources.getDrawable(android.R.drawable.ic_media_play)) { launchApp(MEDIA) })
        addTile(makeTile("Admin", null, resources.getDrawable(android.R.drawable.ic_menu_preferences)) { askPin() })

        val title = TextView(this)
        title.text = "S Custom launcher"
        title.textSize = 24f
        title.setTextColor(Color.WHITE)
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
        root.setPadding(pad, pad / 2, pad, pad / 2)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B1220.toInt(), 0xFF1B2638.toInt())
        )
        root.addView(header)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        grid.getChildAt(0)?.requestFocus()
    }

    // One focusable tile: app banner (or icon + name); grows and gets a white border when selected
    private fun makeTile(label: String, banner: Drawable?, icon: Drawable?, onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = 14 * d
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
            col.addView(iv, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()))
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 15f
            tv.maxLines = 2
            tv.gravity = Gravity.CENTER
            tv.setTextColor(Color.WHITE)
            tv.setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), 0)
            col.addView(tv)
            box.addView(col, FrameLayout.LayoutParams(-1, -1))
        }

        box.setOnFocusChangeListener { v, has ->
            val scale = if (has) 1.1f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            v.translationZ = if (has) 12 * d else 0f
            bg.setStroke(if (has) (3 * d).toInt() else 0, Color.WHITE)
        }
        box.setOnClickListener { onClick() }
        return box
    }

    private fun homeApps(): List<ResolveInfo> {
        val allowed = allowedSet()
        return installedApps().filter { allowed.contains(it.activityInfo.packageName) }
    }

    // ---------- PIN ----------
    private fun askPin() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.hint = "Admin PIN"
        val dialog = AlertDialog.Builder(this)
            .setTitle("Admin")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == pin) showAdmin()
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
                if ((e?.toString() ?: "") == pin) { dialog.dismiss(); showAdmin() }
            }
        })
        dialog.show()
        // safety: if nobody finishes the PIN, go back to the normal screen
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

    // ---------- Admin panel ----------
    private fun showAdmin() {
        screen = ADMIN
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)

        val apps = installedApps()
        val labels = apps.map { it.loadLabel(packageManager).toString() }
        val allowed = allowedSet()
        var auto = autoStart

        val lv = ListView(this)
        lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        for (i in apps.indices) {
            if (allowed.contains(apps[i].activityInfo.packageName)) lv.setItemChecked(i, true)
        }

        fun checkedPkgs(): List<String> {
            val out = mutableListOf<String>()
            for (i in apps.indices) if (lv.isItemChecked(i)) out.add(apps[i].activityInfo.packageName)
            return out
        }
        fun autoLabel(): String {
            val i = apps.indexOfFirst { it.activityInfo.packageName == auto }
            return if (auto == MEDIA) "Media player (USB)" else if (auto.isEmpty() || i < 0) "Off" else labels[i]
        }

        val save = button("Save") {
            val chosen = checkedPkgs()
            if (chosen.isEmpty() && auto != MEDIA) toast("Tick at least one app")
            else {
                prefs.edit().putStringSet("allowed", HashSet(chosen)).apply()
                autoStart = if (chosen.contains(auto) || auto == MEDIA) auto else ""
                toast("Saved")
                if (autoStart.isNotEmpty()) { screen = NONE; launchTarget() } else showHome()
            }
        }
        val cancel = button("Cancel") {
            if (configured()) backToMain() else toast("Tick at least one app and press Save")
        }
        val pinBtn = button("Change PIN") { changePin() }

        val autoBtn = button("") { }
        autoBtn.text = "Auto-start: " + autoLabel()
        autoBtn.setOnClickListener {
            val opts = listOf("", MEDIA) + checkedPkgs()
            val idx = opts.indexOf(auto)
            auto = opts[(idx + 1) % opts.size]
            autoBtn.text = "Auto-start: " + autoLabel()
        }

        val wifiBtn = button("Wi-Fi") {
            openFirst(
                Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        val lanBtn = button("LAN / Ethernet") {
            openFirst(
                Intent("android.settings.ETHERNET_SETTINGS"),
                Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        val settingsBtn = button("All settings") { openFirst(Intent(Settings.ACTION_SETTINGS)) }

        val rotBtn = button("") { }
        rotBtn.text = "Rotation: " + rotation + "°"
        rotBtn.setOnClickListener {
            rotation = (rotation + 90) % 360
            rotBtn.text = "Rotation: " + rotation + "°"
            applyRotation()
            if (!canWriteSettings()) toast("Press 'Allow rotation control' so other apps rotate too")
        }
        val writeBtn = button("Allow rotation control") {
            openFirst(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + packageName)))
        }
        val permBtn = button("Allow auto-start") {
            openFirst(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        val homeBtn = button("Set as default Home") { setAsHome() }

        val secsOptions = listOf(5, 10, 15, 30, 60)
        val imgBtn = button("") { }
        imgBtn.text = "Image time: " + prefs.getInt("img_secs", 10) + "s"
        imgBtn.setOnClickListener {
            val cur = prefs.getInt("img_secs", 10)
            val nxt = secsOptions[(secsOptions.indexOf(cur) + 1) % secsOptions.size]
            prefs.edit().putInt("img_secs", nxt).apply()
            imgBtn.text = "Image time: " + nxt + "s"
        }
        val soundBtn = button("") { }
        soundBtn.text = "Video sound: " + (if (prefs.getBoolean("mute", false)) "Off" else "On")
        soundBtn.setOnClickListener {
            val m = !prefs.getBoolean("mute", false)
            prefs.edit().putBoolean("mute", m).apply()
            soundBtn.text = "Video sound: " + (if (m) "Off" else "On")
        }
        val usbBtn = button("Allow USB access") { requestUsbAccess() }
        val playBtn = button("Play USB media now") { launchApp(MEDIA) }

        val title = TextView(this)
        title.text = "Admin panel – tick the apps to show on the home screen (all others are locked)"
        title.textSize = 20f
        title.setTextColor(Color.WHITE)
        val status = TextView(this)
        status.text = "Network: " + networkStatus()
        status.textSize = 16f
        status.setTextColor(Color.LTGRAY)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(60, 40, 60, 40)
        root.addView(title)
        root.addView(status)
        root.addView(row(save, cancel, pinBtn))
        root.addView(row(wifiBtn, lanBtn, settingsBtn))
        root.addView(row(rotBtn, writeBtn, permBtn))
        root.addView(row(autoBtn, homeBtn))
        root.addView(row(imgBtn, soundBtn, usbBtn, playBtn))
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        save.requestFocus()
    }

    // Lets the player read the USB drive (needs a one-time permission)
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

    private fun row(vararg buttons: Button): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (b in buttons) r.addView(b)
        return r
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

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.layoutParams = LinearLayout.LayoutParams(0, (44 * resources.displayMetrics.density).toInt(), 1f)
        b.setPadding(4, 0, 4, 0)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
