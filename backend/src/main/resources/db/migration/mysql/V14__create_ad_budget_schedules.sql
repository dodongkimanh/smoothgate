-- Daily automatic budget increase or decrease per Meta ad set / CBO campaign (e.g. +10% or -10% every day at 18:00)
CREATE TABLE ad_budget_schedules (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL,
    data_source_id   BIGINT        NOT NULL,
    object_id        VARCHAR(64)   NOT NULL,
    ad_account_id    VARCHAR(64),
    increase_percent DECIMAL(6,2)  NOT NULL,
    run_time         VARCHAR(5)    NOT NULL,
    budget_limit     BIGINT,
    last_run_date    DATE,
    last_run_at      DATETIME,
    last_result      VARCHAR(500),
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ad_budget_schedules_object UNIQUE (tenant_id, object_id)
);
