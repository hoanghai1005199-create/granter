package com.example.granter

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.conscrypt.Conscrypt
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateExtensions
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.KeyIdentifier
import sun.security.x509.PrivateKeyUsageExtension
import sun.security.x509.SubjectKeyIdentifierExtension
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
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
import java.util.Date
import java.util.Random

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
            val pub = pair.public
            k = pair.private
            val subject = "CN=HH tool"
            val algo = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 86400000L * 3650)
            val ext = CertificateExtensions()
            ext.set("SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(pub).identifier))
            val name = X500Name(subject)
            ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber",
                CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algo)))
            info.set("subject", CertificateSubjectName(name))
            info.set("key", CertificateX509Key(pub))
            info.set("validity", CertificateValidity(notBefore, notAfter))
            info.set("issuer", CertificateIssuerName(name))
            info.set("extensions", ext)
            val cert = X509CertImpl(info)
            cert.sign(k, algo)
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
