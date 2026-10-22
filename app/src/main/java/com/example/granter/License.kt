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
import android.util.Log
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object License {
    enum class State { ACTIVE, PENDING, EXPIRED, REVOKED, OFFLINE }

    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbzuzBMdry0FbdoVrz4oUIauO9NdBwWyu2mrcV1UJZLMQIwLDSmLJrJWl1rnf3s466XO/exec"
    private const val API_KEY = "Vf2te4paGki7t4ozRO2lZ1Am"
    private const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0acW7K7bYZgL5TUIyFTeSFrxPGDQ/GiBLtq3gY75J2sm9Y/LPXSN3N1zyDQR9zicrvNny8spPTWN0fT7gMtHw7DOFrplUuC0K3U838+7Vi/5gI+xjBsGt181WnU4SmeFtBB4FfHcBodxx2RrYRVvSjmjPBiX/hmOdoEldPr1AhScwxVKpx/B74BjUDI5KlirrYkj4pdobiQQW5tfRDJHQCDoKqQoIgQXoR8NFxu2BE1FrRnuFWGeukNGDw0b0YR8VAb+wxP36ekK9RAbwXmdG5V4hBrzSx05e7a6qKCue5BQm2fMQNiVNuv9hUeLsfDqIGdV7mpLVsspVbdILgYdIwIDAQAB"

    private const val GRACE_MS = 45L * 60 * 1000
    private const val MIN_REFRESH_GAP_MS = 3L * 60 * 1000
    private const val AUTO_REFRESH_GAP_MS = 10L * 60 * 1000
    private const val SAME_BOOT_TOLERANCE_MS = 120_000L
    private const val CLOCK_SKEW_TOLERANCE_MS = 5L * 60 * 1000

    private const val EXPECTED_CERT = "5229A654918A31A4E8A5A1EF54F00796FD7BFCFA7DAD040FE64D3CD49A95DE88"
    private const val PREFS = "lic3"
    private const val TAG = "License"
    private const val LOCAL_CACHE_MS = 8000L

    private val lastLocal = AtomicLong(0)
    private val lastLocalState = AtomicReference(State.OFFLINE)

    // =====================================================================
    // KHAI BÁO NATIVE (C++) - THÊM MỚI
    // =====================================================================
    init {
        try {
            System.loadLibrary("license")
        } catch (e: Throwable) {
            Log.e(TAG, "Không thể load thư viện native C++", e)
        }
    }

    private external fun nativeVerify(payload: String, sigB64: String, pubKeyB64: String): Boolean
    // =====================================================================

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

    private fun deviceFingerprint(): String {
        val src = "${Build.BRAND}|${Build.DEVICE}|${Build.MODEL}|${Build.FINGERPRINT}|${Build.HARDWARE}"
        val h = MessageDigest.getInstance("SHA-256").digest(src.toByteArray())
        return h.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun newNonce(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // =====================================================================
    // HÀM VERIFY ĐÃ ĐƯỢC CHUYỂN SANG GỌI NATIVE C++
    // =====================================================================
    private fun verify(payload: String, sigB64: String): Boolean {
        return try {
            // Gọi hàm C++ thay vì dùng thư viện Java
            nativeVerify(payload, sigB64, PUBLIC_KEY)
        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi verify native", e)
            false
        }
    }
    // =====================================================================

    @Suppress("DEPRECATION")
    private fun certOk(c: Context): Boolean {
        if (EXPECTED_CERT.isEmpty()) return true
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            sigs?.any {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } == EXPECTED_CERT
            } ?: false
        } catch (e: Throwable) { false }
    }

    fun localCached(c: Context): State {
        val now = SystemClock.elapsedRealtime()
        val last = lastLocal.get()
        if (now - last in 0 until LOCAL_CACHE_MS) return lastLocalState.get()!!
        val s = local(c)
        lastLocal.set(now)
        lastLocalState.set(s)
        return s
    }

    fun local(c: Context): State {
        if (!certOk(c)) return State.OFFLINE
        val p = sp(c)
        val nonce   = p.getString("nonce",   null) ?: return State.OFFLINE
        val status  = p.getString("status",  null) ?: return State.OFFLINE
        val expiryS = p.getString("expiry",  "0")  ?: "0"
        val stS     = p.getString("st",      null) ?: return State.OFFLINE
        val sig     = p.getString("sig",     null) ?: return State.OFFLINE
        val fp      = p.getString("fp",      null) ?: return State.OFFLINE

        if (fp != deviceFingerprint()) return State.OFFLINE
        val payload = "v3|$nonce|${deviceCode(c)}|$fp|$status|$expiryS|$stS"
        if (!verify(payload, sig)) return State.OFFLINE

        when (status) {
            "REVOKED" -> return State.REVOKED
            "EXPIRED" -> return State.EXPIRED
            "ACTIVE"  -> {}
            else      -> return State.PENDING
        }

        val serverMs = stS.toLongOrNull() ?: return State.OFFLINE
        val el   = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val aEl   = p.getLong("a_el",   -1L)
        val aWall = p.getLong("a_wall", -1L)
        val sameBoot = aEl >= 0 && el >= aEl && Math.abs((wall - el) - (aWall - aEl)) < SAME_BOOT_TOLERANCE_MS

        val age: Long = if (sameBoot) el - aEl else {
            if (wall < serverMs - CLOCK_SKEW_TOLERANCE_MS) return State.OFFLINE
            wall - serverMs
        }

        if (age > GRACE_MS) return State.OFFLINE
        val expiry = expiryS.toLongOrNull() ?: 0L
        if (expiry > 0 && serverMs + age > expiry) return State.EXPIRED
        return State.ACTIVE
    }

    private fun sysprop(key: String): String {
        var v = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
        } catch (e: Throwable) { "" }
        if (v.isBlank()) v = try {
            Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream.bufferedReader().readText().trim()
        } catch (e: Throwable) { "" }
        return v.trim()
    }

    fun deviceLabel(): String {
        val market = listOf("ro.product.marketname", "ro.product.odm.marketname", "ro.product.vendor.marketname")
            .map { sysprop(it) }.firstOrNull { it.isNotEmpty() }
        return if (market != null) "$market (${Build.MODEL})" else "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    fun romLabel(): String {
        val ver = listOf("ro.mi.os.version.incremental", "ro.build.version.incremental")
            .map { sysprop(it) }.firstOrNull { it.isNotEmpty() }
        return if (ver != null) "$ver · Android ${Build.VERSION.RELEASE}" else (Build.DISPLAY ?: "")
    }

    private val refreshing = AtomicBoolean(false)

    fun refresh(c: Context): State {
        if (!refreshing.compareAndSet(false, true)) return local(c)
        try {
            val device = deviceCode(c)
            val fp     = deviceFingerprint()
            val nonce  = newNonce()
            val url = ENDPOINT + "?v=3" +
                    "&key="    + enc(API_KEY) +
                    "&device=" + enc(device) +
                    "&fp="     + enc(fp) +
                    "&nonce="  + enc(nonce) +
                    "&name="   + enc(deviceLabel()) +
                    "&rom="    + enc(romLabel()) +
                    "&auto="   + autostart(c) +
                    "&bat="    + (if (batteryOk(c)) 1 else 0)

            val body = http(url) ?: return local(c)
            try {
                val j = JSONObject(body)
                if (!j.optBoolean("ok", false)) return local(c)
                if (j.optString("nonce") != nonce) return local(c)

                val status = j.optString("status")
                val expiry = j.optString("expiry", "0")
                val st     = j.optString("server_time")
                val sig    = j.optString("sig3")

                val payload = "v3|$nonce|$device|$fp|$status|$expiry|$st"
                if (!verify(payload, sig)) return local(c)

                sp(c).edit()
                    .putString("nonce",  nonce)
                    .putString("status", status)
                    .putString("expiry", expiry)
                    .putString("st",     st)
                    .putString("sig",    sig)
                    .putString("fp",     fp)
                    .putLong("a_el",   SystemClock.elapsedRealtime())
                    .putLong("a_wall", System.currentTimeMillis())
                    .putLong("last_try", System.currentTimeMillis())
                    .apply()
            } catch (e: Throwable) {
                return local(c)
            }
            return local(c)
        } finally {
            refreshing.set(false)
        }
    }

    fun refreshIfDue(c: Context, gapMs: Long = AUTO_REFRESH_GAP_MS): State {
        val p = sp(c)
        val last = p.getLong("last_try", 0L)
        val now = System.currentTimeMillis()
        val since = now - last

        val shouldRefresh = when {
            last == 0L        -> true
            since < 0         -> since <= -MIN_REFRESH_GAP_MS
            since >= gapMs    -> true
            else              -> false
        }
        return if (shouldRefresh) refresh(c) else localCached(c)
    }

    private fun http(u: String): String? {
        var cn: HttpURLConnection? = null
        return try {
            cn = URL(u).openConnection() as HttpURLConnection
            cn.connectTimeout = 10_000
            cn.readTimeout = 15_000
            cn.instanceFollowRedirects = true
            cn.setRequestProperty("User-Agent", "Granter/License")
            if (cn.responseCode == 200) cn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Throwable) {
            null
        } finally {
            try { cn?.disconnect() } catch (_: Throwable) {}
        }
    }

    fun batteryOk(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
    } catch (e: Throwable) { false }

    fun autostart(c: Context): Int = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        if ((m.invoke(ops, 10008, Process.myUid(), c.packageName) as Int) == 0) 1 else 0
    } catch (e: Throwable) { -1 }
}
