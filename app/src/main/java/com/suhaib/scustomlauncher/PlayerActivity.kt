package com.suhaib.scustomlauncher

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import java.io.File

// Plays the images and videos of the USB drive in an endless loop (menu boards / ads)
class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private lateinit var video: VideoView
    private lateinit var image: ImageView
    private lateinit var msg: TextView
    private var list: List<File> = emptyList()
    private var index = 0
    private var gen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.visibility = View.GONE
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        msg = TextView(this)
        msg.setTextColor(Color.LTGRAY)
        msg.textSize = 22f
        msg.gravity = Gravity.CENTER
        root.addView(msg, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        hideSystemUi()
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        begin()
    }

    override fun onResume() {
        super.onResume()
        applyOrientation()
        begin()
    }

    override fun onPause() {
        gen++
        handler.removeCallbacksAndMessages(null)
        try { video.stopPlayback() } catch (e: Exception) { }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Right arrow = next, left arrow = previous, Back = leave the player
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { next(); return true }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { prev(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // Locked mode: when "Media player (USB)" is the auto-start app, Back does nothing.
    // (Opened from the home-screen tile instead, Back leaves the player.)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val locked = prefs.getString("autostart", "") == "app.media.player"
        if (!locked) super.onBackPressed()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun applyOrientation() {
        if (!prefs.contains("rotation")) return
        requestedOrientation = when (prefs.getInt("rotation", 0)) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    // (Re)scans the USB drive and starts from the first file.
    // Also runs at the end of every loop, so a new or swapped USB drive is picked up automatically.
    private fun begin() {
        gen++
        handler.removeCallbacksAndMessages(null)
        if (!hasAccess()) {
            showMessage("USB access is not allowed yet.\n\nOpen the launcher Admin panel and press \"Allow USB access\".")
            handler.postDelayed({ begin() }, 5000)
            return
        }
        list = MediaLib.playlist(this)
        if (list.isEmpty()) {
            showMessage("Insert a USB drive with images and videos.\n\nPut the files in a folder named \"signage\" (or on the main folder of the drive).")
            handler.postDelayed({ begin() }, 5000)
            return
        }
        index = 0
        playCurrent()
    }

    private fun next() {
        if (list.isEmpty()) return
        index++
        if (index >= list.size) begin() else playCurrent()
    }

    private fun prev() {
        if (list.isEmpty()) return
        index = if (index <= 0) list.size - 1 else index - 1
        playCurrent()
    }

    private fun playCurrent() {
        handler.removeCallbacksAndMessages(null)
        val myGen = ++gen
        val f = list[index]
        if (!f.exists()) { next(); return }
        if (MediaLib.isVideo(f)) playVideo(f, myGen) else showImage(f, myGen)
    }

    private fun showImage(f: File, myGen: Int) {
        Thread {
            val bmp = decode(f)
            runOnUiThread {
                if (myGen == gen) {
                    if (bmp == null) {
                        next()
                    } else {
                        try { video.stopPlayback() } catch (e: Exception) { }
                        video.visibility = View.GONE
                        msg.visibility = View.GONE
                        image.setImageBitmap(bmp)
                        image.visibility = View.VISIBLE
                        image.alpha = 0f
                        image.animate().alpha(1f).setDuration(400).start()
                        val secs = prefs.getInt("img_secs", 10)
                        handler.postDelayed({ if (myGen == gen) next() }, secs * 1000L)
                    }
                }
            }
        }.start()
    }

    private fun playVideo(f: File, myGen: Int) {
        image.visibility = View.GONE
        msg.visibility = View.GONE
        video.visibility = View.VISIBLE
        video.setOnPreparedListener { mp ->
            if (prefs.getBoolean("mute", false)) mp.setVolume(0f, 0f)
        }
        video.setOnCompletionListener { if (myGen == gen) next() }
        video.setOnErrorListener { _, _, _ ->
            if (myGen == gen) next()
            true
        }
        video.setVideoPath(f.absolutePath)
        video.start()
        // safety: a video that never starts is skipped after 30 seconds
        handler.postDelayed({
            if (myGen == gen && !video.isPlaying && video.currentPosition == 0) next()
        }, 30000)
    }

    private fun showMessage(text: String) {
        try { video.stopPlayback() } catch (e: Exception) { }
        video.visibility = View.GONE
        image.visibility = View.GONE
        msg.text = text
        msg.visibility = View.VISIBLE
    }

    // Loads an image scaled down to the screen size (saves memory on TV boxes)
    private fun decode(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, o)
            val dm = resources.displayMetrics
            var s = 1
            while (o.outWidth / (s * 2) >= dm.widthPixels && o.outHeight / (s * 2) >= dm.heightPixels) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(f.absolutePath, o2)
        } catch (t: Throwable) {
            null
        }
    }
}
