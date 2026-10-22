package com.example.granter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Bật/tắt màn hình thì ghi lại Millet + cloud_memory_freeze_whitelist.
 * Không dịch vụ foreground, không thông báo: receiver sống theo tiến trình app.
 * Khai báo trong Manifest: <application android:name=".GranterApp" ...>
 */
class GranterApp : Application() {

    private val h = Handler(Looper.getMainLooper())
    private val task = Runnable {
        val ctx = applicationContext
        thread {
            try {
                val on = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE)
                    .getBoolean("guard_on", false)
                if (on && Guard.canWrite(ctx) && Gate.ok(ctx) &&
                    License.local(ctx) == License.State.ACTIVE) {
                    Guard.fire(ctx, true)
                }
            } catch (e: Throwable) {}
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Gộp các sự kiện dồn dập (tắt + bật liên tiếp) thành 1 lần chạy
            h.removeCallbacks(task)
            h.postDelayed(task, 800)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        } catch (e: Throwable) {}
    }
}
