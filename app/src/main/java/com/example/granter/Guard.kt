package com.example.granter

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

object Guard {
    const val KEY = "MILLET_NO_RESTRICT_APP"
    const val FREEZE_KEY = "cloud_memory_freeze_whitelist"
    private const val GMS = "com.google.android.gms"

    val DEFAULT = listOf(
        GMS, "com.google.android.gms.persistent", "com.google.android.gsf",
        "com.google.android.location", "com.android.location.fused",
        "com.google.android.projection.gearhead", "com.xiaomi.barrage"
    )

    private val SOCIAL = setOf(
        "com.zing.zalo", "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
        "com.facebook.mlite", "com.instagram.android", "org.telegram.messenger",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android",
        "com.whatsapp", "com.viber.voip", "com.discord", "com.snapchat.android",
        "com.pinterest", "com.linkedin.android", "com.reddit.frontpage",
        "org.thoughtcrime.securesms", "com.threads.android", "com.instagram.barcelona",
        "com.google.android.youtube", "com.line.android", "jp.naver.line.android"
    )

    fun freezeWanted(ctx: Context): List<String> {
        val social = try {
            ctx.packageManager.getInstalledApplications(0).filter {
                val sys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                (!sys || it.packageName in SOCIAL) &&
                        (it.category == ApplicationInfo.CATEGORY_SOCIAL || it.packageName in SOCIAL)
            }.map { it.packageName }
        } catch (e: Throwable) { emptyList() }
        return (listOf(GMS, "com.zing.zalo") + social).filter { it != ctx.packageName }.distinct()
    }

    fun freezeUris(): List<android.net.Uri> = listOf(
        Settings.System.getUriFor(FREEZE_KEY),
        Settings.Global.getUriFor(FREEZE_KEY),
        Settings.Secure.getUriFor(FREEZE_KEY))

    fun hasSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun hasWriteSettings(ctx: Context): Boolean =
        try { Settings.System.canWrite(ctx) } catch (e: Throwable) { false }

    fun canWrite(ctx: Context): Boolean = hasSecure(ctx) || hasWriteSettings(ctx)

