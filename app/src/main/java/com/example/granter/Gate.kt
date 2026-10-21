package com.example.granter

import android.content.Context
import android.os.Build

/** Điều kiện để HH tool được chạy: không có app xung đột, pin không hạn chế, tự khởi động bật. */
object Gate {
    val CONFLICTS = listOf(
        "com.reed.fcmguard", "com.hanh.gmsprotect", "com.mihome.tools", "com.ttp.notifix",
        "com.kooritea.fcmfix", "com.bronc3.chinaphonefix", "com.fixthongbao.app"
    )

    /** Các gói xung đột đang được cài trên máy. */
    fun installedConflicts(ctx: Context): List<String> = CONFLICTS.filter {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (e: Throwable) { false }
    }

    fun batteryFree(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Throwable) { false }
    }

    /** Tự khởi động kiểu Xiaomi: 1 = bật, 0 = tắt, -1 = máy không đọc được (không chặn). */
    fun autoStart(ctx: Context): Int = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val uid = ctx.packageManager.getApplicationInfo(ctx.packageName, 0).uid
        val m = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        if ((m.invoke(ops, 10008, uid, ctx.packageName) as Int) == 0) 1 else 0
    } catch (e: Throwable) { -1 }

    fun ok(ctx: Context): Boolean =
        installedConflicts(ctx).isEmpty() && batteryFree(ctx) && autoStart(ctx) != 0
}
