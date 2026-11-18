-- Daily Meta insights per ad (ads_metrics_daily only keeps campaign totals), so spend, messages and
-- Meta leads can be compared with CRM leads by ad_id.
CREATE TABLE IF NOT EXISTS ads_ad_metrics_daily (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL,
    ad_account_id     BIGINT        NOT NULL,
    account_external  VARCHAR(64)   NOT NULL,
    date              DATE          NOT NULL,
    campaign_id       VARCHAR(64),
    campaign_name     VARCHAR(500),
    adset_id          VARCHAR(64),
    adset_name        VARCHAR(500),
    ad_id             VARCHAR(64)   NOT NULL,
    ad_name           VARCHAR(500),
    spend             NUMERIC(18,2) NOT NULL DEFAULT 0,
    impressions       BIGINT        NOT NULL DEFAULT 0,
    reach             BIGINT        NOT NULL DEFAULT 0,
    clicks            BIGINT        NOT NULL DEFAULT 0,
    message_contacts  BIGINT        NOT NULL DEFAULT 0,
    meta_leads        BIGINT        NOT NULL DEFAULT 0,
    actions_json      TEXT,
    updated_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ads_ad_metrics_daily UNIQUE (tenant_id, ad_id, date)
);
CREATE INDEX IF NOT EXISTS idx_ads_ad_metrics_account_date ON ads_ad_metrics_daily (tenant_id, ad_account_id, date);
