-- ============================================================
-- SmitGate Database Schema — PostgreSQL (Supabase)
-- ============================================================

CREATE TABLE tenants (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    timezone   VARCHAR(50)  DEFAULT 'Asia/Ho_Chi_Minh',
    currency   VARCHAR(10)  DEFAULT 'VND',
    created_at TIMESTAMP    DEFAULT NOW()
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants(id),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    role          VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN','ANALYST')),
    status        VARCHAR(20)  DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_user_email UNIQUE (email)
);

CREATE TABLE data_sources (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenants(id),
    type             VARCHAR(30)  NOT NULL CHECK (type IN ('PANCAKE_POS','META_ADS','GOOGLE_ADS','TIKTOK_ADS')),
    name             VARCHAR(255),
    status           VARCHAR(20)  DEFAULT 'INACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','ERROR')),
    config_json      JSONB,
    secret_encrypted TEXT,
    last_success_at  TIMESTAMP,
    last_error_at    TIMESTAMP,
    last_error_msg   TEXT,
    created_at       TIMESTAMP    DEFAULT NOW()
);

CREATE TABLE sync_state (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenants(id),
    data_source_id BIGINT      NOT NULL REFERENCES data_sources(id),
    entity         VARCHAR(30) NOT NULL CHECK (entity IN ('ORDERS','ADS_METRICS')),
    watermark      TIMESTAMP,
    updated_at     TIMESTAMP   DEFAULT NOW(),
    CONSTRAINT uk_sync_state UNIQUE (tenant_id, data_source_id, entity)
);

CREATE TABLE pos_shops (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL REFERENCES tenants(id),
    data_source_id   BIGINT       NOT NULL REFERENCES data_sources(id),
    external_shop_id VARCHAR(255) NOT NULL,
    name             VARCHAR(255),
    created_at       TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_pos_shop UNIQUE (tenant_id, external_shop_id)
);

CREATE TABLE orders (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT        NOT NULL REFERENCES tenants(id),
    pos_shop_id         BIGINT        NOT NULL REFERENCES pos_shops(id),
    external_order_id   VARCHAR(255)  NOT NULL,
    created_at_external TIMESTAMP,
    updated_at_external TIMESTAMP,
    status              VARCHAR(50),
    revenue             DECIMAL(18,2) DEFAULT 0,
    customer_phone      VARCHAR(50),
    utm_source          VARCHAR(255),
    utm_medium          VARCHAR(255),
    utm_campaign        VARCHAR(255),
    utm_content         VARCHAR(255),
    utm_term            VARCHAR(255),
    click_id            VARCHAR(500),
    raw_json            TEXT,
    created_at          TIMESTAMP     DEFAULT NOW(),
    CONSTRAINT uk_order UNIQUE (tenant_id, pos_shop_id, external_order_id)
);
CREATE INDEX idx_order_date ON orders (tenant_id, created_at_external);
CREATE INDEX idx_order_utm  ON orders (tenant_id, utm_campaign);
CREATE INDEX idx_order_click ON orders (tenant_id, click_id);

CREATE TABLE ad_accounts (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants(id),
    data_source_id      BIGINT       NOT NULL REFERENCES data_sources(id),
    platform            VARCHAR(20)  NOT NULL CHECK (platform IN ('META','GOOGLE','TIKTOK')),
    external_account_id VARCHAR(255) NOT NULL,
    name                VARCHAR(255),
    created_at          TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_ad_account UNIQUE (tenant_id, platform, external_account_id)
);

CREATE TABLE campaigns (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenants(id),
    ad_account_id        BIGINT       REFERENCES ad_accounts(id),
    platform             VARCHAR(20)  NOT NULL CHECK (platform IN ('META','GOOGLE','TIKTOK')),
    external_campaign_id VARCHAR(255) NOT NULL,
    name                 VARCHAR(255),
    status               VARCHAR(50),
    created_at           TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_campaign UNIQUE (tenant_id, platform, external_campaign_id)
);

CREATE TABLE ads_metrics_daily (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT         NOT NULL REFERENCES tenants(id),
    platform      VARCHAR(20)    NOT NULL CHECK (platform IN ('META','GOOGLE','TIKTOK')),
    ad_account_id BIGINT         NOT NULL REFERENCES ad_accounts(id),
    campaign_id   BIGINT         REFERENCES campaigns(id),
    date          DATE           NOT NULL,
    spend         DECIMAL(18,2)  DEFAULT 0,
    impressions   BIGINT         DEFAULT 0,
    clicks        BIGINT         DEFAULT 0,
    reach         BIGINT         DEFAULT 0,
    ctr           DECIMAL(10,4)  DEFAULT 0,
    cpc           DECIMAL(18,6)  DEFAULT 0,
    cpm           DECIMAL(18,6)  DEFAULT 0,
    raw_json      TEXT,
    CONSTRAINT uk_ads_metric UNIQUE (tenant_id, platform, ad_account_id, campaign_id, date)
);
CREATE INDEX idx_ads_metric_date ON ads_metrics_daily (tenant_id, date);

CREATE TABLE order_attribution (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenants(id),
    order_id    BIGINT      NOT NULL REFERENCES orders(id),
    platform    VARCHAR(20) DEFAULT 'UNKNOWN' CHECK (platform IN ('META','GOOGLE','TIKTOK','UNKNOWN')),
    campaign_id BIGINT      REFERENCES campaigns(id),
    match_type  VARCHAR(20) DEFAULT 'UNKNOWN' CHECK (match_type IN ('CLICK_ID','UTM','UNKNOWN')),
    matched_at  TIMESTAMP   DEFAULT NOW(),
    CONSTRAINT uk_order_attribution UNIQUE (tenant_id, order_id)
);

CREATE TABLE campaign_perf_daily (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenants(id),
    platform    VARCHAR(20)   DEFAULT 'UNKNOWN' CHECK (platform IN ('META','GOOGLE','TIKTOK','UNKNOWN')),
    campaign_id BIGINT        REFERENCES campaigns(id),
    date        DATE          NOT NULL,
    spend       DECIMAL(18,2) DEFAULT 0,
    orders_count INT          DEFAULT 0,
    revenue     DECIMAL(18,2) DEFAULT 0,
    cpo         DECIMAL(18,6) DEFAULT 0,
    roas        DECIMAL(18,6) DEFAULT 0,
    CONSTRAINT uk_campaign_perf UNIQUE (tenant_id, platform, campaign_id, date)
);
CREATE INDEX idx_campaign_perf_date ON campaign_perf_daily (tenant_id, date);
