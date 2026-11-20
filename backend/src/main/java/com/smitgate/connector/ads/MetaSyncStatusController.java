package com.smitgate.connector.ads;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Smoothgate has sent to Meta and whether it worked: customer-list audiences, phone leads
 * (LeadSubmitted) and purchases. Read-only; admins only (falls under the default admin rule).
 *
 * <p>The health summary is always "now"; the lead and purchase tables cover the chosen days so they can be
 * compared with Ads Manager. Leads are dated by when the customer messaged (the event time sent to Meta),
 * purchases by the order date.
 */
@Slf4j
@RestController
@RequestMapping("/api/meta-sync")
@RequiredArgsConstructor
public class MetaSyncStatusController {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String ISO = "'YYYY-MM-DD\"T\"HH24:MI:SS'";
    private static final int MAX_ROWS = 300;

    private final JdbcTemplate jdbc;
    private final CrmCustomerSource crmCustomerSource;
    private final MetaLeadEventService leadEventService;
    private final MetaPurchaseEventService purchaseEventService;
    private final MetaCustomerAudienceService audienceService;
    private final MetaAdAccountResolver adAccountResolver;
    private final MetaAdsConnector metaAdsConnector;
    private final com.smitgate.datasource.DataSourceRepository dataSourceRepository;
    private final com.smitgate.datasource.DataSourceService dataSourceService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        if (to == null) to = today;
        if (from == null) from = to;
        if (from.isAfter(to)) {
            LocalDate t = from;
            from = to;
            to = t;
        }
        Timestamp start = Timestamp.valueOf(from.atStartOfDay());
        Timestamp end = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        Map<String, String> pageNames = safePageNames();

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("crmConfigured", crmCustomerSource.isConfigured());
        jobs.put("audienceRunning", audienceService.isRunning());
        jobs.put("leadLastRunAt", iso(leadEventService.getLastRunAt()));
        jobs.put("purchaseLastRunAt", iso(purchaseEventService.getLastRunAt()));
        out.put("jobs", jobs);
        out.put("metaLogin", metaLogin(tenantId, pageNames));
        out.put("range", Map.of("from", from.toString(), "to", to.toString()));
        out.put("audiences", audiences(tenantId));
        out.put("audienceHistory", audienceHistory(tenantId));
        out.put("leads", leads(tenantId, pageNames, start, end));
        out.put("purchases", purchases(tenantId, pageNames, start, end));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /** Sends the customer lists to Meta now instead of waiting for 23:59; runs in the background. */
    @PostMapping("/audiences/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runAudiencesNow() {
        boolean started = audienceService.runNow();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("started", started, "running", true)));
    }

    private List<Map<String, Object>> audiences(Long tenantId) {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList("""
                SELECT a.name AS account, a.external_account_id AS "accountId", m.kind, m.external_audience_id AS "audienceId",
                       m.last_count AS "lastCount", m.meta_count_lower AS "metaCountLower", m.meta_count_upper AS "metaCountUpper",
                       m.meta_status AS "metaStatus", to_char(m.last_synced_at, %1$s) AS "lastSyncedAt",
                       to_char(m.meta_checked_at, %1$s) AS "metaCheckedAt", m.last_error AS "lastError"
                FROM meta_customer_audiences m JOIN ad_accounts a ON a.id = m.ad_account_id
                WHERE m.tenant_id = ?
                ORDER BY a.name, m.kind
                """.formatted(ISO), tenantId));
        rows.removeIf(r -> !audienceService.isIncluded(String.valueOf(r.get("accountId"))));
        return rows;
    }

    /** The last 30 daily uploads of the customer lists, newest first. */
    private List<Map<String, Object>> audienceHistory(Long tenantId) {
        return jdbc.queryForList("""
                SELECT to_char(synced_at, %s) AS "syncedAt", buyers, all_phones AS "allPhones",
                       added_buyers AS "addedBuyers", removed_buyers AS "removedBuyers",
                       added_phones AS "addedPhones", removed_phones AS "removedPhones",
                       with_birth_year AS "withBirthYear", buyers_with_birth_year AS "buyersWithBirthYear",
                       with_gender AS "withGender", with_province AS "withProvince",
                       accounts_ok AS "accountsOk", accounts_failed AS "accountsFailed"
                FROM meta_audience_sync_log WHERE tenant_id = ?
                ORDER BY synced_at DESC LIMIT 30
                """.formatted(ISO), tenantId);
    }

    private Map<String, Object> leads(Long tenantId, Map<String, String> pageNames, Timestamp start, Timestamp end) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE event_time >= date_trunc('day', now() AT TIME ZONE 'Asia/Ho_Chi_Minh')) AS today,
                       count(*) FILTER (WHERE event_time >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days') AS "last7d",
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NULL) AS pending,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NOT NULL
                                        AND event_time >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days') AS failed,
                       to_char(max(sent_at), %s) AS "lastSentAt"
                FROM meta_lead_events WHERE tenant_id = ?
                """.formatted(ISO), tenantId));

        // Pages whose leads Meta is still refusing (only those still young enough to be retried).
        List<Map<String, Object>> failing = jdbc.queryForList("""
                SELECT page_id AS "pageId", count(*) AS failed, max(last_error) AS error
                FROM meta_lead_events
                WHERE tenant_id = ? AND sent_at IS NULL AND last_error IS NOT NULL
                  AND event_time >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days'
                GROUP BY page_id ORDER BY failed DESC
                """, tenantId);
        failing.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("failingPages", failing);
        out.put("failReason", failing.isEmpty() ? null : explain(String.valueOf(failing.get(0).get("error"))));

        out.put("rangeTotals", jdbc.queryForMap("""
                SELECT count(*) AS total, count(sent_at) AS sent,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NOT NULL) AS failed
                FROM meta_lead_events WHERE tenant_id = ? AND event_time >= ? AND event_time < ?
                """, tenantId, start, end));

        List<Map<String, Object>> byPage = jdbc.queryForList("""
                SELECT page_id AS "pageId", count(*) AS total, count(sent_at) AS sent,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NOT NULL) AS failed,
                       to_char(max(sent_at), %s) AS "lastSentAt",
                       max(last_error) FILTER (WHERE sent_at IS NULL) AS "lastError"
                FROM meta_lead_events WHERE tenant_id = ? AND event_time >= ? AND event_time < ?
                GROUP BY page_id ORDER BY total DESC
                """.formatted(ISO), tenantId, start, end);
        byPage.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("byPage", byPage);

        List<Map<String, Object>> byAd = jdbc.queryForList("""
                SELECT ad_id AS "adId", page_id AS "pageId", count(*) AS total, count(sent_at) AS sent,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NOT NULL) AS failed
                FROM meta_lead_events WHERE tenant_id = ? AND event_time >= ? AND event_time < ?
                GROUP BY ad_id, page_id ORDER BY total DESC, ad_id
                """, tenantId, start, end);
        byAd.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("byAd", byAd);

        out.put("daily", jdbc.queryForList("""
                SELECT to_char(event_time, 'YYYY-MM-DD') AS date, count(*) AS total, count(sent_at) AS sent
                FROM meta_lead_events WHERE tenant_id = ? AND event_time >= ? AND event_time < ?
                GROUP BY 1 ORDER BY 1 DESC
                """, tenantId, start, end));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, crm_row_id AS "crmRowId", page_id AS "pageId", ad_id AS "adId",
                       to_char(event_time, %1$s) AS "eventTime", to_char(sent_at, %1$s) AS "sentAt", last_error AS error
                FROM meta_lead_events WHERE tenant_id = ? AND event_time >= ? AND event_time < ?
                ORDER BY event_time DESC, id DESC LIMIT %2$d
                """.formatted(ISO, MAX_ROWS), tenantId, start, end);
        Set<Long> rowIds = new HashSet<>();
        rows.forEach(r -> {
            if (r.get("crmRowId") instanceof Number n) rowIds.add(n.longValue());
        });
        Map<Long, String> names = safeCustomerNames(rowIds);
        rows.forEach(r -> {
            r.put("pageName", pageNames.get(String.valueOf(r.get("pageId"))));
            Object row = r.get("crmRowId");
            r.put("customerName", row instanceof Number n ? names.get(n.longValue()) : null);
        });
        out.put("rows", rows);
        attachAccounts(tenantId, byAd, rows);
        return out;
    }

    private Map<String, Object> purchases(Long tenantId, Map<String, String> pageNames, Timestamp start, Timestamp end) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE meta_purchase_sent_at >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days') AS "sent7d",
                       COALESCE(sum(revenue) FILTER (WHERE meta_purchase_sent_at >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days'), 0) AS "value7d",
                       count(*) FILTER (WHERE meta_purchase_sent_at IS NULL AND meta_purchase_error IS NOT NULL
                                        AND created_at_external >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '30 days') AS failed,
                       to_char(max(meta_purchase_sent_at), %s) AS "lastSentAt"
                FROM orders WHERE tenant_id = ?
                """.formatted(ISO), tenantId));

        String inRange = "tenant_id = ? AND created_at_external >= ? AND created_at_external < ? "
                + "AND (meta_purchase_sent_at IS NOT NULL OR meta_purchase_error IS NOT NULL)";
        out.put("rangeTotals", jdbc.queryForMap("""
                SELECT count(meta_purchase_sent_at) AS sent,
                       COALESCE(sum(revenue) FILTER (WHERE meta_purchase_sent_at IS NOT NULL), 0) AS value,
                       count(*) FILTER (WHERE meta_purchase_sent_at IS NULL) AS failed
                FROM orders WHERE
                """ + inRange, tenantId, start, end));

        List<Map<String, Object>> byAd = jdbc.queryForList("""
                SELECT CASE WHEN click_id ~ '^[0-9]+$' THEN click_id END AS "adId", fb_page_id AS "pageId", count(meta_purchase_sent_at) AS sent,
                       COALESCE(sum(revenue) FILTER (WHERE meta_purchase_sent_at IS NOT NULL), 0) AS value
                FROM orders WHERE
                """ + inRange + " GROUP BY 1, 2 ORDER BY sent DESC, value DESC", tenantId, start, end);
        byAd.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("byAd", byAd);

        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList("""
                SELECT external_order_id AS "orderId", customer_name AS "customerName", revenue, status,
                       CASE WHEN click_id ~ '^[0-9]+$' THEN click_id END AS "adId", fb_page_id AS "pageId", to_char(created_at_external, %1$s) AS "orderAt",
                       to_char(meta_purchase_sent_at, %1$s) AS "sentAt", meta_purchase_error AS error
                FROM orders WHERE
                """.formatted(ISO) + inRange + " ORDER BY created_at_external DESC LIMIT " + MAX_ROWS, tenantId, start, end));
        rows.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("rows", rows);
        attachAccounts(tenantId, byAd, rows);
        return out;
    }

    /** Adds the ad account (id and Smoothgate name) of each row's ad, so the page can group by account. */
    @SafeVarargs
    private void attachAccounts(Long tenantId, List<Map<String, Object>>... lists) {
        Set<String> adIds = new HashSet<>();
        for (List<Map<String, Object>> list : lists) {
            list.forEach(r -> {
                if (r.get("adId") != null) adIds.add(String.valueOf(r.get("adId")));
            });
        }
        Map<String, String> accountByAd;
        try {
            accountByAd = adAccountResolver.accountsOf(tenantId, adIds);
        } catch (Exception e) {
            log.warn("Ad accounts of ads unavailable: {}", e.getMessage());
            accountByAd = Map.of();
        }
        Map<String, String> names = new java.util.HashMap<>();
        jdbc.query("SELECT external_account_id, name FROM ad_accounts WHERE tenant_id = ?",
                rs -> {
                    names.put(rs.getString(1), rs.getString(2));
                }, tenantId);
        for (List<Map<String, Object>> list : lists) {
            for (Map<String, Object> r : list) {
                String act = r.get("adId") == null ? null : accountByAd.get(String.valueOf(r.get("adId")));
                r.put("accountId", act);
                r.put("accountName", act == null ? null : names.getOrDefault(act, act));
            }
        }
    }

    /**
     * The connected Facebook login as Meta reports it: when the owner last connected and which Pages may receive
     * events. Pages Smoothgate sends leads for but that lack page_events are listed so the owner knows what to tick.
     */
    private Map<String, Object> metaLogin(Long tenantId, Map<String, String> pageNames) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            com.smitgate.datasource.DataSource ds = dataSourceRepository
                    .findByTypeAndStatus(com.smitgate.datasource.DataSource.Type.META_ADS, com.smitgate.datasource.DataSource.Status.ACTIVE)
                    .stream().filter(d -> tenantId.equals(d.getTenantId())).findFirst().orElse(null);
            if (ds == null) {
                out.put("error", "Chưa kết nối Meta");
                return out;
            }
            Map<String, Object> info = metaAdsConnector.inspectUserToken(tenantId, dataSourceService.decryptSecret(ds));
            out.put("valid", info.get("valid"));
            out.put("issuedAt", info.get("issuedAt"));
            out.put("expiresAt", info.get("expiresAt"));
            @SuppressWarnings("unchecked")
            Map<String, List<String>> granular = (Map<String, List<String>>) info.get("granularScopes");
            List<String> eventPages = granular.getOrDefault("page_events", List.of());
            List<Map<String, Object>> pages = new ArrayList<>();
            for (String id : eventPages) {
                pages.add(Map.of("pageId", id, "pageName", pageNames.getOrDefault(id, id)));
            }
            out.put("pageEventsPages", pages);
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT DISTINCT page_id FROM meta_lead_events WHERE tenant_id = ?", tenantId)) {
                String id = String.valueOf(r.get("page_id"));
                if (!eventPages.contains(id)) missing.add(Map.of("pageId", id, "pageName", pageNames.getOrDefault(id, id)));
            }
            out.put("pagesMissingEvents", missing);
        } catch (Exception e) {
            log.warn("Meta login check failed: {}", e.getMessage());
            out.put("error", "Không kiểm tra được kết nối Meta: " + e.getMessage());
        }
        return out;
    }

    /** Meta's error in words the owner can act on. */
    static String explain(String error) {
        if (error == null) return null;
        if (error.contains("page_events")) {
            return "Smoothgate chưa được cấp quyền gửi sự kiện cho page này. Vào Kết nối QC, kết nối lại Meta, "
                    + "bấm \"Chỉnh sửa quyền truy cập\" và tích chọn page đó.";
        }
        if (error.contains("pages_read_engagement") || error.contains("không quản lý trang")) {
            return "Tài khoản Facebook đang kết nối không quản lý page này. Thêm tài khoản đó làm quản trị viên page rồi kết nối lại Meta.";
        }
        if (error.contains("token") || error.contains("session")) {
            return "Kết nối Meta đã hết hạn. Vào Kết nối QC và kết nối lại Meta.";
        }
        return error;
    }

    private static String iso(java.time.LocalDateTime t) {
        return t == null ? null : t.withNano(0).toString();
    }

    private Map<String, String> safePageNames() {
        try {
            return crmCustomerSource.pageNames();
        } catch (Exception e) {
            log.warn("CRM page names unavailable: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Long, String> safeCustomerNames(Set<Long> rowIds) {
        try {
            return crmCustomerSource.customerNames(rowIds);
        } catch (Exception e) {
            log.warn("CRM customer names unavailable: {}", e.getMessage());
            return Map.of();
        }
    }
}
