# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SmitGate (also referred to as Smoothgate) is an advertising management platform that connects ad accounts (Facebook/Meta Ads, Google Ads) with POS order data (Pancake POS) to calculate attribution and ad performance metrics (ROAS, CPA, CTR). The UI is in Vietnamese.

## Build & Run Commands

### Backend (Spring Boot 3.2, Java 17)
```bash
cd backend
mvn spring-boot:run                    # Run with default (MySQL) profile
mvn clean package -DskipTests          # Build JAR without tests
mvn test                               # Run all tests
mvn test -Dtest=ClassName              # Run a single test class
mvn test -Dtest=ClassName#methodName   # Run a single test method
```

### Frontend (React 18 + Vite)
```bash
cd frontend
npm install
npm run dev       # Dev server on http://localhost:3000
npm run build     # Production build to dist/
```

### Docker (full stack)
```bash
docker compose up -d    # MySQL + backend + frontend (nginx on port 80)
```

## Architecture

### Two-tier monorepo
- `backend/` — Spring Boot REST API (port 8080)
- `frontend/` — React SPA with Vite (port 3000 in dev, nginx on port 80 in Docker)

### Backend package layout (`com.smitgate.*`)
- `auth/` — JWT authentication (login, register, change-password), `CustomUserDetailsService`
- `config/` — Spring Security (`JwtAuthenticationFilter`, `JwtTokenProvider`, `SecurityConfig`), Redis cache, HikariCP tuning, `DataInitializer` for seed data
- `connector/ads/` — Meta Ads OAuth + API integration (`MetaAdsConnector`, `AdsConnector` interface), ad account/campaign/metrics entities
- `connector/pos/` — Pancake POS integration (`PancakeConnector`), order entity, `OrderStatusClassifier`
- `attribution/` — `AttributionEngine` matches POS orders to ad campaigns
- `report/` — `ReportService`/`ReportController` aggregate dashboard data (overview, campaign perf, funnel, account spend)
- `datasource/` — Multi-source abstraction (`DataSource` entity with type `META_ADS` or `PANCAKE_POS`), manages connection lifecycle and sync state
- `sync/` — `SyncScheduler` runs periodic jobs (orders every 15min, ads every 30min, attribution every 20min); `OpsController` for manual trigger
- `tenant/` — Multi-tenant support via `Tenant` entity; all data is scoped by `tenantId`
- `common/` — `EncryptionUtil` (AES-256-GCM for stored tokens), `GlobalExceptionHandler`

### Frontend structure (`frontend/src/`)
- `services/api.js` — Axios client; all API calls go through `/api` prefix (Vite proxies to backend in dev)
- `store/authStore.js` — Zustand store with `persist` middleware for JWT token
- `pages/` — Dashboard, Campaigns, AdAccounts, Orders, ConnectAds, ConnectPoscake, Settings
- `components/` — Layout (sidebar + header + outlet), ErrorBoundary

### Key architectural patterns
- **Multi-tenant**: Every entity is scoped by `tenantId`; auth resolves tenant from the JWT user
- **DataSource abstraction**: External connections (Meta Ads, Pancake POS) are managed as `DataSource` entities with lifecycle states (ACTIVE, ERROR, INACTIVE)
- **Dual DB support**: MySQL for local dev (`application.yml`), PostgreSQL/Supabase for production (`application-prod.yml`, activated via `SPRING_PROFILES_ACTIVE=prod`)
- **Flyway migrations**: Separate migration paths — `db/migration/mysql/` and `db/migration/postgresql/`; versions are not aligned between the two
- **Redis caching (prod only)**: Dev uses Spring's simple in-memory cache; prod uses Redis (Upstash) with `RedisCacheConfig` and `CacheInvalidationService`
- **Spring Retry**: `@Retryable` on auth service for transient Supabase cold-start connection failures
- **Scheduled sync with cache eviction cooldown**: `SyncScheduler` evicts report caches after data changes, but rate-limits eviction via `reportCacheEvictMinIntervalMs`

## Environment & Configuration

- Default dev DB: MySQL on `localhost:3306/smitgate` (user `root`)
- Env vars for secrets: `DB_PASSWORD`, `JWT_SECRET`, `ENCRYPTION_SECRET`, `FB_APP_ID`, `FB_APP_SECRET`, `FB_REDIRECT_URI`
- Production activates via `SPRING_PROFILES_ACTIVE=prod` (PostgreSQL, Redis, tuned HikariCP)
- Frontend proxy: Vite dev server proxies `/api` requests to `http://localhost:8080`

## API Auth

All endpoints require JWT Bearer token except:
- `POST /api/auth/login`, `POST /api/auth/register`
- `GET /api/ops/health`
- OAuth callbacks (`/api/integrations/meta/oauth/callback`, `/api/integrations/google/oauth/callback`)
