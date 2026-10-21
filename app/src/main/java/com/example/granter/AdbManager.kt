package com.example.granter

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/** Quản lý khóa + kết nối ADB nội bộ (Wireless debugging của chính máy). */
class AdbManager(ctx: Context) : AbsAdbConnectionManager() {

    private val mKey: PrivateKey
    private val mCert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(ctx.filesDir, "adb_key.pk8")
        val certFile = File(ctx.filesDir, "adb_cert.der")
        var k: PrivateKey? = null
        var c: Certificate? = null
        if (keyFile.exists() && certFile.exists()) {
            try {
                k = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                c = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes()))
            } catch (ignored: Exception) {
                k = null; c = null
            }
        }
        if (k == null || c == null) {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = gen.generateKeyPair()
            k = pair.private
            val cert = CertGen.generate(pair, "HH tool")
            c = cert
            keyFile.writeBytes(k.encoded)
            certFile.writeBytes(c.encoded)
        }
        mKey = k
        mCert = c
        // Conscrypt cần cho ghép nối (TLS key export). Chèn sau khi tạo khóa xong.
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    override fun getPrivateKey(): PrivateKey = mKey
    override fun getCertificate(): Certificate = mCert
    override fun getDeviceName(): String = "HHTool"
}

object AdbBackend {
    @Volatile private var mgr: AdbManager? = null
    @Volatile var lastError: String = ""

    fun get(ctx: Context): AdbManager =
        mgr ?: synchronized(this) {
            mgr ?: AdbManager(ctx.applicationContext).also { mgr = it }
        }

    fun isConnected(ctx: Context): Boolean = try { get(ctx).isConnected } catch (ignored: Throwable) { false }

    fun pair(ctx: Context, host: String, port: Int, code: String): Boolean = try {
        get(ctx).pair(host, port, code)
    } catch (e: Throwable) {
        lastError = (e.cause ?: e).message ?: e.toString(); false
    }

    fun ensureConnected(ctx: Context): Boolean {
        return try {
            val m = get(ctx)
            if (m.isConnected) true else m.autoConnect(ctx, 8000L)
        } catch (e: Throwable) {
            lastError = (e.cause ?: e).message ?: e.toString(); false
        }
    }

    fun run(ctx: Context, script: String): String = try {
        val st = get(ctx).openStream("shell:$script")
        val out = String(st.openInputStream().readBytes())
        try { st.close() } catch (ignored: Exception) {}
        out
    } catch (e: Throwable) {
        "ERR ${(e.cause ?: e).message}"
    }
}
