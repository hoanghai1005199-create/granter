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

    private const val ENDPOINT = "https://script.google.com/macros/s/AKfycbyqIX05gSqExLV-ZT_YRiH3ebB_XMhgh4F6W9JRjH06gmTfwkYbT7E4KsKqECiTknXZsA/exec"
    private const val API_KEY = "Vf2te4paGki7t4ozRO2lZ1Am"
    
    // ⚠️ DÁN PUBLIC KEY MỚI CỦA BẠN VÀO ĐÂY (Từ lệnh cat public.pem)
    private const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArY4q5Gvmyd9wJc/byTaJJaxkYQEmrUFq2Ta/d92voB6Owdm4/nQXYXqOsiqwFftmnOms/JMRuG2fX9WLs5rKW951/1mWjVDgTMCb31e3Yrd07xsEG5Uw9L0W9GFOgf4RZsE8Nw5AikrvuVw0zOa48R6MnXbrMfG/VOa4yH23gJRdeV0/5FuGhFr1ecFdkWYDgxLnixK63pBkj5GTpYCHRhVUmiM+XvWRXX14oK2A9mkT3gpd21zZ0cEY5v4gVZxmmIIUQlz7Wll9TTz9l1KoWXFOpNRMabvPFR5whqJPmS8/2Kd94mN23zv+rYZUvGdUpLO0djM2PmDcWSCbvpx7cQIDAQAB"

    private const val GRACE_MS = 45L * 60 * 1000
    private const val MIN_REFRESH_GAP_MS = 3L * 60 * 1000
    private const val AUTO_REFRESH_GAP_MS = 10L * 60 * 1000
    private const val SAME_BOOT_TOLERANCE_MS = 120_000L
    private const val CLOCK_SKEW_TOLERANCE_MS = 5L * 60 * 1000

    // ĐÃ CẬP NHẬT MÃ SHA-256 MỚI (Từ ảnh chụp màn hình của bạn)
    private const val EXPECTED_CERT = "32D7C9EDC4C1EC26CE591006E9C9CDDF142BA04E2F57A0340AAAA5740357DBB4"
    
    private const val PREFS = "lic3"
    private const val TAG = "License"
    private const val LOCAL_CACHE_MS = 8000L

    private val lastLocal = AtomicLong(0)
    private val lastLocalState = AtomicReference(State.OFFLINE)
    private val refreshing = AtomicBoolean(false)

    init {
        try {
            System.loadLibrary("license")
        } catch (e: Throwable) {
            Log.e(TAG, "Không thể load thư viện native C++", e)
        }
    }

    private external fun nativeVerify(payload: String, sigB64: String, pubKeyB64: String): Boolean

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

    private fun verify(payload: String, sigB64: String): Boolean {
        try {
            if (nativeVerify(payload, sigB64, PUBLIC_KEY)) return true
        } catch (e: Throwable) {
            Log.e(TAG, "Native verify exception", e)
        }
        
        return try {
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
            val s = Signature.getInstance("SHA256withRSA")
            s.initVerify(pub)
            s.update(payload.toByteArray(Charsets.UTF_8))
            s.verify(Base64.decode(sigB64, Base64.DEFAULT))
        } catch (e: Throwable) { 
            Log.e(TAG, "Java verify exception", e)
            false 
        }
    }

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
        val kid     = p.getString("kid",     "k1") 
        val nonce   = p.getString("nonce",   null) ?: return State.OFFLINE
        val status  = p.getString("status",  null) ?: return State.OFFLINE
        val expiryS = p.getString("expiry",  "0")  ?: "0"
        val stS     = p.getString("st",      null) ?: return State.OFFLINE
        val sig     = p.getString("sig",     null) ?: return State.OFFLINE
        val fp      = p.getString("fp",      null) ?: return State.OFFLINE

        if (fp != deviceFingerprint()) return State.OFFLINE
        val payload = "v3|$kid|$nonce|${deviceCode(c)}|$fp|$status|$expiryS|$stS"
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

                val kid    = j.optString("kid", "k1")
                val status = j.optString("status")
                val expiry = j.optString("expiry", "0")
                val st     = j.optString("server_time")
                val sig    = j.optString("sig3")

                val payload = "v3|$kid|$nonce|$device|$fp|$status|$expiry|$st"
                if (!verify(payload, sig)) return local(c)

                sp(c).edit()
                    .putString("kid",    kid)
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