    fun wanted(ctx: Context): List<String> {
        val pinned = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE).getStringSet("pinned", emptySet()) ?: emptySet()
        val fixed = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE).getStringSet("fixed", emptySet()) ?: emptySet()
        return (DEFAULT + ctx.packageName + pinned + fixed).distinct()
    }

    @Volatile var lastError: String = ""

    fun applyMillet(ctx: Context): Boolean {
        if (License.localCached(ctx) != License.State.ACTIVE) { lastError = "Giấy phép không còn hiệu lực"; return false }
        if (applyDirect(ctx)) return true
        return applyViaServer(ctx)
    }

    private fun applyViaServer(ctx: Context): Boolean {
        if (!HhServer.ping(ctx)) return false
        val want = wanted(ctx)
        val script = "cur=\$(settings --user 0 get system $KEY); [ \"\$cur\" = null ] && cur=''; orig=\"\$cur\"; " +
                "for p in ${want.joinToString(" ")}; do case \",\$cur,\" in *\",\$p,\"*) ;; *) cur=\"\${cur:+\$cur,}\$p\";; esac; done; " +
                "[ \"\$cur\" != \"\$orig\" ] && settings --user 0 put system $KEY \"\$cur\"; settings --user 0 get system $KEY"
        val r = HhServer.exec(ctx, script)
        if (r == null) { lastError = HhServer.lastError; return false }
        val have = r.trim().split(",").map { it.trim() }
        val ok = want.all { it in have }
        if (!ok) lastError = "Máy chủ shell đã ghi nhưng danh sách Millet chưa khớp: ${r.trim().take(120)}"
        return ok
    }

    private fun applyDirect(ctx: Context): Boolean = try {
        val cr = ctx.contentResolver
        val have = (Settings.System.getString(cr, KEY) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var changed = false
        for (p in wanted(ctx)) { if (p !in have) { have.add(p); changed = true } }
        if (changed) {
            val ok = Settings.System.putString(cr, KEY, have.joinToString(","))
            if (!ok) lastError = "putString trả về false (hệ thống từ chối ghi)"
            ok
        } else true
    } catch (e: Throwable) {
        lastError = e.toString(); false
    }

    fun applyFreeze(ctx: Context) {
        if (License.localCached(ctx) != License.State.ACTIVE) return
        val want = freezeWanted(ctx)
        if (!FreezeStore.applyDirect(ctx, want)) applyFreezeViaServer(ctx, want)
    }

    private fun applyFreezeViaServer(ctx: Context, want: List<String>) {
        if (!HhServer.ping(ctx)) return
        val script = FreezeStore.script(ctx, want)
        if (script.isNotEmpty()) HhServer.exec(ctx, script)
    }

    private val SAFE_PKG = Regex("[A-Za-z0-9._]+")

    fun cleanup(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = gp(app)
        if (prefs.getBoolean("cleaned", false)) return
        val remove = ((prefs.getStringSet("pinned", emptySet()) ?: emptySet()) +
                (prefs.getStringSet("fixed", emptySet()) ?: emptySet()) + app.packageName)
            .filter { SAFE_PKG.matches(it) }.toSet()

        try {
            val have = (Settings.System.getString(app.contentResolver, KEY) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val keep = have.filter { it !in remove }
            if (keep.size != have.size) {
                val v = keep.joinToString(",")
                val ok = try { Settings.System.putString(app.contentResolver, KEY, v) } catch (e: Throwable) { false }
                if (!ok && HhServer.ping(app)) HhServer.exec(app, "settings --user 0 put system $KEY \"$v\"")
            }
        } catch (e: Throwable) {}

        prefs.edit().putBoolean("cleaned", true).apply()
        try {
            if (hasSecure(app) && HhServer.ping(app)) HhServer.exec(app, "pm revoke ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Throwable) {}
    }

    fun kickFcm(ctx: Context) {
        val actions = listOf("com.google.android.intent.action.GTALK_HEARTBEAT", "com.google.android.intent.action.MCS_HEARTBEAT")
        for (a in actions) { try { ctx.sendBroadcast(Intent(a).setPackage(GMS)) } catch (e: Throwable) {} }
    }

    private const val P_LOG = "override_log"
    private val logLock = Any()
    private fun gp(ctx: Context) = ctx.getSharedPreferences("granter", Context.MODE_PRIVATE)

    fun overrideLog(ctx: Context): List<Pair<Long, String>> = synchronized(logLock) {
        (gp(ctx).getString(P_LOG, "") ?: "").lines().mapNotNull { l ->
            val t = l.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            t to l.substringAfter('|', "")
        }
    }

    private fun addLog(ctx: Context, what: String) = synchronized(logLock) {
        val all = (overrideLog(ctx) + (System.currentTimeMillis() to what)).takeLast(100)
        gp(ctx).edit().putString(P_LOG, all.joinToString("\n") { "${it.first}|${it.second}" }).apply()
    }

    fun clearOverrideLog(ctx: Context) = synchronized(logLock) { gp(ctx).edit().remove(P_LOG).apply() }

    fun milletMissing(ctx: Context): Boolean {
        val have = (try { Settings.System.getString(ctx.contentResolver, KEY) } catch (e: Throwable) { null } ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return wanted(ctx).any { it !in have }
    }

    fun freezeStale(ctx: Context): Boolean {
        val want = freezeWanted(ctx)
        return FreezeStore.TABLES.any { FreezeStore.merged(ctx, FreezeStore.read(ctx, it), want) != null }
    }

    fun fire(ctx: Context, log: Boolean = false) {
        if (License.localCached(ctx) != License.State.ACTIVE) return
        if (log) {
            try {
                val m = milletMissing(ctx); val f = freezeStale(ctx)
                if (m || f) addLog(ctx, if (m && f) "millet+freeze" else if (m) "millet" else "freeze")
            } catch (e: Throwable) {}
        }
        applyMillet(ctx)
        try { applyFreeze(ctx) } catch (e: Throwable) {}
        kickFcm(ctx)
    }
}
