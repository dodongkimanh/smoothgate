CREATE TABLE ad_saved_views (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id  BIGINT NOT NULL,
    name       VARCHAR(255) NOT NULL,
    ad_keys    JSON NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ad_saved_views_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
