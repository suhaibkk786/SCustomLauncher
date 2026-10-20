package com.suhaib.scustomlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var inAdmin = false
    private var okPresses = 0

    private val launchRunnable = Runnable { launchTarget() }
    private val resetRunnable = Runnable { okPresses = 0; launchTarget() }

    // Saved settings: app to run, admin PIN (default 1234), screen rotation (0/90/180/270)
    private var target: String
        get() = prefs.getString("target", "") ?: ""
        set(value) { prefs.edit().putString("target", value).apply() }
    private var pin: String
        get() = prefs.getString("pin", "1234") ?: "1234"
        set(value) { prefs.edit().putString("pin", value).apply() }
    private var rotation: Int
        get() = prefs.getInt("rotation", 0)
        set(value) { prefs.edit().putInt("rotation", value).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Runs at TV boot AND every time the selected app is closed
    override fun onResume() {
        super.onResume()
        applyRotation()
        if (inAdmin) return
        if (target.isEmpty()) showAdmin() else showSplash()
    }

    override fun onPause() {
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inAdmin && target.isNotEmpty()) { inAdmin = false; showSplash() }
        // otherwise ignored: users cannot back out of the launcher
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
                inAdmin = false // when you come back, the launcher restarts the app
                return
            } catch (e: Exception) { }
        }
        toast("Not available on this TV")
    }

    // ---------- Splash (black screen, no hints, no ads) ----------
    private fun showSplash() {
        inAdmin = false
        okPresses = 0
        val tv = TextView(this).apply {
            text = "Starting…"; textSize = 22f
            setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(tv, FrameLayout.LayoutParams(-1, -1))
        })
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)
        handler.postDelayed(launchRunnable, 5000)
    }

    // Secret entry: press OK 5 times quickly while the splash is showing
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inAdmin && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            handler.removeCallbacks(launchRunnable)
            handler.removeCallbacks(resetRunnable)
            okPresses++
            if (okPresses >= 5) { okPresses = 0; askPin() }
            else handler.postDelayed(resetRunnable, 4000)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchTarget() {
        val t = target
        if (t.isEmpty()) { showAdmin(); return }
        val i = packageManager.getLeanbackLaunchIntentForPackage(t)
            ?: packageManager.getLaunchIntentForPackage(t)
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } else {
            handler.postDelayed(launchRunnable, 5000) // app missing: keep retrying
        }
    }

    // ---------- PIN ----------
    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Admin PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Admin")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == pin) showAdmin()
                else { toast("Wrong PIN"); showSplash() }
            }
            .setNegativeButton("Cancel") { _, _ -> showSplash() }
            .setOnCancelListener { showSplash() }
            .show()
    }

    private fun changePin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (4+ digits)"
        }
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
        inAdmin = true
        handler.removeCallbacks(launchRunnable)
        handler.removeCallbacks(resetRunnable)

        val apps = installedApps()
        val labels = apps.map { it.loadLabel(packageManager).toString() }
        val current = target
        val checked = apps.indexOfFirst { it.activityInfo.packageName == current }

        val lv = ListView(this)
        lv.choiceMode = ListView.CHOICE_MODE_SINGLE
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, labels)
        if (checked >= 0) lv.setItemChecked(checked, true)

        val save = button("Save & Start") {
            val pos = lv.checkedItemPosition
            if (pos == ListView.INVALID_POSITION) toast("Select an app first")
            else {
                target = apps[pos].activityInfo.packageName
                toast("Saved: " + labels[pos])
                inAdmin = false
                launchTarget()
            }
        }
        val cancel = button("Cancel") {
            if (target.isNotEmpty()) { inAdmin = false; showSplash() } else toast("Select an app first")
        }
        val pinBtn = button("Change PIN") { changePin() }

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
        val autoBtn = button("Allow auto-start") {
            openFirst(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }

        val title = TextView(this).apply {
            text = "Admin panel – choose the app to run (all others are locked)"
            textSize = 20f; setTextColor(Color.WHITE)
        }
        val status = TextView(this).apply {
            text = "Network: " + networkStatus()
            textSize = 16f; setTextColor(Color.LTGRAY)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(60, 40, 60, 40)
            addView(title)
            addView(status)
            addView(row(save, cancel, pinBtn))
            addView(row(wifiBtn, lanBtn, settingsBtn))
            addView(row(rotBtn, writeBtn, autoBtn))
            addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        save.requestFocus()
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
        b.textSize = 14f
        b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
