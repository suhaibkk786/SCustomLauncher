package com.suhaib.scustomlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

// Optional "Auto refresh": when switched On, the chosen app is stopped and started again fresh
// every N hours. Off by default.
object Refresh {
    const val ACTION_TICK = "com.suhaib.scustomlauncher.REFRESH_TICK"

    private fun prefs(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("refresh_on", false)
    fun hours(c: Context) = prefs(c).getInt("refresh_hours", 6)

    fun setEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("refresh_on", v).putLong("refresh_last", System.currentTimeMillis()).apply()
    }

    fun setHours(c: Context, h: Int) {
        prefs(c).edit().putInt("refresh_hours", h).putLong("refresh_last", System.currentTimeMillis()).apply()
    }

    private fun tickIntent(c: Context): PendingIntent {
        val i = Intent(c, RefreshReceiver::class.java).setAction(ACTION_TICK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(c, 0, i, flags)
    }

    // Sets (or cancels) the alarm for the next refresh
    fun reschedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = tickIntent(c)
        am.cancel(pi)
        if (!enabled(c)) return
        val now = System.currentTimeMillis()
        var last = prefs(c).getLong("refresh_last", 0L)
        if (last == 0L || last > now) {
            last = now
            prefs(c).edit().putLong("refresh_last", last).apply()
        }
        val at = Math.max(last + hours(c) * 3600000L, now + 2000)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    // Called by the alarm (and when the clock or time zone changes)
    fun onTick(c: Context) {
        if (enabled(c)) {
            val now = System.currentTimeMillis()
            val p = prefs(c)
            val last = p.getLong("refresh_last", 0L)
            if (last == 0L || last > now) {
                p.edit().putLong("refresh_last", now).apply()
            } else if (now >= last + hours(c) * 3600000L - 1500) {
                p.edit().putLong("refresh_last", now).putLong("req_refresh_at", now).apply()
                // bring the launcher to the front; it stops the app and starts it again
                // (starting a screen from the background needs the "Allow auto-start" permission)
                try {
                    c.startActivity(
                        Intent(c, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (e: Exception) { }
            }
        }
        reschedule(c)
    }
}
