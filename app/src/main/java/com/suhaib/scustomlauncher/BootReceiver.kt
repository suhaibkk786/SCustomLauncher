package com.suhaib.scustomlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Fallback for TVs that do not let you change the default Home app:
// starts the launcher (and so the selected app) when the TV finishes booting.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { /* blocked by the system: nothing to do */ }
    }
}
