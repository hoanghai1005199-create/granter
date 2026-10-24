package com.example.granter

import android.content.Context
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client của máy chủ shell (ShellServer). Máy chủ chạy bằng quyền shell, nghe ở 127.0.0.1,
 * khởi động một lần qua Shizuku/ADB rồi sống tiếp sau khi ngắt ADB (đến khi khởi động lại máy).
 * Mọi hàm ở đây là mạng: chỉ gọi trong thread nền.
 *
 * THAY ĐỔI: không còn tự sinh "hh_token" ngẫu nhiên nữa. Khoá bắt tay HMAC ("session
 * token") giờ suy ra từ License.signedPayloadIfActive() — tức là chỉ khởi động/điều
 * khiển được ShellServer khi đang có license ACTIVE đã được server ký hợp lệ. ShellServer
 * tự verify lại chữ ký đó độc lập (xem ShellServer.java), nên patch lớp Kotlin này không
 * đủ để qua mặt nó.
 */
object HhServer {
    const val VERSION = 1
    private const val CLS = "com.example.granter.ShellServer"
    private const val LOG = "/data/local/tmp/hhtool_server.log"

    @Volatile var lastError: String = ""

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("granter", Context.MODE_PRIVATE)

    /** Khoá HMAC của phiên hiện tại: SHA-256(sig) của payload license đã start server. */
    private fun sessionKey(ctx: Context): ByteArray? {
        val hex = prefs(ctx).getString("hh_session", null) ?: return null
        return try {
            ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }
        } catch (e: Throwable) { null }
    }

    private fun mac(key: ByteArray, ns: ByteArray, nc: ByteArray, tag: Int): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        m.update(ns)
        m.update(nc)
        m.update(tag.toByte())
        return m.doFinal()
    }

    /** Gọi máy chủ. Trả về (mã, đầu ra) hoặc null nếu không gọi được (lý do ở lastError). */
    private fun call(ctx: Context, op: Int, cmd: String, readTimeoutMs: Int): Pair<Int, ByteArray>? {
        val port = prefs(ctx).getInt("hh_port", 0)
        if (port == 0) {
            lastError = "Máy chủ shell chưa được khởi động"
            return null
        }
        val key = sessionKey(ctx)
        if (key == null) {
            lastError = "Chưa có phiên hợp lệ (license không ACTIVE hoặc chưa start server)"
            return null
        }
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1500)
                s.soTimeout = readTimeoutMs
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val ns = ByteArray(16)
                inp.readFully(ns)
                val nc = ByteArray(16)
                SecureRandom().nextBytes(nc)
                out.write(nc)
                out.write(mac(key, ns, nc, 1))
                out.flush()
                val m2 = ByteArray(32)
                inp.readFully(m2)
                if (!MessageDigest.isEqual(m2, mac(key, ns, nc, 2))) {
                    lastError = "Máy chủ ở cổng $port không xác thực được (license đã đổi, hoặc app khác chiếm cổng)"
                    return@use null
                }
                val cb = cmd.toByteArray(Charsets.UTF_8)
                out.writeByte(op)
                out.writeInt(cb.size)
                out.write(cb)
                out.flush()
                val code = inp.readInt()
                val n = inp.readInt()
                if (n < 0 || n > (1 shl 21)) {
                    lastError = "Phản hồi không hợp lệ"
                    return@use null
                }
                val buf = ByteArray(n)
                inp.readFully(buf)
                Pair(code, buf)
            }
        } catch (e: Throwable) {
            lastError = e.message ?: e.toString()
            null
        }
    }

    /** Số phiên bản của máy chủ đang chạy, hoặc -1 nếu không gọi được. */
    fun version(ctx: Context): Int = call(ctx, 0, "", 3000)?.first ?: -1

    /** Máy chủ đang chạy và đúng phiên bản của app này? */
    fun ping(ctx: Context): Boolean = version(ctx) == VERSION

    /** Chạy lệnh shell bằng quyền shell. Trả về đầu ra, hoặc null nếu không gọi được máy chủ. */
    fun exec(ctx: Context, cmd: String): String? {
        val r = call(ctx, 1, cmd, 70_000) ?: return null
        return String(r.second, Charsets.UTF_8)
    }

    fun stop(ctx: Context) {
        call(ctx, 2, "", 3000)
    }

    private fun startCommand(ctx: Context, port: Int, payloadB64: String, sigB64: String): String =
        "S=''; command -v setsid >/dev/null 2>&1 && S=setsid; " +
                "CLASSPATH='${ctx.packageCodePath}' \$S nohup app_process /system/bin " +
                "--nice-name=hhtool_server $CLS '$payloadB64' '$sigB64' $port >$LOG 2>&1 </dev/null & sleep 1; echo started"

    /**
     * Khởi động máy chủ (hoặc khởi động lại nếu đang chạy bản cũ).
     * [run] chạy một lệnh shell bằng quyền shell (Shizuku/ADB) và trả về đầu ra.
     *
     * Thất bại ngay (không gọi app_process) nếu license hiện không ACTIVE — ShellServer
     * cũng tự kiểm tra lại y hệt điều kiện này, đây chỉ là chặn sớm để báo lỗi rõ ràng
     * hơn cho người dùng, KHÔNG phải lớp bảo vệ thật.
     */
    fun start(ctx: Context, run: (String) -> String): Boolean {
        val v = version(ctx)
        if (v == VERSION) return true
        if (v != -1) {            // bản cũ: tắt trước
            stop(ctx)
            Thread.sleep(500)
        }

        // Làm mới nếu payload đã cũ, để tuổi payload luôn nằm trong GRACE_MS của ShellServer.
        // (start() chạy ở thread nền nên gọi mạng ở đây là an toàn.)
        try { License.refreshIfDue(ctx) } catch (_: Throwable) {}
        val signed = License.signedPayloadIfActive(ctx)
        if (signed == null) {
            lastError = "License không ACTIVE (hoặc chưa refresh) — không khởi động máy chủ."
            return false
        }
        val (payload, sig) = signed
        val payloadB64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val sessionHex = try {
            MessageDigest.getInstance("SHA-256").digest(Base64.decode(sig, Base64.NO_WRAP))
                .joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            lastError = "Chữ ký license không hợp lệ: ${e.message}"
            return false
        }

        val port = 20000 + SecureRandom().nextInt(40000)
        prefs(ctx).edit()
            .putInt("hh_port", port)
            .putString("hh_session", sessionHex)
            .commit()

        val out = run(startCommand(ctx, port, payloadB64, sig))
        repeat(20) {
            Thread.sleep(300)
            if (version(ctx) == VERSION) return true
        }
        val err = lastError
        val log = run("ls -l $LOG 2>&1; cat $LOG 2>&1; ps -A 2>/dev/null | grep [h]htool; " +
                "echo setsid=\$(command -v setsid); id").trim().take(600)
        lastError = "Không khởi động được máy chủ.\n$err" +
                (if (out.isNotBlank()) "\n${out.trim().take(200)}" else "") +
                (if (log.isNotEmpty()) "\nNhật ký: $log" else "") +
                "\nCHẨN ĐOÁN ADB:\n" + AdbBackend.probe(ctx)
        return false
    }
}
