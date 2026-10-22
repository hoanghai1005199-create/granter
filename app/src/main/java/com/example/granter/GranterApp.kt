package com.example.granter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GranterApp : Application() {

    private val h = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val task = Runnable {
        if (!running.compareAndSet(false, true)) return@Runnable
        val ctx = applicationContext
        thread {
            try {
                val on = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE)
                    .getBoolean("guard_on", false)
                if (!on) return@thread
                if (License.refreshIfDue(ctx, 5 * 60_000L) != License.State.ACTIVE) {
                    GuardJob.cancel(ctx)
                    try { ctx.stopService(Intent(ctx, GuardService::class.java)) } catch (e: Throwable) {}
                    return@thread
                }
                if (Guard.canWrite(ctx) && Gate.ok(ctx)) Guard.fire(ctx, true)
            } catch (e: Throwable) {
            } finally {
                running.set(false)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            h.removeCallbacks(task)
            h.postDelayed(task, 800)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Throwable) {}
    }
}
