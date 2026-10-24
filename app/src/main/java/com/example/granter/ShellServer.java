package com.example.granter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;

/**
 * Máy chủ shell nhỏ, chạy bằng quyền shell/system:
 *   CLASSPATH=<base.apk> app_process /system/bin com.example.granter.ShellServer \
 *       <payloadB64> <sigB64> <port>
 *
 * THAY ĐỔI SO VỚI BẢN CŨ:
 * Bản cũ nhận "token" tuỳ ý do caller chọn -> ai patch MainActivity/License cũng tự
 * sinh token rồi start server được, license coi như vô dụng với ShellServer.
 *
 * Bản này: ShellServer TỰ xác minh lại payload license (cùng định dạng, cùng
 * PUBLIC_KEY với License.kt: "v3|kid|nonce|device|fp|status|expiry|server_time"),
 * không tin bất cứ cờ "đã duyệt" nào do tiến trình gọi nó truyền sang. Chỉ khi
 * payload có chữ ký RSA hợp lệ (ký bởi khoá riêng chỉ nằm trên Apps Script) VÀ
 * status == "ACTIVE" VÀ chưa hết hạn/hết grace, server mới mở socket và nhận lệnh.
 * Patch Kotlin ở đâu cũng không tự ký được payload giả.
 *
 * Giao thức sau khi server đã mở (không đổi): bắt tay HMAC hai chiều bằng "session
 * token" = SHA-256(sigBytes) — khoá này suy ra được từ chữ ký license hợp lệ, nên
 * chỉ phía đã có license thật (đã verify được payload) mới tính ra đúng được.
 * Sau bắt tay: client gửi op(1 byte), độ dài lệnh(int), lệnh(UTF-8);
 * máy chủ trả mã(int), độ dài đầu ra(int), đầu ra.
 * op: 0 = ping (mã trả về = VERSION), 1 = chạy lệnh qua "sh -c", 2 = dừng máy chủ.
 */
public final class ShellServer {
    static final int VERSION = 1;
    static final int MAX_OUT = 1 << 20;
    static final int MAX_CMD = 64 * 1024;
    static final long EXEC_TIMEOUT_S = 60;
    static final int MAX_CONN = 16;

    // PHẢI khớp y hệt hằng số trong License.kt.
    private static final String PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArY4q5Gvmyd9wJc/byTaJJaxkYQEmrUFq2Ta/d92voB6Owdm4/nQXYXqOsiqwFftmnOms/JMRuG2fX9WLs5rKW951/1mWjVDgTMCb31e3Yrd07xsEG5Uw9L0W9GFOgf4RZsE8Nw5AikrvuVw0zOa48R6MnXbrMfG/VOa4yH23gJRdeV0/5FuGhFr1ecFdkWYDgxLnixK63pBkj5GTpYCHRhVUmiM+XvWRXX14oK2A9mkT3gpd21zZ0cEY5v4gVZxmmIIUQlz7Wll9TTz9l1KoWXFOpNRMabvPFR5whqJPmS8/2Kd94mN23zv+rYZUvGdUpLO0djM2PmDcWSCbvpx7cQIDAQAB";

    // Phải khớp GRACE_MS / CLOCK_SKEW_TOLERANCE_MS trong License.kt.
    private static final long GRACE_MS = 45L * 60 * 1000;
    private static final long CLOCK_SKEW_TOLERANCE_MS = 5L * 60 * 1000;

