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

/** Chạy nền: khi PowerKeeper ghi đè danh sách Millet hoặc bật màn hình thì ghi lại + đá FCM. */
class GuardService : Service() {

    private val h = Handler(Looper.getMainLooper())
    private val task = Runnable { thread { Guard.fire(applicationContext) } }
    private var obs: ContentObserver? = null
    private var rcv: BroadcastReceiver? = null

    private fun schedule() {
        h.removeCallbacks(task)
        h.postDelayed(task, 500)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val o = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) { schedule() }
        }
        obs = o
        try {
            contentResolver.registerContentObserver(Settings.System.getUriFor(Guard.KEY), false, o)
        } catch (e: Throwable) {}
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { schedule() }
        }
        rcv = r
        val f = IntentFilter(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)
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
        schedule()
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(task)
        try { obs?.let { contentResolver.unregisterContentObserver(it) } } catch (e: Throwable) {}
        try { rcv?.let { unregisterReceiver(it) } } catch (e: Throwable) {}
        super.onDestroy()
    }
}
