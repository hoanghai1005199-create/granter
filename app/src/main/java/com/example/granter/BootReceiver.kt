package com.example.granter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val on = c.getSharedPreferences("granter", Context.MODE_PRIVATE)
            .getBoolean("guard_on", false)
        if (on) {
            try { c.startForegroundService(Intent(c, GuardService::class.java)) } catch (e: Throwable) {}
        }
    }
}
