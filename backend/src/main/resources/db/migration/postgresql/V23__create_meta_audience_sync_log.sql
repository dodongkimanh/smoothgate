-- One row per daily customer-list upload: how many people were sent, how many joined or left the lists since the
-- previous upload, and how many carry a birth year / gender / province for Meta's matching.
CREATE TABLE IF NOT EXISTS meta_audience_sync_log (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               BIGINT    NOT NULL,
    synced_at               TIMESTAMP NOT NULL,
    buyers                  INT       NOT NULL,
    all_phones              INT       NOT NULL,
    added_buyers            INT,
    removed_buyers          INT,
    added_phones            INT,
    removed_phones          INT,
    with_birth_year         INT       NOT NULL DEFAULT 0,
    buyers_with_birth_year  INT       NOT NULL DEFAULT 0,
    with_gender             INT       NOT NULL DEFAULT 0,
    with_province           INT       NOT NULL DEFAULT 0,
    accounts_ok             INT       NOT NULL DEFAULT 0,
    accounts_failed         INT       NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_meta_audience_sync_log_tenant ON meta_audience_sync_log (tenant_id, synced_at DESC);

-- The hashed phones in each list at the last upload, to count who joined or left at the next one.
CREATE TABLE IF NOT EXISTS meta_audience_sent_phones (
    tenant_id   BIGINT      NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    phone_hash  CHAR(64)    NOT NULL,
    PRIMARY KEY (tenant_id, kind, phone_hash)
);
