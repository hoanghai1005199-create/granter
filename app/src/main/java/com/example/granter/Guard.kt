package com.example.granter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Giữ Millet no-restrict + đá FCM (giống app SetEdit cũ). */
object Guard {
    const val KEY = "MILLET_NO_RESTRICT_APP"
    private const val GMS = "com.google.android.gms"

    val DEFAULT = listOf(
        GMS, "com.google.android.gms.persistent", "com.google.android.gsf",
        "com.google.android.location", "com.android.location.fused",
        "com.google.android.projection.gearhead", "com.xiaomi.barrage"
    )

    /** Quyền cấp qua ADB/Shizuku (giữ nguyên sau khi tắt gỡ lỗi USB). */
    fun hasSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                PackageManager.PERMISSION_GRANTED

    /** Quyền "Sửa đổi cài đặt hệ thống" người dùng tự bật trong Cài đặt, không cần ADB. */
    fun hasWriteSettings(ctx: Context): Boolean =
        try { Settings.System.canWrite(ctx) } catch (e: Throwable) { false }

    fun canWrite(ctx: Context): Boolean = hasSecure(ctx) || hasWriteSettings(ctx)

    /** Gói cần giữ: mặc định + chính app + các app đã ghim. */
    fun wanted(ctx: Context): List<String> {
        val pinned = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE)
            .getStringSet("pinned", emptySet()) ?: emptySet()
        val fixed = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE)
            .getStringSet("fixed", emptySet()) ?: emptySet()
        return (DEFAULT + ctx.packageName + pinned + fixed).distinct()
    }

    /** Thêm các gói còn thiếu vào danh sách Millet, giữ nguyên gói đã có. */
    fun applyMillet(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        val have = (Settings.System.getString(cr, KEY) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var changed = false
        for (p in wanted(ctx)) {
            if (p !in have) { have.add(p); changed = true }
        }
        if (changed) Settings.System.putString(cr, KEY, have.joinToString(","))
        else true
    } catch (e: Throwable) {
        false
    }

    fun kickFcm(ctx: Context) {
        val actions = listOf(
            "com.google.android.intent.action.GTALK_HEARTBEAT",
            "com.google.android.intent.action.MCS_HEARTBEAT"
        )
        for (a in actions) {
            try { ctx.sendBroadcast(Intent(a).setPackage(GMS)) } catch (e: Throwable) {}
        }
    }

    fun fire(ctx: Context) {
        if (License.local(ctx) != License.State.ACTIVE) return
        applyMillet(ctx)
        kickFcm(ctx)
    }
}
