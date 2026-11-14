# Milano Launcher (Android TV / Google TV)

App launcher riêng cho box Onn 4K Pro: mở trang launcher web toàn màn hình, làm màn hình Home.

**Tải APK mới nhất:**
https://github.com/john2556-ship-it/milano-launcher-apk/releases/latest/download/MilanoLauncher.apk

## Có gì
- Mở `launcher-url.txt` toàn màn hình, mất mạng thì dùng bản đi kèm trong app
- Cho trang dùng micro (Hỏi AI) và vị trí (thời tiết), không cần Fully PLUS
- Đăng ký làm Home: dùng TiviKit → Quản lý launcher → chọn Milano Launcher
- Cầu nối JavaScript `MilanoApp`: `openApp(pkg)`, `openIntent(uri)`, `speak(text, lang)`, `stopSpeak()`, `getApps()`, `version()`, `reload()`
- Nút Back không thoát app, chỉ đóng bảng đang mở

## Build
GitHub Actions tự build mỗi lần push lên `main`, ký bằng `keystore/milano-release.p12`
(mật khẩu trong secret `KEYSTORE_PASS`) và đăng lên Releases.

Đổi địa chỉ trang: sửa file `launcher-url.txt` rồi commit.
