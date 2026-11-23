-- Meta insights per ad and day split by age + gender, and by region (province), stored by the ad-metrics sync.
CREATE TABLE IF NOT EXISTS ads_ad_breakdown_daily (
    tenant_id        BIGINT        NOT NULL,
    ad_account_id    BIGINT,
    date             DATE          NOT NULL,
    ad_id            VARCHAR(64)   NOT NULL,
    dimension        VARCHAR(16)   NOT NULL, -- 'age_gender' | 'region'
    dim_key          VARCHAR(200)  NOT NULL, -- '35-44|male' or the region name
    age              VARCHAR(16),
    gender           VARCHAR(16),
    region           VARCHAR(200),
    spend            NUMERIC(18, 2) NOT NULL DEFAULT 0,
    impressions      BIGINT        NOT NULL DEFAULT 0,
    reach            BIGINT        NOT NULL DEFAULT 0,
    clicks           BIGINT        NOT NULL DEFAULT 0,
    message_contacts BIGINT        NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, ad_id, date, dimension, dim_key)
);
CREATE INDEX IF NOT EXISTS idx_ads_breakdown_account_date ON ads_ad_breakdown_daily (ad_account_id, date);
