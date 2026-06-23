-- ============================================================
-- SmitGate Database Schema - Database-First with Flyway
-- ============================================================

-- Tenants (workspaces)
CREATE TABLE tenants (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    timezone    VARCHAR(50) DEFAULT 'Asia/Ho_Chi_Minh',
    currency    VARCHAR(10) DEFAULT 'VND',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Users
CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    role          ENUM('ADMIN','ANALYST') NOT NULL DEFAULT 'ADMIN',
    status        VARCHAR(20) DEFAULT 'ACTIVE',
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_email (email),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Data Sources (unified connector config)
CREATE TABLE data_sources (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id        BIGINT NOT NULL,
    type             ENUM('PANCAKE_POS','META_ADS','GOOGLE_ADS','TIKTOK_ADS') NOT NULL,
    name             VARCHAR(255),
    status           ENUM('ACTIVE','INACTIVE','ERROR') DEFAULT 'INACTIVE',
    config_json      JSON,
    secret_encrypted TEXT,
    last_success_at  TIMESTAMP NULL,
    last_error_at    TIMESTAMP NULL,
    last_error_msg   TEXT,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ds_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Sync State (watermark tracking for idempotent sync)
CREATE TABLE sync_state (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    data_source_id  BIGINT NOT NULL,
    entity          ENUM('ORDERS','ADS_METRICS') NOT NULL,
    watermark       DATETIME,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sync_state (tenant_id, data_source_id, entity),
    CONSTRAINT fk_ss_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_ss_ds FOREIGN KEY (data_source_id) REFERENCES data_sources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- POS Shops
CREATE TABLE pos_shops (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    data_source_id    BIGINT NOT NULL,
    external_shop_id  VARCHAR(255) NOT NULL,
    name              VARCHAR(255),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pos_shop (tenant_id, external_shop_id),
    CONSTRAINT fk_ps_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_ps_ds FOREIGN KEY (data_source_id) REFERENCES data_sources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Orders (from POS with UTM tracking)
CREATE TABLE orders (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id             BIGINT NOT NULL,
    pos_shop_id           BIGINT NOT NULL,
    external_order_id     VARCHAR(255) NOT NULL,
    created_at_external   DATETIME,
    updated_at_external   DATETIME,
    status                VARCHAR(50),
    revenue               DECIMAL(18,2) DEFAULT 0,
    customer_phone        VARCHAR(50),
    utm_source            VARCHAR(255),
    utm_medium            VARCHAR(255),
    utm_campaign          VARCHAR(255),
    utm_content           VARCHAR(255),
    utm_term              VARCHAR(255),
    click_id              VARCHAR(500),
    raw_json              LONGTEXT,
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order (tenant_id, pos_shop_id, external_order_id),
    INDEX idx_order_date (tenant_id, created_at_external),
    INDEX idx_order_utm (tenant_id, utm_campaign),
    INDEX idx_order_click (tenant_id, click_id(191)),
    CONSTRAINT fk_ord_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_ord_shop FOREIGN KEY (pos_shop_id) REFERENCES pos_shops(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ad Accounts
CREATE TABLE ad_accounts (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id             BIGINT NOT NULL,
    data_source_id        BIGINT NOT NULL,
    platform              ENUM('META','GOOGLE','TIKTOK') NOT NULL,
    external_account_id   VARCHAR(255) NOT NULL,
    name                  VARCHAR(255),
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ad_account (tenant_id, platform, external_account_id),
    CONSTRAINT fk_aa_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_aa_ds FOREIGN KEY (data_source_id) REFERENCES data_sources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Campaigns
CREATE TABLE campaigns (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id               BIGINT NOT NULL,
    ad_account_id           BIGINT,
    platform                ENUM('META','GOOGLE','TIKTOK') NOT NULL,
    external_campaign_id    VARCHAR(255) NOT NULL,
    name                    VARCHAR(255),
    status                  VARCHAR(50),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign (tenant_id, platform, external_campaign_id),
    CONSTRAINT fk_cmp_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_cmp_aa FOREIGN KEY (ad_account_id) REFERENCES ad_accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ads Metrics Daily
CREATE TABLE ads_metrics_daily (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    platform        ENUM('META','GOOGLE','TIKTOK') NOT NULL,
    ad_account_id   BIGINT NOT NULL,
    campaign_id     BIGINT,
    date            DATE NOT NULL,
    spend           DECIMAL(18,2) DEFAULT 0,
    impressions     BIGINT DEFAULT 0,
    clicks          BIGINT DEFAULT 0,
    reach           BIGINT DEFAULT 0,
    ctr             DECIMAL(10,4) DEFAULT 0,
    cpc             DECIMAL(18,6) DEFAULT 0,
    cpm             DECIMAL(18,6) DEFAULT 0,
    raw_json        LONGTEXT,
    UNIQUE KEY uk_ads_metric (tenant_id, platform, ad_account_id, campaign_id, date),
    INDEX idx_ads_metric_date (tenant_id, date),
    CONSTRAINT fk_am_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_am_aa FOREIGN KEY (ad_account_id) REFERENCES ad_accounts(id),
    CONSTRAINT fk_am_cmp FOREIGN KEY (campaign_id) REFERENCES campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Order Attribution
CREATE TABLE order_attribution (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    order_id      BIGINT NOT NULL,
    platform      ENUM('META','GOOGLE','TIKTOK','UNKNOWN') DEFAULT 'UNKNOWN',
    campaign_id   BIGINT,
    match_type    ENUM('CLICK_ID','UTM','UNKNOWN') DEFAULT 'UNKNOWN',
    matched_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_attribution (tenant_id, order_id),
    CONSTRAINT fk_oa_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_oa_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_oa_cmp FOREIGN KEY (campaign_id) REFERENCES campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Campaign Performance Daily (aggregated view)
CREATE TABLE campaign_perf_daily (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    platform      ENUM('META','GOOGLE','TIKTOK','UNKNOWN') DEFAULT 'UNKNOWN',
    campaign_id   BIGINT,
    date          DATE NOT NULL,
    spend         DECIMAL(18,2) DEFAULT 0,
    orders_count  INT DEFAULT 0,
    revenue       DECIMAL(18,2) DEFAULT 0,
    cpo           DECIMAL(18,6) DEFAULT 0,
    roas          DECIMAL(18,6) DEFAULT 0,
    UNIQUE KEY uk_campaign_perf (tenant_id, platform, campaign_id, date),
    INDEX idx_campaign_perf_date (tenant_id, date),
    CONSTRAINT fk_cp_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_cp_cmp FOREIGN KEY (campaign_id) REFERENCES campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
