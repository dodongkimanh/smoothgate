# SmitGate — Hướng dẫn Deploy lên Production

## Kiến trúc

| Thành phần | Nền tảng | Gói miễn phí |
|---|---|---|
| Frontend (React) | **Vercel** | Free forever |
| Backend (Spring Boot) | **Render** | Free (ngủ sau 15 phút không dùng) |
| Database | **Supabase** | Free 500MB PostgreSQL |
| Cron job | Render Cron / GitHub Actions | Free |

---

## Bước 1 — Supabase (PostgreSQL)

1. Vào [supabase.com](https://supabase.com) → **New Project**
2. Đặt tên dự án, chọn region **Southeast Asia (Singapore)**
3. Sau khi tạo xong, vào **Project Settings → Database → Connection string**
4. ⚠️ **QUAN TRỌNG**: Chọn **Method = Session Pooler** (KHÔNG dùng Direct Connection — Render free không hỗ trợ IPv6!)
5. Copy connection string dạng (port **5432** trên pooler host):
   ```
   postgresql://postgres.PROJECT_REF:[PASSWORD]@aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres
   ```
6. Chuyển sang JDBC format để nhập vào Render:
   ```
   jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?user=postgres.PROJECT_REF&password=YOUR_PASSWORD&sslmode=require
   ```
   > Ví dụ: nếu project ref là `hvmtfbjlkztpnkdfeolw` và password là `YourPass`:
   > `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?user=postgres.hvmtfbjlkztpnkdfeolw&password=YourPass&sslmode=require`

> **Flyway** tự tạo bảng khi backend khởi động lần đầu.
> Migration scripts ở `backend/src/main/resources/db/migration/postgresql/`

---

## Bước 2 — Render (Backend Java)

1. Vào [render.com](https://render.com) → **New → Web Service**
2. Kết nối GitHub repo, chọn folder `backend` (hoặc dùng `render.yaml` ở root)
3. Cấu hình:
   - **Runtime**: Docker
   - **Dockerfile path**: `./backend/Dockerfile`
   - **Root directory**: `/` (root của repo)
4. Thiết lập **Environment Variables** trong Render dashboard:

   | Key | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DATABASE_URL` | JDBC Session Pooler string (xem Bước 1) |
   | `JWT_SECRET` | Chuỗi ngẫu nhiên 64 ký tự |
   | `ENCRYPTION_SECRET` | Chuỗi ngẫu nhiên 32 ký tự |
   | `FRONTEND_URL` | `https://YOUR_FRONTEND_URL` (**phải có https://**) |
   | `CORS_ALLOWED_ORIGINS` | `https://YOUR_FRONTEND_URL` (**phải có https://**) |

   > ⚠️ `FRONTEND_URL` và `CORS_ALLOWED_ORIGINS` **phải có** `https://` — thiếu sẽ bị lỗi CORS khi frontend gọi API!

6. Deploy → Chờ vài phút → URL dạng `https://YOUR_BACKEND_URL`

> Render free plan: Service ngủ sau 15 phút không có request. Lần đầu request sau khi ngủ mất 30-60s.
> Để tránh: dùng [UptimeRobot](https://uptimerobot.com) ping `/api/ops/health` mỗi 14 phút (miễn phí).

---

## Bước 3 — Vercel (Frontend React)

1. Vào [vercel.com](https://vercel.com) → **New Project** → Import GitHub repo
2. **Framework**: Vite
3. **Root Directory**: `frontend`
4. **Build Command**: `npm run build`
5. **Output Directory**: `dist`
6. Thiết lập **Environment Variables**:

   | Key | Value |
   |---|---|
   | `VITE_API_URL` | URL backend Render (vd: `https://YOUR_BACKEND_URL`) |

7. Deploy → URL dạng `https://YOUR_FRONTEND_URL`

---

## Bước 4 — Cấu hình Facebook App cho Production

1. Vào [developers.facebook.com](https://developers.facebook.com) → App của bạn
2. **Facebook Login → Settings → Valid OAuth Redirect URIs**: Thêm
   ```
   https://YOUR_BACKEND_URL/api/integrations/meta/oauth/callback
   ```
3. Đăng nhập SmitGate production → **Cài đặt → Meta Ads** → Cập nhật:
   - App ID
   - App Secret
   - Redirect URI: `https://YOUR_BACKEND_URL/api/integrations/meta/oauth/callback`
4. **Đăng ký domains** trong Facebook App nếu dùng domain riêng

---

## Cron Job tự động đồng bộ (GitHub Actions — miễn phí)

Tạo file `.github/workflows/sync.yml`:

```yaml
name: Daily Sync
on:
  schedule:
    - cron: '0 1 * * *'  # 8:00 AM Vietnam time (UTC+7)
jobs:
  sync:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger sync
        run: |
          TOKEN=$(curl -s -X POST ${{ secrets.API_URL }}/api/auth/login \
            -H "Content-Type: application/json" \
            -d '{"email":"${{ secrets.ADMIN_EMAIL }}","password":"${{ secrets.ADMIN_PASSWORD }}"}' | jq -r '.data.token')
          curl -s -X POST ${{ secrets.API_URL }}/api/integrations/meta/sync \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            -d '{"dataSourceId": 1}'
```

Thêm GitHub Secrets: `API_URL`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`

---

## Tóm tắt Checklist Deploy

- [ ] Tạo Supabase project → copy connection string
- [ ] Push code lên GitHub (đảm bảo không commit `.env`)
- [ ] Deploy backend lên Render → set tất cả env vars
- [ ] Deploy frontend lên Vercel → set `VITE_API_URL`
- [ ] Cập nhật Facebook App Redirect URI
- [ ] Vào SmitGate prod → Cài đặt → cập nhật Meta credentials với Redirect URI prod
- [ ] Test kết nối Facebook Ads
- [ ] Cài UptimeRobot ping backend mỗi 14 phút
