package com.example.granter

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Cấp quyền từ xa qua Google Sheet (tab HHTOOL).
 * - Server ký RSA; app chỉ giữ PUBLIC key => không ai làm server giả được.
 * - Mỗi lần hỏi có nonce ngẫu nhiên + mã máy nằm trong chữ ký => không phát lại được.
 * - Token lưu trong máy được kiểm chữ ký lại mỗi lần dùng => sửa SharedPreferences vô ích.
 */
object License {
    enum class State { ACTIVE, PENDING, EXPIRED, REVOKED, OFFLINE }

    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbzuzBMdry0FbdoVrz4oUIauO9NdBwWyu2mrcV1UJZLMQIwLDSmLJrJWl1rnf3s466XO/exec"
    private const val API_KEY = "Vf2te4paGki7t4ozRO2lZ1Am"
    private const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0acW7K7bYZgL5TUIyFTeSFrxPGDQ/GiBLtq3gY75J2sm9Y/LPXSN3N1zyDQR9zicrvNny8spPTWN0fT7gMtHw7DOFrplUuC0K3U838+7Vi/5gI+xjBsGt181WnU4SmeFtBB4FfHcBodxx2RrYRVvSjmjPBiX/hmOdoEldPr1AhScwxVKpx/B74BjUDI5KlirrYkj4pdobiQQW5tfRDJHQCDoKqQoIgQXoR8NFxu2BE1FrRnuFWGeukNGDw0b0YR8VAb+wxP36ekK9RAbwXmdG5V4hBrzSx05e7a6qKCue5BQm2fMQNiVNuv9hUeLsfDqIGdV7mpLVsspVbdILgYdIwIDAQAB"

    /** Offline tối đa bao lâu kể từ lần server xác nhận gần nhất (càng nhỏ, thu hồi càng có hiệu lực nhanh với máy mất mạng). */
    private const val GRACE_MS = 60L * 60 * 1000

    /** Điền SHA-256 chứng chỉ ký APK (hex, in hoa, không dấu ':') để chặn bản repack. Để trống = tắt. */
    private const val EXPECTED_CERT = ""

    private fun sp(c: Context) = c.getSharedPreferences("lic2", Context.MODE_PRIVATE)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Mã thiết bị ----------
    fun deviceCode(c: Context): String {
        val id = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val bad = id.isBlank() || id == "0" || id == "unknown" || id == "null" || id == "9774d56d682e549c"
        val raw = if (bad) fallbackId(c) else id
        val h = MessageDigest.getInstance("SHA-256").digest(("hhtool|$raw").toByteArray())
        val hex = h.take(6).joinToString("") { "%02X".format(it) }
        return "HT-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
    }

    private fun fallbackId(c: Context): String {
        val p = sp(c)
        return p.getString("uid", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("uid", it).apply()
        }
    }

    private fun newNonce(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // ---------- Chữ ký ----------
    private fun verify(payload: String, sigB64: String): Boolean = try {
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        val s = Signature.getInstance("SHA256withRSA")
        s.initVerify(pub)
        s.update(payload.toByteArray(Charsets.UTF_8))
        s.verify(Base64.decode(sigB64, Base64.DEFAULT))
    } catch (e: Throwable) { false }

    @Suppress("DEPRECATION")
    private fun certOk(c: Context): Boolean {
        if (EXPECTED_CERT.isEmpty()) return true
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            sigs?.any {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                    .joinToString("") { b -> "%02X".format(b) } == EXPECTED_CERT
            } ?: false
        } catch (e: Throwable) { false }
    }

    // ---------- Trạng thái lưu trong máy (offline, rẻ) ----------
    /** Gọi thoải mái trên main thread: chỉ kiểm lại chữ ký + hạn + chống lùi giờ. */
    fun local(c: Context): State {
        if (!certOk(c)) return State.OFFLINE
        val p = sp(c)
        val nonce = p.getString("nonce", null) ?: return State.OFFLINE
        val status = p.getString("status", "") ?: ""
        val expiryS = p.getString("expiry", "0") ?: "0"
        val stS = p.getString("st", "") ?: ""
        val sig = p.getString("sig", "") ?: ""
        if (!verify("v2|$nonce|${deviceCode(c)}|$status|$expiryS|$stS", sig)) return State.OFFLINE
        when (status) {
            "REVOKED" -> return State.REVOKED
            "EXPIRED" -> return State.EXPIRED
            "ACTIVE" -> {}
            else -> return State.PENDING
        }
        val serverMs = stS.toLongOrNull() ?: return State.OFFLINE
        val el = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val aEl = p.getLong("a_el", -1L)
        val aWall = p.getLong("a_wall", -1L)
        val sameBoot = aEl >= 0 && el >= aEl &&
                Math.abs((wall - el) - (aWall - aEl)) < 120_000L
        val age: Long
        if (sameBoot) {
            age = el - aEl
        } else {
            if (wall < serverMs - 5 * 60_000L) return State.OFFLINE   // đồng hồ bị lùi
            age = wall - serverMs
        }
        if (age > GRACE_MS) return State.OFFLINE
        val expiry = expiryS.toLongOrNull() ?: 0L
        if (expiry > 0 && serverMs + age > expiry) return State.EXPIRED
        return State.ACTIVE
    }

