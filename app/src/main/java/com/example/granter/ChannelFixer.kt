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
            for (c in list) {
                val ch = c as NotificationChannel
                if (ch.importance == NotificationManager.IMPORTANCE_NONE) {
                    ch.importance = NotificationManager.IMPORTANCE_DEFAULT
                    cls.getMethod("updateNotificationChannelForPackage",
                        String::class.java, i, NotificationChannel::class.java)
                        .invoke(nm, pkg, uid, ch)
                    fixed++
                }
            }
            println("Kênh thông báo: bật lại $fixed/${list.size} kênh đang tắt")
        } catch (t: Throwable) {
            println("Không bật được kênh thông báo: ${(t.cause ?: t).message}")
        }
    }
}
