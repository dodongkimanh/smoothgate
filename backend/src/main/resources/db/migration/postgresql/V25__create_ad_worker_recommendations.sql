-- Every suggestion a worker's AI analysis makes, and 3 days later whether the owner followed it and whether it
-- turned out right. Evaluated suggestions become "lessons" fed back into later analyses.
CREATE TABLE ad_worker_recommendations (
    id            BIGSERIAL PRIMARY KEY,
    worker_id     BIGINT       NOT NULL REFERENCES ad_workers(id) ON DELETE CASCADE,
    report_id     BIGINT       REFERENCES ad_worker_reports(id) ON DELETE SET NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    level         VARCHAR(20)  NOT NULL,          -- adset | campaign
    entity_id     VARCHAR(64)  NOT NULL,
    entity_name   VARCHAR(500),
    action        VARCHAR(30)  NOT NULL,          -- TAT | TANG_NGAN_SACH | GIAM_NGAN_SACH | GIU | THEO_DOI | KHAC
    detail        TEXT,
    baseline      TEXT,                           -- numbers when suggested (JSON)
    due_date      DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | EVALUATED
    followed      VARCHAR(20),                    -- CO | KHONG | KHONG_RO
    after_metrics TEXT,                           -- numbers after the suggestion (JSON)
    verdict       VARCHAR(20),                    -- DUNG | SAI | CHUA_RO
    lesson        TEXT,
    evaluated_at  TIMESTAMP
);

CREATE INDEX idx_ad_worker_recs_worker ON ad_worker_recommendations (worker_id, status, due_date);
