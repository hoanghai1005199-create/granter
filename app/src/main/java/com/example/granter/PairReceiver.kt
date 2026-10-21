package com.example.granter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import kotlin.concurrent.thread

object Notif {
    private const val CH = "pair"
    private const val ID = 1

    private fun nm(ctx: Context): NotificationManager {
        val m = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(
            NotificationChannel(CH, "Ghép nối ADB", NotificationManager.IMPORTANCE_HIGH))
        return m
    }

    fun askCode(ctx: Context, host: String, port: Int) {
        val ri = RemoteInput.Builder("code").setLabel("Mã ghép nối 6 số").build()
        val intent = Intent(ctx, PairReceiver::class.java)
            .putExtra("host", host).putExtra("port", port)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent, flags)
        val action = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_edit),
            "Nhập mã ghép nối", pi).addRemoteInput(ri).build()
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HH tool: sẵn sàng ghép nối")
            .setContentText("Bấm \"Nhập mã ghép nối\" rồi gõ mã 6 số đang hiện trong Cài đặt")
            .addAction(action)
            .setOnlyAlertOnce(true)
            .build()
        nm(ctx).notify(ID, n)
    }

    fun show(ctx: Context, text: String) {
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HH tool")
            .setContentText(text)
            .build()
        nm(ctx).notify(ID, n)
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)
            ?.getCharSequence("code")?.toString()?.trim() ?: return
        val host = i.getStringExtra("host") ?: return
        val port = i.getIntExtra("port", 0)
        val pending = goAsync()
        thread {
            val ok = AdbBackend.pair(c, host, port, code)
            if (!ok) {
                Notif.show(c, "Ghép nối thất bại: ${AdbBackend.lastError.take(80)}. Thử lại.")
            } else {
                val conn = AdbBackend.ensureConnected(c)
                Notif.show(c, if (conn) "Ghép nối và kết nối thành công ✅"
                else "Đã ghép nối. Mở HH tool và bấm KẾT NỐI ADB")
            }
            pending.finish()
        }
    }
}
