package com.suhaib.scustomlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Alarm for the next refresh time, and clock or time-zone changes
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Refresh.onTick(context)
    }
}
