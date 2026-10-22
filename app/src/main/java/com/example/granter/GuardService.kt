package com.example.granter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import kotlin.concurrent.thread

/** Chạy nền: chỉ soi danh sách Millet, khi PowerKeeper ghi đè thì ghi lại + đá FCM. Không nghe bật/mở khóa màn hình. */
class GuardService : Service() {

    private val h = Handler(Looper.getMainLooper())
    @Volatile private var logNext = false
    private val task = Runnable {
        val l = logNext; logNext = false
        thread { Guard.fire(applicationContext, l) }
    }
    private val licTask = object : Runnable {
        override fun run() {
            thread {
                if (License.refresh(applicationContext) != License.State.ACTIVE || !Gate.ok(applicationContext)) h.post { stopSelf() }
            }
            h.postDelayed(this, 6 * 60 * 60 * 1000L)
        }
    }
    private var obs: ContentObserver? = null

    private fun schedule() {
        h.removeCallbacks(task)
        h.postDelayed(task, 500)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val o = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) { logNext = true; schedule() }
        }
        obs = o
        try {
            contentResolver.registerContentObserver(Settings.System.getUriFor(Guard.KEY), false, o)
        } catch (e: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("guard", "Giữ Millet + FCM", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "guard")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HH tool: đang giữ Millet + FCM")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7, n)
        }
        if (License.local(this) != License.State.ACTIVE) { stopSelf(); return START_NOT_STICKY }
        if (!Gate.ok(this)) { stopSelf(); return START_NOT_STICKY }
        h.removeCallbacks(licTask)
        h.post(licTask)
        schedule()
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(task)
        h.removeCallbacks(licTask)
        try { obs?.let { contentResolver.unregisterContentObserver(it) } } catch (e: Throwable) {}
        super.onDestroy()
    }
}
