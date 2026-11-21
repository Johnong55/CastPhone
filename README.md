# NewCast — Màn hình phụ không dây 60fps tối giản cho Google Pixel

Ứng dụng Android Native (**Kotlin + Jetpack Compose + Hardware MediaCodec**) biến 1 chiếc Pixel (ví dụ **Pixel 6 Pro**) thành **màn hình phụ không dây siêu mượt (~40–80ms, 60fps)** cho chiếc Pixel còn lại (**Pixel 8**) khi quay phim / chụp ảnh bằng **app Pixel Camera gốc** (giữ trọn HDR+, Night Sight, 4K60, Cinematic Blur).

## Tối ưu chống lag & tiết kiệm pin

- **Zero-Copy Hardware Pipeline**: `MediaProjection` → `VirtualDisplay` → Hardware `MediaCodec` (`KEY_LOW_LATENCY = 1`, `KEY_MAX_B_FRAMES = 0`) → **UDP Low-Latency (DSCP EF QoS)** → **Async Event-Driven `MediaCodec.Callback`** → `SurfaceView` trực tiếp qua Hardware Composer (không tốn GPU compositing, không vòng lặp polling 15ms, 0 CPU wakeup khi màn hình tĩnh).
- **Zero-Allocation Hot Loop & Micro-Pacing**: Tái sử dụng 1 `DatagramPacket` duy nhất + cache sẵn `InetAddress` trên thread ưu tiên `THREAD_PRIORITY_URGENT_DISPLAY` (triệt tiêu ~1.000 cấp phát đối tượng/giây & giật GC), kèm micro-pacing nhẹ 0.1ms cho keyframe lớn để không tràn hàng đợi Wi-Fi TX.
- **Tự nghỉ phần cứng khi chờ kết nối (Idle Suspend)**: Khi Máy Quay chưa có máy phụ kết nối, encoder phần cứng được tạm dừng bằng `PARAMETER_KEY_SUSPEND`, tự nhả `WifiLock` & `WakeLock`, chỉ kích hoạt lại trong < 5ms khi máy phụ kết nối vào.
- **Giảm tải khi khung hình tĩnh & đang phát**:
  - `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 500ms` (giảm ~80% tải mã hóa & phát sóng Wi-Fi khi khung hình đứng yên) và chu kỳ `I-frame = 2s` kết hợp `REQUEST_IDR` tức thì khi rớt gói.
  - Tự động tạm dừng gói tin broadcast dò tìm (Discovery Beacon) trong lúc đang stream video.
  - Tạm dừng quét trạng thái mạng khi app chạy nền (lúc đang mở Pixel Camera) hoặc khi màn hình phụ đang xem trực tiếp.
- **Giao diện Monochrome OLED `#000000`**: Tối giản tuyệt đối đen-trắng-xám, tiết kiệm pin màn hình OLED trên Pixel 6 Pro & Pixel 8.

## Tương tác từ xa trên Pixel Camera gốc

1. **Qua Hotspot/Wi-Fi (Accessibility Service)**: Bấm nút chụp/quay trực tiếp, chạm 0ms lấy nét / chọn nút, giữ lâu khóa nét AF/AE, vuốt chuyển chế độ, bấm Back / Home từ xa.
2. **Bluetooth HID Shutter (tùy chọn)**: Giả lập nút chụp Volume-Up chuẩn Bluetooth — dùng trực tiếp với Pixel Camera gốc mà không cần bật Trợ năng.

## Cài đặt & Build

```powershell
# Build APK debug
.\gradlew.bat assembleDebug

# Chạy unit tests
.\gradlew.bat testDebugUnitTest

# Cài lên Pixel qua USB (khuyên dùng adb install để Android 14/15 không khóa quyền Trợ năng)
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
