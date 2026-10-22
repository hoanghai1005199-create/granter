package com.example.granter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val on = c.getSharedPreferences("granter", Context.MODE_PRIVATE)
            .getBoolean("guard_on", false)
        if (!(on && Gate.ok(c) && License.local(c) == License.State.ACTIVE)) return
        if (GuardJob.schedule(c)) {
            // Job chỉ chạy khi khóa Millet đổi, nên ghi một lần ngay sau khi khởi động máy
            val app = c.applicationContext
            val pr = goAsync()
            Thread {
                try { Guard.fire(app) } catch (e: Throwable) {}
                pr.finish()
            }.start()
        } else {
            try { c.startForegroundService(Intent(c, GuardService::class.java)) } catch (e: Throwable) {}
        }
    }
}
