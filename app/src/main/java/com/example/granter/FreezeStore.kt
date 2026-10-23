package com.example.granter

import android.content.Context
import android.provider.Settings

/**
 * Quản lý khóa cloud_memory_freeze_whitelist:
 *  - extra   : gói thêm tay (luôn được nối vào)
 *  - exclude : gói loại trừ (luôn bị gỡ ra, không bao giờ nối lại)
 * Kết quả ghi = (giá trị hiện có − exclude) ∪ (gói app muốn giữ ∪ extra − exclude)
 */
object FreezeStore {
    const val KEY = "cloud_memory_freeze_whitelist"
    const val GMS = "com.google.android.gms"
    const val ZALO = "com.zing.zalo"

    /** Không cho loại trừ. */
    val REQUIRED = listOf(GMS, ZALO)
    val TABLES = listOf("system", "global", "secure")

    private const val P_EXTRA = "freeze_extra"
    private const val P_EXCLUDE = "freeze_exclude"
    private val VALID = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

    private fun prefs(c: Context) = c.getSharedPreferences("granter", Context.MODE_PRIVATE)

    fun isValid(pkg: String) = VALID.matches(pkg)

    fun extra(c: Context): Set<String> = prefs(c).getStringSet(P_EXTRA, emptySet())!!.toSet()
    fun exclude(c: Context): Set<String> = prefs(c).getStringSet(P_EXCLUDE, emptySet())!!.toSet()

    fun save(c: Context, extra: Set<String>, exclude: Set<String>) {
        prefs(c).edit()
            .putStringSet(P_EXTRA, extra.toSet())
            .putStringSet(P_EXCLUDE, exclude - REQUIRED.toSet())
            .apply()
    }

    fun read(c: Context, table: String): String? = try {
        when (table) {
            "system" -> Settings.System.getString(c.contentResolver, KEY)
            "global" -> Settings.Global.getString(c.contentResolver, KEY)
            else -> Settings.Secure.getString(c.contentResolver, KEY)
        }
    } catch (e: Throwable) { null }

    fun parse(v: String?): List<String> =
        (v ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "null" }

    /** Danh sách app muốn giữ sau khi cộng gói thêm tay và trừ gói loại trừ. */
    fun finalWanted(c: Context, base: Iterable<String>): List<String> {
        val ex = exclude(c)
        return (base.toList() + extra(c)).filter { it !in ex && isValid(it) }.distinct()
    }

    /** Giá trị mới cho 1 bảng; null nếu bảng trống (không đụng vào) hoặc không có gì đổi. */
    fun merged(c: Context, current: String?, wanted: Iterable<String>): String? {
        val cur = parse(current)
        if (cur.isEmpty()) return null
        val ex = exclude(c)
        val out = LinkedHashSet<String>()
        cur.filterTo(out) { it !in ex }
        finalWanted(c, wanted).filterTo(out) { it !in ex }
        val res = out.joinToString(",")
        return if (res == cur.joinToString(",")) null else res
    }

    /** Đoạn lệnh shell ghi lại khóa ở những bảng cần đổi. Rỗng nếu không có gì để ghi. */
    fun script(c: Context, wanted: Iterable<String>): String {
        val sb = StringBuilder()
        for (t in TABLES) {
            val nv = merged(c, read(c, t), wanted) ?: continue
            if (nv.contains('\'')) continue
            sb.append("settings --user 0 put $t $KEY '$nv' 2>/dev/null; ")
        }
        return sb.toString()
    }

    /** Ghi thẳng bằng Settings API. Trả về false nếu có bảng cần ghi mà bị từ chối (để nhờ máy chủ shell). */
    fun applyDirect(c: Context, wanted: Iterable<String>): Boolean {
        var ok = true
        for (t in TABLES) {
            val nv = merged(c, read(c, t), wanted) ?: continue
            val done = try {
                when (t) {
                    "system" -> Settings.System.putString(c.contentResolver, KEY, nv)
                    "global" -> Settings.Global.putString(c.contentResolver, KEY, nv)
                    else -> Settings.Secure.putString(c.contentResolver, KEY, nv)
                }
            } catch (e: Throwable) { false }
            if (!done) ok = false
        }
        return ok
    }
}
