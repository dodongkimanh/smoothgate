-- Messenger customers who left a phone number, reported to Meta once as a Lead event (CAPI business messaging).
CREATE TABLE IF NOT EXISTS meta_lead_events (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL,
    page_id     VARCHAR(64)  NOT NULL,
    psid        VARCHAR(64)  NOT NULL,
    crm_row_id  BIGINT,
    phone_hash  VARCHAR(64),
    ad_id       VARCHAR(64),
    event_time  TIMESTAMP    NOT NULL,
    sent_at     TIMESTAMP,
    last_error  VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_meta_lead_events UNIQUE (tenant_id, page_id, psid)
);
CREATE INDEX IF NOT EXISTS idx_meta_lead_events_unsent ON meta_lead_events (tenant_id) WHERE sent_at IS NULL;
