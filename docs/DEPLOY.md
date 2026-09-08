# SmitGate — Hướng dẫn Deploy lên Production

## Kiến trúc

| Thành phần | Nền tảng |
|---|---|
| Frontend (React) | **Vercel** |
| Backend (Spring Boot) | **Docker trên Synology NAS** (self-hosted) |
| Database (PostgreSQL) | **Docker trên Synology NAS** (self-hosted) |
| Public access cho backend | **Cloudflare Tunnel** (không mở port ra Internet) |

Toàn bộ backend + database chạy trong `docker/Smoothgate/` trên NAS, định nghĩa trong một `docker-compose.yml` gồm 3 service: `smoothgate-db` (Postgres), `smoothgate-backend` (build từ `backend/Dockerfile`), `cloudflared-smoothgate` (tunnel connector).

---

## Bước 1 — Postgres trên NAS

1. Trên NAS, tạo thư mục `docker/Smoothgate/pgdata`.
2. Thêm service `smoothgate-db` (image `postgres:17`) vào `docker-compose.yml`, đặt `POSTGRES_PASSWORD` trong file `.env` cùng thư mục (không commit).
3. `docker compose up -d smoothgate-db`.

## Bước 2 — Backend (Spring Boot) trên NAS

1. Copy thư mục `backend/` (source code) lên NAS vào `docker/Smoothgate/backend/`.
2. Tạo file `backend.env` (không commit) chứa các biến môi trường — xem danh sách đầy đủ trong `application-prod.yml` và `application.yml` (mọi `${VAR:default}`), quan trọng nhất:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` → trỏ vào `smoothgate-db` cùng docker network
   - `DB_SSL_MODE=disable` (Postgres tự host không bật TLS)
   - `SPRING_CACHE_TYPE=simple` + `SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration` (production hiện không dùng Redis)
   - `JWT_SECRET`, `ENCRYPTION_SECRET` — **phải giữ cố định một khi đã có dữ liệu**, đổi sẽ làm hỏng token OAuth đã lưu và JWT hiện có
   - `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS` → URL Vercel
   - `FB_REDIRECT_URI` → URL public của backend (xem Bước 3)
   - `ANTHROPIC_API_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` nếu bật AI agent (`APP_AGENT_ENABLED=true`)
3. `docker compose up -d --build smoothgate-backend`.
4. Kiểm tra: `curl http://localhost:8081/api/ops/health` (hoặc port map tương ứng) → `{"status":"UP"}`.

## Bước 3 — Cloudflare Tunnel (public access cho backend)

1. Cloudflare Zero Trust dashboard (`one.dash.cloudflare.com`) → **Networks → Tunnels → Create a tunnel** → chọn **Docker**, copy token.
2. Thêm service `cloudflared-smoothgate` vào `docker-compose.yml`:
   ```yaml
   cloudflared-smoothgate:
     image: cloudflare/cloudflared:latest
     command: tunnel --no-autoupdate run --token ${CLOUDFLARE_TUNNEL_TOKEN}
   ```
3. Trong tunnel vừa tạo → tab **Published application routes** → **Add** → chọn domain đã có sẵn trong Cloudflare (không cần mua domain mới nếu đã có domain khác trong cùng account) → Service: `HTTP` → `smoothgate-backend:8080`.
4. Domain hiện tại đang dùng: `smoothgate-backend.kimanh-media.cloud`.

## Bước 4 — Vercel (Frontend React)

1. Vào [vercel.com](https://vercel.com) → **New Project** → Import GitHub repo
2. **Framework**: Vite, **Root Directory**: `frontend`, **Build Command**: `npm run build`, **Output Directory**: `dist`
3. Không cần set `VITE_API_URL` — routing `/api/*` được cấu hình qua rewrite trong `frontend/vercel.json`, trỏ thẳng vào URL Cloudflare Tunnel ở Bước 3. Sửa file đó nếu domain backend thay đổi.
4. Deploy → URL dạng `https://YOUR_FRONTEND_URL.vercel.app`

---

## Bước 5 — Cấu hình Facebook App cho Production

1. Vào [developers.facebook.com](https://developers.facebook.com) → App của bạn
2. **Facebook Login → Settings → Valid OAuth Redirect URIs**: thêm URL backend qua Cloudflare Tunnel:
   ```
   https://smoothgate-backend.kimanh-media.cloud/api/integrations/meta/oauth/callback
   ```
3. Đăng nhập SmitGate production → **Cài đặt → Meta Ads** → cập nhật App ID / App Secret / Redirect URI tương ứng.

---

## Backup & khôi phục dữ liệu

- Dump định kỳ: chạy `pg_dump` (custom format `-Fc`) từ container tạm, lưu vào `docker/Smoothgate/backups/` trên NAS.
- Khôi phục: `pg_restore` vào `smoothgate-db` với `--no-owner --no-privileges`.

## Tóm tắt Checklist Deploy

- [ ] Tạo Postgres container trên NAS, lưu password vào `.env`
- [ ] Copy backend source lên NAS, tạo `backend.env` với đầy đủ secrets (đặc biệt `JWT_SECRET`/`ENCRYPTION_SECRET` giữ cố định)
- [ ] `docker compose up -d --build` toàn bộ stack
- [ ] Tạo Cloudflare Tunnel, publish route trỏ vào `smoothgate-backend:8080`
- [ ] Deploy frontend lên Vercel, kiểm tra `frontend/vercel.json` trỏ đúng domain backend
- [ ] Cập nhật Facebook App Redirect URI
- [ ] Test đăng nhập + kết nối Facebook Ads / Pancake POS
