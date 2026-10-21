# Granter
Chọn một ứng dụng, bấm một nút, cấp toàn bộ quyền qua Shizuku.

## Cách dùng
1. Cài và khởi động Shizuku (Wireless debugging).
2. Mở Granter, cho phép Shizuku, chọn app, bấm "CẤP TẤT CẢ QUYỀN".

## Build bằng GitHub Actions
Thêm Secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
Tạo keystore: keytool -genkey -v -keystore release.jks -alias key -keyalg RSA -keysize 2048 -validity 10000
Mã hóa: base64 -w0 release.jks
Phát hành: git tag v1.0 && git push origin v1.0