    // ---------- Tên máy / phiên bản ROM gửi lên bảng ----------
    private fun sysprop(key: String): String {
        var v = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as String
        } catch (e: Throwable) { "" }
        if (v.isBlank()) v = try {
            Runtime.getRuntime().exec(arrayOf("getprop", key))
                .inputStream.bufferedReader().readText().trim()
        } catch (e: Throwable) { "" }
        return v.trim()
    }

    /** Ví dụ: "Redmi K80 Pro (24122RKC7C)"; không đọc được tên thương mại thì "Xiaomi 24122RKC7C". */
    fun deviceLabel(): String {
        val market = listOf("ro.product.marketname", "ro.product.odm.marketname", "ro.product.vendor.marketname")
            .map { sysprop(it) }.firstOrNull { it.isNotEmpty() }
        return if (market != null) market + " (" + Build.MODEL + ")" else Build.MANUFACTURER + " " + Build.MODEL
    }

    /** Ví dụ: "OS2.0.8.0.VOCCNXM · Android 15"; không đọc được thì dùng Build.DISPLAY. */
    fun romLabel(): String {
        val ver = listOf("ro.mi.os.version.incremental", "ro.build.version.incremental")
            .map { sysprop(it) }.firstOrNull { it.isNotEmpty() }
        return if (ver != null) ver + " · Android " + Build.VERSION.RELEASE else (Build.DISPLAY ?: "")
    }

    // ---------- Hỏi server (gọi ở thread nền) ----------
    fun refresh(c: Context): State {
        val device = deviceCode(c)
        val nonce = newNonce()
        val url = ENDPOINT + "?v=2&key=" + enc(API_KEY) + "&device=" + enc(device) + "&nonce=" + nonce +
                "&name=" + enc(deviceLabel()) +
                "&rom=" + enc(romLabel()) +
                "&auto=" + autostart(c) + "&bat=" + (if (batteryOk(c)) 1 else 0)
        val body = http(url) ?: return local(c)
        try {
            val j = JSONObject(body)
            if (!j.optBoolean("ok", false) || j.optString("nonce") != nonce) return local(c)
            val status = j.optString("status")
            val expiry = j.optString("expiry", "0")
            val st = j.optString("server_time")
            val sig = j.optString("sig2")
            if (!verify("v2|$nonce|$device|$status|$expiry|$st", sig)) return local(c)
            sp(c).edit()
                .putString("nonce", nonce).putString("status", status)
                .putString("expiry", expiry).putString("st", st).putString("sig", sig)
                .putLong("a_el", SystemClock.elapsedRealtime())
                .putLong("a_wall", System.currentTimeMillis())
                .putLong("last_try", System.currentTimeMillis())
                .apply()
        } catch (e: Throwable) {
            return local(c)
        }
        return local(c)
    }

    /** Hỏi lại server nếu đã quá [gapMs] kể từ lần trước. */
    fun refreshIfDue(c: Context, gapMs: Long = 10 * 60_000L): State {
        val last = sp(c).getLong("last_try", 0L)
        return if (System.currentTimeMillis() - last >= gapMs || System.currentTimeMillis() < last) refresh(c) else local(c)
    }

    private fun http(u: String): String? = try {
        val cn = URL(u).openConnection() as HttpURLConnection
        cn.connectTimeout = 10_000
        cn.readTimeout = 15_000
        cn.instanceFollowRedirects = true
        if (cn.responseCode == 200) cn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Throwable) { null }

    // ---------- Trạng thái máy gửi lên Sheet (bằng chứng khi khách khiếu nại) ----------
    fun batteryOk(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    } catch (e: Throwable) { false }

    /** 1 = cho phép, 0 = bị chặn, -1 = không đọc được (máy không phải Xiaomi). */
    fun autostart(c: Context): Int = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        if ((m.invoke(ops, 10008, Process.myUid(), c.packageName) as Int) == 0) 1 else 0
    } catch (e: Throwable) { -1 }
}