    private static byte[] sessionToken; // suy ra từ chữ ký license, KHÔNG do caller chọn
    private static final AtomicInteger conns = new AtomicInteger();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ShellServer <payloadB64> <sigB64> <port>");
            System.exit(2);
            return;
        }
        String payload = new String(Base64.decode(args[0], Base64.NO_WRAP), StandardCharsets.UTF_8);
        byte[] sig = Base64.decode(args[1], Base64.NO_WRAP);
        int port = Integer.parseInt(args[2]);

        if (!licenseOk(payload, sig)) {
            System.err.println("LICENSE_REJECTED");
            System.exit(3);
            return;
        }
        // Session token bắt nguồn từ chữ ký (bất khả giả mạo), không phải chuỗi caller tự đặt.
        sessionToken = MessageDigest.getInstance("SHA-256").digest(sig);

        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        while (true) {
            final Socket s = ss.accept();
            if (conns.incrementAndGet() > MAX_CONN) {
                conns.decrementAndGet();
                try { s.close(); } catch (IOException ignored) {}
                continue;
            }
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try { handle(s); } finally { conns.decrementAndGet(); }
                }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Xác minh ĐỘC LẬP payload license, giống hệt logic License.kt.local() nhánh
     * "khác boot" (ShellServer là tiến trình mới, không có elapsedRealtime cũ để
     * so sánh same-boot, nên luôn dùng nhánh wall-clock).
     *
     * payload kỳ vọng: "v3|kid|nonce|device|fp|status|expiry|server_time"
     */
    private static boolean licenseOk(String payload, byte[] sig) {
        try {
            PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.NO_WRAP)));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(pub);
            s.update(payload.getBytes(StandardCharsets.UTF_8));
            if (!s.verify(sig)) return false;
        } catch (Throwable t) {
            return false;
        }

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 8 || !"v3".equals(parts[0])) return false;
        String status = parts[5];
        String expiryS = parts[6];
        String stS = parts[7];

        if (!"ACTIVE".equals(status)) return false;

        long serverMs;
        try { serverMs = Long.parseLong(stS); } catch (NumberFormatException e) { return false; }
        long wall = System.currentTimeMillis();
        if (wall < serverMs - CLOCK_SKEW_TOLERANCE_MS) return false;
        long age = wall - serverMs;
        if (age > GRACE_MS) return false;

        long expiry;
        try { expiry = Long.parseLong(expiryS); } catch (NumberFormatException e) { expiry = 0L; }
        if (expiry > 0 && serverMs + age > expiry) return false;

        return true;
    }

    private static byte[] mac(byte[] ns, byte[] nc, byte tag) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(sessionToken, "HmacSHA256"));
        m.update(ns);
        m.update(nc);
        m.update(tag);
        return m.doFinal();
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            // Bắt tay: server gửi nonce, client trả nonce + HMAC, server trả HMAC của mình.
            byte[] ns = new byte[16];
            new SecureRandom().nextBytes(ns);
            out.write(ns);
            out.flush();
            byte[] nc = new byte[16];
            in.readFully(nc);
            byte[] m1 = new byte[32];
            in.readFully(m1);
            if (!MessageDigest.isEqual(m1, mac(ns, nc, (byte) 1))) {
                Thread.sleep(300);
                s.close();
                return;
            }
            out.write(mac(ns, nc, (byte) 2));
            out.flush();

            int op = in.readUnsignedByte();
            int n = in.readInt();
            if (n < 0 || n > MAX_CMD) { s.close(); return; }
            byte[] cb = new byte[n];
            in.readFully(cb);
            String cmd = new String(cb, StandardCharsets.UTF_8);

            if (op == 0) {
                reply(out, VERSION, new byte[0]);
            } else if (op == 1) {
                execAndReply(cmd, out);
            } else if (op == 2) {
                reply(out, 0, new byte[0]);
                s.close();
                System.exit(0);
            } else {
                reply(out, -1, "unknown op".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void reply(DataOutputStream out, int code, byte[] data) throws IOException {
        out.writeInt(code);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private static void execAndReply(String cmd, DataOutputStream out) throws Exception {
        final Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        try { p.getOutputStream().close(); } catch (IOException ignored) {}
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final InputStream is = p.getInputStream();
        Thread rd = new Thread(new Runnable() {
            @Override public void run() {
                byte[] b = new byte[4096];
                int r;
                try {
                    while ((r = is.read(b)) > 0) {
                        if (bo.size() < MAX_OUT) bo.write(b, 0, Math.min(r, MAX_OUT - bo.size()));
                    }
                } catch (IOException ignored) {}
            }
        });
        rd.setDaemon(true);
        rd.start();
        int code;
        boolean done = p.waitFor(EXEC_TIMEOUT_S, TimeUnit.SECONDS);
        if (done) {
            rd.join(1500);
            code = p.exitValue();
        } else {
            p.destroyForcibly();
            rd.join(500);
            code = -2;
            byte[] t = "\n[quá thời gian, đã dừng lệnh]".getBytes(StandardCharsets.UTF_8);
            bo.write(t, 0, t.length);
        }
        reply(out, code, bo.toByteArray());
    }
}
