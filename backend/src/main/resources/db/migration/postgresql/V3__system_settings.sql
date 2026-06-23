-- System-level key-value settings (Meta App credentials, etc.)
CREATE TABLE system_settings (
    key_name   VARCHAR(255) PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMP DEFAULT NOW()
);
