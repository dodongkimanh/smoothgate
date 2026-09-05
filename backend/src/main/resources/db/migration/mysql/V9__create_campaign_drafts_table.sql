CREATE TABLE campaign_drafts (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id    BIGINT NOT NULL,
    name         VARCHAR(255) NOT NULL,
    objective    VARCHAR(100),
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    payload_json JSON,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
