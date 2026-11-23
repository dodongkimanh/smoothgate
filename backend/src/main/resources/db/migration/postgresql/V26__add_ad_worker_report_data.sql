-- Structured data shown as tables next to a report's text (e.g. the ad-by-ad comparison of an analysis).
alter table ad_worker_reports add column if not exists data text;
