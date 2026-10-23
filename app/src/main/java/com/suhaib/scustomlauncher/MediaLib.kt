package com.suhaib.scustomlauncher

import android.content.Context
import android.os.Environment
import java.io.File

// Finds the USB drive(s) and builds the list of images/videos to play
object MediaLib {
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val VIDEOS = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi", "ts", "mpg", "mpeg")
    private val FOLDERS = listOf("signage", "Signage", "SIGNAGE", "ads", "Ads", "media", "Media")

    fun isVideo(f: File) = f.extension.lowercase() in VIDEOS

    // Natural file-name order: 1.mp4, 2.jpg, 3.mp4 ... 10.jpg (not 1, 10, 2)
    private val NATURAL = Comparator<File> { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var si = i
                while (si < a.length && a[si].isDigit()) si++
                var sj = j
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = si
                j = sj
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun roots(ctx: Context): List<File> {
        val out = LinkedHashSet<File>()
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self" && f.canRead()) out.add(f)
            }
        } catch (e: Exception) { }
        for (base in listOf("/mnt/usb_storage", "/mnt/usb")) {
            try {
                File(base).listFiles()?.forEach { f -> if (f.isDirectory && f.canRead()) out.add(f) }
            } catch (e: Exception) { }
        }
        try {
            val primary = Environment.getExternalStorageDirectory().absolutePath
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android")
                if (i > 0 && p.substring(0, i) != primary) out.add(File(p.substring(0, i)))
            }
        } catch (e: Exception) { }
        return out.toList()
    }

    // Uses the folder "signage" (or ads/media) on the drive if it exists, otherwise the whole drive
    fun playlist(ctx: Context): List<File> {
        for (root in roots(ctx)) {
            val dir = FOLDERS.map { File(root, it) }.firstOrNull { it.isDirectory } ?: root
            val files = ArrayList<File>()
            collect(dir, 0, files)
            if (files.isNotEmpty()) return files.sortedWith(NATURAL) // 1, 2, 3 ... 10, 11 (numbers in the name count as numbers)
        }
        return emptyList()
    }

    private fun collect(dir: File, depth: Int, out: MutableList<File>) {
        val list = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (f in list) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < 2 && f.name != "Android" && f.name != "LOST.DIR" && f.name != "System Volume Information")
                    collect(f, depth + 1, out)
            } else {
                val e = f.extension.lowercase()
                if (e in IMAGES || e in VIDEOS) out.add(f)
            }
        }
    }
}
