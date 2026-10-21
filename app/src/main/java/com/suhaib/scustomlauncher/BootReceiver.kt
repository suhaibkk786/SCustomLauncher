package com.suhaib.scustomlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Runs when the TV finishes booting: re-arms the refresh timer and starts the launcher
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { Refresh.reschedule(context) } catch (e: Exception) { }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { /* blocked by the system: nothing to do */ }
    }
}
