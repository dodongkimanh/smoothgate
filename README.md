# SmitGate - Advertising Management Platform

## 🚀 Tổng quan

SmitGate là nền tảng quản lý quảng cáo giúp doanh nghiệp:
- **Kết nối tài khoản quảng cáo** (Facebook Ads, Google Ads)
- **Đồng bộ đơn hàng** từ Poscake tự động
- **Phân tích hiệu quả** quảng cáo (ROAS, CPA, CTR)
- **Dashboard** trực quan với dữ liệu real-time

## 📁 Cấu trúc dự án

```
SmitGate/
├── backend/                    # Spring Boot API
│   ├── src/main/java/com/smitgate/
│   │   ├── config/            # Security, WebClient configs
│   │   ├── controller/        # REST API controllers
│   │   ├── dto/               # Request/Response DTOs
│   │   ├── entity/            # JPA entities
│   │   ├── exception/         # Global exception handler
│   │   ├── repository/        # Spring Data repositories
│   │   ├── scheduler/         # Cron jobs
│   │   ├── security/          # JWT authentication
│   │   ├── service/           # Business logic
│   │   └── util/              # Encryption utilities
│   └── src/main/resources/
│       └── application.yml    # App configuration
├── frontend/                   # React (Vite) Dashboard
│   ├── src/
│   │   ├── components/        # Layout, Sidebar, Header
│   │   ├── pages/             # Dashboard, Campaigns, Orders...
│   │   ├── services/          # API client (axios)
│   │   └── store/             # Zustand state management
│   └── index.html
├── docker-compose.yml          # Docker orchestration
└── .env.example               # Environment template
```

## 🛠 Yêu cầu

- **Java 17+** (Spring Boot 3.2)
- **Node.js 18+** (React + Vite)
- **MySQL 8.0**
- **Docker** (optional)

## ⚡ Khởi chạy nhanh

### Option 1: Docker Compose

```bash
# Clone & config
cp .env.example .env
# Sửa .env với credentials thực

# Chạy toàn bộ
docker compose up -d
```

Truy cập: http://localhost

### Option 2: Development Mode

**Backend:**
```bash
cd backend
mvn spring-boot:run
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev
```

Truy cập: http://localhost:3000

## 🔌 API Endpoints

| Method | Endpoint | Mô tả |
|--------|----------|--------|
| POST | /api/auth/register | Đăng ký |
| POST | /api/auth/login | Đăng nhập |
| GET | /api/dashboard | Tổng quan dashboard |
| GET | /api/campaigns | Danh sách chiến dịch |
| GET | /api/ads/accounts | Tài khoản QC |
| GET | /api/ads/facebook/auth-url | URL OAuth Facebook |
| POST | /api/ads/facebook/connect | Kết nối Facebook |
| POST | /api/poscake/connect | Kết nối Poscake |
| GET | /api/orders | Danh sách đơn hàng |

## 📊 Tính năng chính

### 1. Dashboard
- Tổng chi phí, đơn hàng, doanh thu
- ROAS, CPA, CTR
- Biểu đồ chi phí/doanh thu theo ngày
- Top chiến dịch hiệu quả

### 2. Quản lý quảng cáo (giống SmitGate)
- Bảng dữ liệu với đầy đủ cột
- Toolbar: search, filter, tag, export
- Phân trang, sắp xếp

### 3. Đồng bộ Poscake
- Kết nối API key
- Tự động sync đơn hàng mỗi 5 phút
- Attribution matching (UTM + Phone)

### 4. Analytics
- ROAS = Revenue / Ad Cost
- CPA = Ad Cost / Orders
- CTR = Clicks / Impressions

## 🔐 Bảo mật

- **JWT Authentication** - Token-based auth
- **AES-256 GCM Encryption** - Mã hóa access tokens
- **BCrypt** - Hash passwords
- **CORS** - Cross-origin protection
- **Input Validation** - Jakarta validation

## 🔄 Scheduled Jobs

| Job | Tần suất | Mô tả |
|-----|----------|--------|
| Ad Metrics Sync | 30 phút | Đồng bộ dữ liệu quảng cáo |
| Order Sync | 5 phút | Đồng bộ đơn hàng Poscake |
| Attribution | 10 phút | Ghép đơn hàng với chiến dịch |

## 🏗 Database Schema

```
users → ad_accounts → campaigns → ad_metrics
                                ↘ campaign_orders ← orders
                poscake_connections
                click_logs
```

## 📝 License

MIT License
