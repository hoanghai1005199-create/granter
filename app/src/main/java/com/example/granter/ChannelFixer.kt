package com.example.granter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.IBinder

/** Chạy bằng: CLASSPATH=<apk> app_process /system/bin com.example.granter.ChannelFixer <pkg> <uid> */
object ChannelFixer {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val pkg = args[0]
            val uid = args[1].toInt()
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
            val cls = Class.forName("android.app.INotificationManager")
            val nm = Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val i = Int::class.javaPrimitiveType!!
            val b = Boolean::class.javaPrimitiveType!!
            try {
                cls.getMethod("setNotificationsEnabledForPackage", String::class.java, i, b)
                    .invoke(nm, pkg, uid, true)
            } catch (ignored: Throwable) {}
            val slice = cls.getMethod("getNotificationChannelsForPackage", String::class.java, i, b)
                .invoke(nm, pkg, uid, false)
            val list = slice!!.javaClass.getMethod("getList").invoke(slice) as List<*>
            var fixed = 0
            val defSound = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            for (c in list) {
                val ch = c as NotificationChannel
                var changed = false
                // Thông báo nổi (heads-up) = IMPORTANCE_HIGH
                if (ch.importance != NotificationManager.IMPORTANCE_HIGH) {
                    ch.importance = NotificationManager.IMPORTANCE_HIGH; changed = true
                }
                // Âm thanh: nếu đang None thì đặt âm thông báo mặc định
                if (ch.sound == null) { ch.setSound(defSound, attrs); changed = true }
                // Rung
                if (!ch.shouldVibrate()) { ch.enableVibration(true); changed = true }
                // Màn hình khóa: hiện đầy đủ
                if (ch.lockscreenVisibility != android.app.Notification.VISIBILITY_PUBLIC) {
                    ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC; changed = true
                }
                if (!ch.canShowBadge()) { ch.setShowBadge(true); changed = true }
                if (changed) {
                    try {
                        cls.getMethod("updateNotificationChannelForPackage",
                            String::class.java, i, NotificationChannel::class.java)
                            .invoke(nm, pkg, uid, ch)
                        fixed++
                    } catch (ignored: Throwable) {}
                }
            }
            println("Kênh thông báo: đã bật full (nổi, âm thanh, rung, màn hình khóa) $fixed/${list.size} kênh")
        } catch (t: Throwable) {
            println("Không bật được kênh thông báo: ${(t.cause ?: t).message}")
        }
    }
}
