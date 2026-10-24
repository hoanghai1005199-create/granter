# ===== granter / HH tool — rule R8 cho bản release =====
# Nguyên tắc: KHÔNG giữ cả gói com.example.granter.** — để R8 đổi tên hầu hết class/method.
# Chỉ giữ đúng những gì bị gọi bằng tên chuỗi (JNI, app_process) hoặc bằng reflection.

# --- JNI: license.cpp map cứng tên gói/class/method vào symbol Java_com_example_granter_License_nativeVerify ---
# Đổi tên class/method này mà không build lại .so sẽ làm UnsatisfiedLinkError.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.example.granter.License {
    native <methods>;
}

# --- Khởi động bằng "app_process ... <tên class đầy đủ>" (ShellServer.kt/HhServer.kt gọi bằng chuỗi) ---
-keep class com.example.granter.ShellServer {
    public static void main(java.lang.String[]);
}
-keep class com.example.granter.ChannelFixer {
    public static void main(java.lang.String[]);
}

# --- Shizuku: API được gọi cả trực tiếp lẫn qua reflection (MainActivity dùng getDeclaredMethod
# cho Shizuku.newProcess) nên không chỉ dựa vào consumer-rules của thư viện ---
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# --- libadb-android (pairing/ADB) + SPAKE2 (JNI libspake2.so) ---
-keep class io.github.muntashirakon.** { *; }
-dontwarn io.github.muntashirakon.**

# --- Conscrypt (TLS cho kết nối ADB) ---
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# Các thư viện trên kéo theo vài annotation/optional-dep không có mặt lúc build release;
# -dontwarn để R8 không chặn build vì thiếu class không thật sự được dùng lúc chạy.
-dontwarn org.bouncycastle.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.**

# --- org.json: class hệ thống Android (android.jar), KHÔNG nằm trong dex app nên R8 không đổi
# tên được — không cần keep, chỉ cần -dontwarn phòng khi R8 vẫn quét tới do import tĩnh.
-dontwarn org.json.**
