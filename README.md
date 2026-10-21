# HH tool
Chọn một ứng dụng, bấm một nút, cấp toàn bộ quyền. Dùng Shizuku nếu đang chạy, nếu không thì dùng ADB tích hợp (Wireless debugging).

## Dùng ADB tích hợp (không cần Shizuku)
1. Bật Tùy chọn nhà phát triển > Gỡ lỗi không dây (cần Wi-Fi).
2. Mở HH tool, bấm GHÉP NỐI ADB, vào "Ghép nối thiết bị bằng mã ghép nối".
3. Kéo thông báo xuống, bấm "Nhập mã ghép nối", gõ mã 6 số.
4. Bấm KẾT NỐI ADB rồi dùng như bình thường.

## Build bằng GitHub Actions
Thêm Secrets: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (không bắt buộc, không có thì build bản debug).
Phát hành: git tag v1.1 && git push origin v1.1
