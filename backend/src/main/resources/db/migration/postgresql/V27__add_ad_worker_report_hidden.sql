-- Reports the owner removed from the Workers page: kept (so a worker does not re-send the same alert) but hidden.
alter table ad_worker_reports add column if not exists hidden boolean not null default false;
