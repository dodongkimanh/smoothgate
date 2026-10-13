CREATE TABLE ad_notes (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT NOT NULL,
    ad_id      VARCHAR(100) NOT NULL,
    content    TEXT NOT NULL,
    author     VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ad_notes_tenant_ad (tenant_id, ad_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
