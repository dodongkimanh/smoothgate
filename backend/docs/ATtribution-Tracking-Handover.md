# Attribution Tracking Hardening Handover

## Objective
Ensure deterministic attribution by enforcing tracking key presence from Poscake for each order.

Deterministic key priority:
1. `p_utm_id`
2. `ad_id` / `adId` / `AD_ID`
3. `click_id`
4. `fbclid` / `gclid` / `ttclid`

If none exist, campaign attribution cannot be guaranteed.

## Backend Changes Delivered

### 1) Ingestion hardening in Poscake sync
File: `src/main/java/com/smitgate/connector/pos/PancakeConnector.java`

What changed:
- Added multi-source tracking key extraction from:
  - Root payload fields (`p_utm_id`, `ad_id`, `adId`, `AD_ID`, `click_id`, `fbclid`, `gclid`, `ttclid`)
  - Nested payload paths (`tracking.*`, `utm.*`, `metadata.*`, `meta_data.*`, `data.*`, `attributes.*`)
  - `note` text parsing
  - Raw payload fallback parsing (`node.toString()`)
- Added truncation guard for all tracking-related fields.

Impact:
- Maximizes capture of valid tracking IDs without changing source schema.
- Reduces avoidable `UNKNOWN` caused by payload shape differences.

### 2) Attribution quality audit API
Files:
- `src/main/java/com/smitgate/report/ReportService.java`
- `src/main/java/com/smitgate/report/ReportController.java`

New endpoint:
- `GET /api/reports/attribution-quality?from=YYYY-MM-DD&to=YYYY-MM-DD&limit=50`

Returns:
- Total orders in range
- Orders with tracking key
- Orders without tracking key
- Coverage rate (%)
- Attributed orders
- Unknown orders
- Unknown orders without tracking
- Sample list of orders missing tracking keys
- Operational advice message

## Operational SLA for Client Team

### Mandatory integration rule
Every created order in Poscake must include at least one deterministic key:
- `p_utm_id` OR `ad_id`

### Monitoring routine
Run daily:
- `GET /api/reports/attribution-quality?from=<today>&to=<today>&limit=100`

Acceptance target:
- `trackingCoverageRate >= 98%`
- `unknownOrdersWithoutTracking == 0`

Escalation threshold:
- If `ordersWithoutTrackingKey > 0`, notify integration owner same day.
- If `trackingCoverageRate < 95%` for 2 consecutive days, block KPI sign-off.

## Root-cause policy
Unknown attribution with empty tracking key is a source-data issue, not attribution engine logic.

## Verification checklist (Go-live)
1. Trigger `ADS` then `ATTRIBUTION`
2. Compare before/after on:
   - `/api/reports/overview`
   - `/api/reports/campaigns`
   - `/api/reports/attributions`
3. Validate quality endpoint shows high coverage and low missing-tracking sample count.

## Notes
- This hardening is backward-compatible.
- It does not fabricate attribution when no tracking key exists, preserving reporting integrity.
