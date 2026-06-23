# SmitGate — Hướng dẫn sử dụng cho Khách hàng

## Giới thiệu

SmitGate giúp bạn:
- Kết nối tài khoản **Facebook Ads** để xem dữ liệu chiến dịch, chi tiêu, hiệu quả
- Kết nối **Pancake POS** để đồng bộ đơn hàng
- Xem báo cáo ROAS (lợi nhuận trên chi tiêu quảng cáo) theo ngày

---

## PHẦN 1 — Kết nối Facebook Ads (Meta Ads)

### Yêu cầu trước
- Tài khoản Facebook đang dùng phải là **Admin** của Business Manager / Ad Account bạn muốn theo dõi

### Bước 1.1 — Kết nối tài khoản
1. Đăng nhập SmitGate
2. Vào menu **"Kết nối QC"** (biểu tượng link ở sidebar)
3. Nhấn nút **"Kết nối"** ở thẻ Facebook Ads
4. Trình duyệt sẽ mở trang đăng nhập Facebook — **đăng nhập bằng tài khoản có quyền quản lý quảng cáo**
5. Facebook hỏi quyền (ads_read, ads_management, read_insights) — nhấn **"Tiếp tục"**
6. Hệ thống tự động chuyển về SmitGate → cửa sổ **"Chọn tài khoản quảng cáo"** hiện ra

### Bước 1.2 — Chọn tài khoản quảng cáo
1. Cửa sổ hiển thị tất cả Ad Account bạn có quyền quản lý
2. Tick chọn các tài khoản muốn theo dõi
3. Nhấn **"Xác nhận"**
4. Thẻ tài khoản quảng cáo xuất hiện ở trang **"Tài khoản QC"**

### Bước 1.3 — Đồng bộ dữ liệu
1. Vào **"Trình quản lý QC"**
2. Nhấn **"Đồng bộ FB"** (nút xanh trên cùng bên phải)
3. Dữ liệu chiến dịch 7 ngày gần nhất sẽ được tải về
4. Danh sách chiến dịch hiển thị trong tab **"Chiến dịch"**

> **Lưu ý**: Dữ liệu trong SmitGate được lấy từ Facebook API — phản ánh số liệu thực trong Ads Manager của bạn.

### Ngắt kết nối
- Vào **"Tài khoản QC"** → Nhấn **"Ngắt kết nối"** trên thẻ tài khoản

---

## PHẦN 2 — Kết nối Pancake POS

### Lấy API Key từ Pancake
1. Vào [pos.pages.fm](https://pos.pages.fm) → Đăng nhập
2. Vào **Cài đặt → Tích hợp API** (hoặc **Settings → API**)
3. Tạo API Key mới nếu chưa có
4. Copy API Key

### Kết nối trong SmitGate
1. Vào menu **"Kết nối Poscake"**
2. Dán API Key vào ô nhập
3. Nhấn **"Kiểm tra kết nối"** — hệ thống liệt kê danh sách shop
4. Tick chọn shop muốn đồng bộ đơn hàng
5. Nhấn **"Kết nối"**

### Đồng bộ đơn hàng
- Hệ thống tự đồng bộ định kỳ (nếu cron job được cài)
- Hoặc vào **"Kết nối Poscake"** → nhấn **"Đồng bộ ngay"**

### Xem đơn hàng
- Vào menu **"Đơn hàng"** để xem tất cả đơn hàng đã đồng bộ

---

## PHẦN 3 — Xem báo cáo

### Tổng quan (Dashboard)
- Tổng chi tiêu quảng cáo
- Tổng doanh thu từ đơn hàng
- ROAS (doanh thu / chi tiêu)
- Số đơn hàng
- CPO (chi phí trên mỗi đơn)

### Trình quản lý chiến dịch
| Tab | Nội dung |
|---|---|
| Chiến dịch | Danh sách campaign level với chi tiêu, impressions, clicks, CTR, CPC, ROAS |
| Nhóm quảng cáo | Đang phát triển |
| Quảng cáo | Đang phát triển |
| Bài viết | Đang phát triển |

---

## Câu hỏi thường gặp

**Q: Tại sao nhấn "Kết nối" Facebook bị lỗi "ID ứng dụng không hợp lệ"?**
> A: Cần cấu hình Facebook App ID và Secret trong **Cài đặt → Meta Ads**. Liên hệ admin hệ thống.

**Q: Dữ liệu Facebook bao lâu cập nhật một lần?**
> A: Theo lịch tự động (hàng ngày) hoặc nhấn "Đồng bộ FB" để cập nhật thủ công ngay lập tức.

**Q: Tôi có nhiều tài khoản quảng cáo, có thể theo dõi tất cả không?**
> A: Có. Khi chọn tài khoản sau bước OAuth, tick chọn nhiều tài khoản cùng lúc.

**Q: Mất kết nối Facebook thì sao?**
> A: Token Facebook có thời hạn 60 ngày. Khi hết hạn, vào "Kết nối QC" → Kết nối lại.

**Q: Dữ liệu Pancake không đồng bộ?**
> A: Kiểm tra API Key còn hiệu lực không. Vào "Kết nối Poscake" → Kiểm tra lại kết nối.
