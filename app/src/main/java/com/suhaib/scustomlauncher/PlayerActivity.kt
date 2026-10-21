package com.suhaib.scustomlauncher

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File

// Plays the images and videos of the USB drive in an endless loop (menu boards / ads).
// Video is drawn on a TextureView so it rotates and resizes correctly with the screen.
class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var tex: TextureView
    private lateinit var image: ImageView
    private lateinit var msg: TextView

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingFile: File? = null
    private var pendingGen = 0
    private var vidW = 0
    private var vidH = 0
    private var lastPw = 0
    private var lastPh = 0

    private var list: List<File> = emptyList()
    private var index = 0
    private var gen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        tex = TextureView(this)
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                val f = pendingFile
                if (f != null && pendingGen == gen) {
                    pendingFile = null
                    startMedia(f, pendingGen)
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                try { surface?.release() } catch (e: Exception) { }
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        root.addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        // images and messages are drawn on an opaque black layer above the video
        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setBackgroundColor(Color.BLACK)
        image.visibility = View.GONE
        root.addView(image, FrameLayout.LayoutParams(-1, -1))

        msg = TextView(this)
        msg.setBackgroundColor(Color.BLACK)
        msg.setTextColor(Color.LTGRAY)
        msg.textSize = 22f
        msg.gravity = Gravity.CENTER
        root.addView(msg, FrameLayout.LayoutParams(-1, -1))

        // re-fit the video whenever the screen size changes (rotation)
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w != lastPw || h != lastPh) {
                lastPw = w
                lastPh = h
                fitVideo()
            }
        }
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
        pendingFile = null
        releasePlayer()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Right arrow = next, left arrow = previous
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { next(); return true }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { prev(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // Locked mode: when "USB media player" is the chosen app, Back does nothing.
    // (Opened by "Test USB media" instead, Back leaves the player.)
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

    // Same rule as the launcher: system rotation (if allowed and switched on) decides, otherwise lock this screen
    private fun applyOrientation() {
        if (!prefs.contains("rotation")) return
        val canWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)
        val sys = prefs.getBoolean("sysrot", true) && canWrite
        requestedOrientation = if (sys) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else when (prefs.getInt("rotation", 0)) {
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
        if (!f.exists()) { handler.postDelayed({ if (myGen == gen) next() }, 300); return }
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
                        releasePlayer()
                        pendingFile = null
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
        if (surface == null) {
            pendingFile = f       // starts as soon as the video surface is ready
            pendingGen = myGen
        } else {
            startMedia(f, myGen)
        }
        // safety: a video that never starts is skipped after 30 seconds
        handler.postDelayed({
            if (myGen == gen && !isVideoPlaying()) next()
        }, 30000)
    }

    private fun startMedia(f: File, myGen: Int) {
        releasePlayer()
        vidW = 0
        vidH = 0
        val p = MediaPlayer()
        player = p
        try {
            p.setSurface(surface)
            p.setDataSource(f.absolutePath)
            p.setOnVideoSizeChangedListener { _, w, h ->
                vidW = w
                vidH = h
                fitVideo()
            }
            p.setOnPreparedListener { mp ->
                if (myGen == gen) {
                    if (prefs.getBoolean("mute", false)) mp.setVolume(0f, 0f)
                    mp.start()
                }
            }
            p.setOnCompletionListener { if (myGen == gen) next() }   // plays the FULL video, then the next file
            p.setOnErrorListener { _, _, _ ->
                if (myGen == gen) handler.postDelayed({ if (myGen == gen) next() }, 300)
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            handler.postDelayed({ if (myGen == gen) next() }, 500)
        }
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
    }

    private fun isVideoPlaying(): Boolean = try { player?.isPlaying ?: false } catch (e: Exception) { false }

    // Fits the video inside the screen keeping its shape (works in landscape and portrait)
    private fun fitVideo() {
        if (vidW <= 0 || vidH <= 0 || lastPw <= 0 || lastPh <= 0) return
        val scale = Math.min(lastPw.toFloat() / vidW, lastPh.toFloat() / vidH)
        val lp = FrameLayout.LayoutParams(
            Math.max(1, (vidW * scale).toInt()), Math.max(1, (vidH * scale).toInt()), Gravity.CENTER
        )
        tex.layoutParams = lp
    }

    private fun showMessage(text: String) {
        releasePlayer()
        pendingFile = null
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
