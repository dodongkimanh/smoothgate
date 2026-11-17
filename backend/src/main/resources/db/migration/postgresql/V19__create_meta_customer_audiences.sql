-- Meta customer-list audiences (hashed phones from Pancake orders) and their lookalikes, one row per ad account and kind.
CREATE TABLE IF NOT EXISTS meta_customer_audiences (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    ad_account_id        BIGINT       NOT NULL REFERENCES ad_accounts(id) ON DELETE CASCADE,
    kind                 VARCHAR(30)  NOT NULL,
    external_audience_id VARCHAR(64),
    last_synced_at       TIMESTAMP,
    last_count           INTEGER,
    last_error           VARCHAR(500),
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_meta_customer_audiences UNIQUE (tenant_id, ad_account_id, kind)
);
