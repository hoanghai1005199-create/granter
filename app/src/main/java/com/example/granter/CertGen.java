package com.example.granter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Tạo chứng chỉ X.509 tự ký (RSA + SHA256) bằng DER thuần, không cần thư viện ngoài. */
public final class CertGen {
    private CertGen() {}

    public static X509Certificate generate(KeyPair kp, String cn) throws Exception {
        long now = System.currentTimeMillis();
        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}),
                tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(utc(new Date(now - 86400000L)),
                utc(new Date(now + 86400000L * 3650)));
        byte[] serial = tlv(0x02, new BigInteger(63, new java.security.SecureRandom())
                .add(BigInteger.ONE).toByteArray());
        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[]{0x02})),   // version v3
                serial, sigAlg, name, validity, name,
                kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] utc(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] a) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(a[0] * 40 + a[1]);
        for (int i = 2; i < a.length; i++) {
            int v = a[i];
            int n = 0;
            byte[] tmp = new byte[5];
            do { tmp[n++] = (byte) (v & 0x7F); v >>>= 7; } while (v > 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] seq(byte[]... parts) { return tlv(0x30, cat(parts)); }
    private static byte[] set(byte[]... parts) { return tlv(0x31, cat(parts)); }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = body.length;
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81); o.write(len);
        } else {
            o.write(0x82); o.write(len >> 8); o.write(len & 0xFF);
        }
        o.write(body, 0, len);
        return o.toByteArray();
    }
}
