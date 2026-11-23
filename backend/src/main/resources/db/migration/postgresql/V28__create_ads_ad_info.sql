-- What each ad is right now (status, budget, creative), stored by the ad-metrics sync so the workers and their page
-- read it from Smoothgate instead of calling Meta.
CREATE TABLE IF NOT EXISTS ads_ad_info (
    tenant_id        BIGINT       NOT NULL,
    ad_account_id    BIGINT,
    account_external VARCHAR(64),
    ad_id            VARCHAR(64)  NOT NULL,
    ad_name          TEXT,
    adset_id         VARCHAR(64),
    adset_name       TEXT,
    adset_status     VARCHAR(40),
    campaign_id      VARCHAR(64),
    campaign_name    TEXT,
    status           VARCHAR(40),
    effective_status VARCHAR(40),
    created_time     VARCHAR(40),
    thumbnail_url    TEXT,
    post_id          VARCHAR(64),
    budget           NUMERIC(18, 2),
    budget_source    VARCHAR(40),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, ad_id)
);
CREATE INDEX IF NOT EXISTS idx_ads_ad_info_account ON ads_ad_info (ad_account_id);
