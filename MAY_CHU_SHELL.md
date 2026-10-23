# Máy chủ shell của HH tool

- `ShellServer.java`: chạy bằng quyền shell qua `app_process`, chỉ nghe 127.0.0.1, bắt tay HMAC hai chiều bằng token bí mật.
- `HhServer.kt`: phía app (khởi động, gọi lệnh, dừng).
- Bật bằng nút "Máy chủ shell (bật/tắt)": lần đầu cần Shizuku hoặc ADB đang kết nối. Sau đó có thể tắt gỡ lỗi USB/không dây.
- Máy chủ mất khi khởi động lại máy (cần bật lại bằng ADB/Shizuku).
- Nhật ký lỗi khởi động: /data/local/tmp/hhtool_server.log
