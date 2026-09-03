<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="NuvioTV" width="300" />
  <br />
  <br />

  <p>
    <strong>NuvioTV Enhanced Edition</strong> — Bản fork tổng hợp từ nhiều nguồn tối ưu giao diện và hiệu năng cho Android TV.
    <br />
    A unified, performance-tuned Android TV fork focusing on interface refinement, low-RAM stability, and snappy navigation.
  </p>

  <p>
    <a href="https://github.com/9000000/NuvioTV/releases/latest">Tải bản phát hành mới nhất (Releases)</a> ·
    <a href="#tối-ưu-nổi-bật">Các tối ưu nổi bật</a> ·
    <a href="#hướng-dẫn-cài-đặt">Cài đặt</a> ·
    <a href="#build-từ-mã-nguồn">Build từ nguồn</a>
  </p>

</div>

---

## 🌟 Giới thiệu về dự án (About This Fork)

Dự án này là **bản fork tùy biến của NuvioTV**, được **tổng hợp và chọn lọc từ nhiều nguồn mã nguồn mở chất lượng cao** (bao gồm [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) chính thức, các tối ưu từ [hackerslash/NuvioTV-Lite](https://github.com/hackerslash/NuvioTV-Lite) và các đóng góp giá trị từ cộng đồng).

Mục tiêu trọng tâm của bản fork:
- **Tối ưu hóa giao diện (UI/UX)**: Đem lại giao diện mượt mà, trực quan, hỗ trợ đa dạng phong cách hiển thị và tối ưu hóa điều hướng bằng remote trên Android TV.
- **Tối ưu hóa hiệu năng & bộ nhớ (Performance & Memory Tuning)**: Thích ứng thông minh với cả các thiết bị Android TV cấu hình thấp (Low-RAM ≤ 2GB) như Mi Box, Fire TV Stick, Chromecast HD, Onn Box, loại bỏ triệt để hiện tượng tràn RAM và giật lag.
- **Khởi động siêu tốc**: Render giao diện ngay tức thì khi mở app (0s delay), không bị treo chờ mạng.
- **Tích hợp cập nhật OTA tự động**: Hỗ trợ nâng cấp trực tiếp ngay trong ứng dụng qua GitHub Releases với chữ ký số bền vững.

---

## ⚡ Các Tối Ưu Nổi Bật (Key Optimizations)

### 1. 🎨 Tối ưu hóa Giao diện & Trải nghiệm (UI/UX & Jetpack Compose)
* **Đa dạng bố cục Trang chủ**: Hỗ trợ linh hoạt các biến thể giao diện trang chủ (**Modern Home**, **Classic Home**, **Grid Home**) cùng hệ thống cài đặt dạng module tiện lợi.
* **Chuyển cảnh tức thì (Snappy Navigation)**: Rút ngắn thời gian chuyển màn hình, đem lại cảm giác phản hồi nhanh nhạy, dứt khoát trên điều khiển remote.
* **Giữ Focus Remote ổn định (Stable List Keys)**: Khóa danh sách ổn định theo định danh mục, ngăn ngừa hiện tượng mất focus hoặc giật nảy thẻ khi danh sách cập nhật hay tải thêm dữ liệu.
* **Tối ưu hóa Recomposition**:
  * Lưu cache các gradient đổ bóng (`drawWithCache`, `remember`) thay vì cấp phát lại trên từng khung hình.
  * Ghim cố định kích thước giải mã logo hero phim theo độ phân giải màn hình, chống re-decode liên tục khi animation co giãn.
  * Tùy chọn giải mã hình ảnh RGB565 giúp tiết kiệm 50% RAM bộ đệm poster mà vẫn giữ chất lượng hình ảnh sắc nét.

### 2. 🧠 Phân tầng Bộ nhớ Thông minh (Physical RAM Tiering)
* **Single Source of Truth (`DeviceMemoryTier`)**: Nhận diện phần cứng dựa trên dung lượng RAM vật lý thực tế qua `/proc/meminfo` và `ActivityManager` thay vì dựa vào Heap Size giả định.
* **Khống chế trần bộ đệm an toàn**: Đặt ngưỡng an toàn cho Exoplayer trên các thiết bị Low-RAM, ngăn chặn Linux Kernel Low Memory Killer (LMK) tắt ứng dụng đột ngột khi mở nội dung nặng.
* **Giới hạn kết nối song song an toàn (`clampParallel`)**: Bảo vệ bộ nhớ khi bật chế độ Performance Mode trên các thiết bị yếu.

### 3. 🚀 Khởi động Nhanh & Tối ưu Mạng (Fast Cold-Start & Network Concurrency)
* **Khởi động không độ trễ (`AuthManager`)**: Xuất session cục bộ ngay lập tức để dựng UI ngay khi mở app, tiến hành xác thực nền trong background mà không làm đóng băng màn hình khởi động.
* **Giải phóng Main Thread (`SupabaseModule`)**: Loại bỏ `runBlocking` đồng bộ lúc khởi tạo client, giúp thời gian cold-start nhanh hơn rõ rệt.
* **Kiểm soát cào nguồn addon (`Stream Fetch Concurrency`)**: Sử dụng Semaphore giới hạn số lượng addon được cào cùng lúc (tối đa 3 trên Low-RAM), chống tràn RAM và sập app khi cài đặt nhiều nguồn phim.
* **Bộ nhớ đệm Catalog & Chống trùng lặp**: Cache danh mục in-memory với TTL 5 phút; tự động gộp các request trùng lặp URL nhằm tiết kiệm băng thông và tăng tốc mở trang.

### 4. 🎬 Nâng cấp Trình phát Video (Player Buffers & Post-Play)
* **Cơ chế Buffer động thông minh**: Tính toán dung lượng đệm mục tiêu (15s – 40s) linh hoạt theo ngân sách RAM thực tế, giải quyết triệt để lỗi đứng hình khi xem các phim bitrate cao (4K Remux).
* **Gợi ý sau phát mượt mà (Post-Play Recommendations)**: Trên thiết bị Low-RAM, app chỉ nạp chi tiết cho thẻ đang hiển thị thay vì truy vấn ồ ạt 4 ứng viên cùng lúc trong khi luồng giải mã 4K đang chạy.
* **HUD Thống kê nhẹ nhàng**: Đọc trực tiếp dung lượng RAM đã cache, loại bỏ việc truy vấn `ActivityManager` liên tục mỗi giây.

---

## 📥 Hướng Dẫn Cài Đặt (Installation)

### Tải tệp APK cài đặt cho Android TV
Truy cập trang **[GitHub Releases](https://github.com/9000000/NuvioTV/releases/latest)** để tải phiên bản APK mới nhất phù hợp với thiết bị của bạn:

* **`armeabi-v7a`**: Phổ biến nhất cho hầu hết các Android TV Box và TV Stick 32-bit (Mi Box, Fire TV Stick, v.v.).
* **`arm64-v8a`**: Dành cho các thiết bị Android TV 64-bit hiện đại.
* **`universal`**: Bản tương thích mọi kiến trúc vi xử lý.

> [!TIP]
> Ứng dụng đã được tích hợp **trình cập nhật tự động (In-app Updater)** với chữ ký số đồng nhất. Sau khi cài đặt lần đầu, các bản cập nhật mới sẽ được thông báo và nâng cấp liền mạch ngay bên trong ứng dụng mà không cần cài đặt lại thủ công.

---

## 🛠️ Build Từ Mã Nguồn (Build from Source)

Yêu cầu môi trường: **Android Studio**, **JDK 17/21** và **Android SDK**.

```bash
# Clone repository
git clone https://github.com/9000000/NuvioTV.git
cd NuvioTV

# Build bản Full Debug
./gradlew :app:assembleFullDebug

# Build bản Full Release
./gradlew :app:assembleFullRelease
```

---

## 🙏 Lời Cảm Ơn & Nguồn Tham Khảo (Credits & Acknowledgments)

Dự án được xây dựng dựa trên sự đóng góp to lớn từ cộng đồng mã nguồn mở:
* Dự án gốc **[NuvioTV](https://github.com/NuvioMedia/NuvioTV)** bởi nhóm phát triển NuvioMedia và các cộng tác viên.
* Nhánh tối ưu hóa bộ nhớ **[NuvioTV-Lite](https://github.com/hackerslash/NuvioTV-Lite)** bởi `@hackerslash`.
* Cùng các đóng góp, bản dịch và cải tiến từ các nhà phát triển trong cộng đồng Stremio / NuvioTV.

---

## 📄 Bản quyền (License)

Dự án được phát hành theo giấy phép [GNU General Public License v3.0](./LICENSE).
