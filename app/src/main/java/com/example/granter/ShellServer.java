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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Máy chủ shell nhỏ (giống ý tưởng của Shizuku). Chạy bằng quyền shell:
 *   CLASSPATH=<base.apk> app_process /system/bin com.example.granter.ShellServer <token> <port>
 * Chỉ nghe ở 127.0.0.1. Mỗi kết nối phải qua bắt tay HMAC hai chiều bằng token bí mật
 * (token chỉ có app biết), nên app khác không dùng được và cũng không giả mạo được máy chủ.
 *
 * Giao thức (sau bắt tay): client gửi op(1 byte), độ dài lệnh(int), lệnh(UTF-8);
 * máy chủ trả mã(int), độ dài đầu ra(int), đầu ra.
 * op: 0 = ping (mã trả về = VERSION), 1 = chạy lệnh qua "sh -c", 2 = dừng máy chủ.
 */
public final class ShellServer {
    static final int VERSION = 1;
    static final int MAX_OUT = 1 << 20;
    static final int MAX_CMD = 64 * 1024;
    static final long EXEC_TIMEOUT_S = 60;
    static final int MAX_CONN = 16;

    private static byte[] token;
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
        token = args[0].getBytes(StandardCharsets.UTF_8);
        int port = Integer.parseInt(args[1]);
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

    private static byte[] mac(byte[] ns, byte[] nc, byte tag) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(token, "HmacSHA256"));
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
