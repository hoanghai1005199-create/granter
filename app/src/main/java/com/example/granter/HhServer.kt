package com.example.granter

import android.content.Context
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
 */
object HhServer {
    const val VERSION = 1
    private const val CLS = "com.example.granter.ShellServer"
    private const val LOG = "/data/local/tmp/hhtool_server.log"

    @Volatile var lastError: String = ""

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("granter", Context.MODE_PRIVATE)

    @Synchronized
    private fun token(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("hh_token", null)?.let { return it }
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        val t = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("hh_token", t).commit()
        return t
    }

    private fun mac(tok: String, ns: ByteArray, nc: ByteArray, tag: Int): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(tok.toByteArray(Charsets.UTF_8), "HmacSHA256"))
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
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1500)
                s.soTimeout = readTimeoutMs
                val tok = token(ctx)
                val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val ns = ByteArray(16)
                inp.readFully(ns)
                val nc = ByteArray(16)
                SecureRandom().nextBytes(nc)
                out.write(nc)
                out.write(mac(tok, ns, nc, 1))
                out.flush()
                val m2 = ByteArray(32)
                inp.readFully(m2)
                if (!MessageDigest.isEqual(m2, mac(tok, ns, nc, 2))) {
                    lastError = "Máy chủ ở cổng $port không xác thực được (có thể app khác đã chiếm cổng)"
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

    private fun startCommand(ctx: Context, port: Int, tok: String): String =
        "S=''; command -v setsid >/dev/null 2>&1 && S=setsid; " +
                "CLASSPATH='${ctx.packageCodePath}' \$S nohup app_process /system/bin " +
                "--nice-name=hhtool_server $CLS $tok $port >$LOG 2>&1 </dev/null & sleep 1; echo started"

    /**
     * Khởi động máy chủ (hoặc khởi động lại nếu đang chạy bản cũ).
     * [run] chạy một lệnh shell bằng quyền shell (Shizuku/ADB) và trả về đầu ra.
     */
    fun start(ctx: Context, run: (String) -> String): Boolean {
        val v = version(ctx)
        if (v == VERSION) return true
        if (v != -1) {            // bản cũ: tắt trước
            stop(ctx)
            Thread.sleep(500)
        }
        val tok = token(ctx)
        val port = 20000 + SecureRandom().nextInt(40000)
        prefs(ctx).edit().putInt("hh_port", port).commit()
        val out = run(startCommand(ctx, port, tok))
        repeat(20) {
            Thread.sleep(300)
            if (version(ctx) == VERSION) return true
        }
        val err = lastError
        val log = run("ls -l $LOG 2>&1; cat $LOG 2>&1; ps -A 2>/dev/null | grep [h]htool; " +
                "echo setsid=\$(command -v setsid); id").trim().take(600)
        lastError = "Không khởi động được máy chủ.\n$err" +
                (if (out.isNotBlank()) "\n${out.trim().take(200)}" else "") +
                (if (log.isNotEmpty()) "\nNhật ký: $log" else "")
        return false
    }
}
