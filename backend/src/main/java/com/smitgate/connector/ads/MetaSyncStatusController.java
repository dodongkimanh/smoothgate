package com.smitgate.connector.ads;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Smoothgate has sent to Meta and whether it worked: customer-list audiences, phone leads
 * (LeadSubmitted) and purchases. Read-only; admins only (falls under the default admin rule).
 */
@Slf4j
@RestController
@RequestMapping("/api/meta-sync")
@RequiredArgsConstructor
public class MetaSyncStatusController {

    private final JdbcTemplate jdbc;
    private final CrmCustomerSource crmCustomerSource;
    private final MetaLeadEventService leadEventService;
    private final MetaPurchaseEventService purchaseEventService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        Map<String, String> pageNames = safePageNames();

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> jobs = new LinkedHashMap<>();
        jobs.put("crmConfigured", crmCustomerSource.isConfigured());
        jobs.put("leadLastRunAt", iso(leadEventService.getLastRunAt()));
        jobs.put("purchaseLastRunAt", iso(purchaseEventService.getLastRunAt()));
        out.put("jobs", jobs);
        out.put("audiences", audiences(tenantId));
        out.put("leads", leads(tenantId, pageNames));
        out.put("purchases", purchases(tenantId, pageNames));
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    private List<Map<String, Object>> audiences(Long tenantId) {
        return jdbc.queryForList("""
                SELECT a.name AS account, a.external_account_id AS "accountId", m.kind, m.external_audience_id AS "audienceId",
                       m.last_count AS "lastCount", m.meta_count_lower AS "metaCountLower", m.meta_count_upper AS "metaCountUpper",
                       m.meta_status AS "metaStatus", to_char(m.last_synced_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS "lastSyncedAt", to_char(m.meta_checked_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS "metaCheckedAt",
                       m.last_error AS "lastError"
                FROM meta_customer_audiences m JOIN ad_accounts a ON a.id = m.ad_account_id
                WHERE m.tenant_id = ?
                ORDER BY a.name, m.kind
                """, tenantId);
    }

    private Map<String, Object> leads(Long tenantId, Map<String, String> pageNames) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE event_time >= date_trunc('day', now() AT TIME ZONE 'Asia/Ho_Chi_Minh')) AS today,
                       count(*) FILTER (WHERE event_time >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days') AS "last7d",
                       count(sent_at) AS sent,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NULL) AS pending,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NOT NULL) AS failed,
                       to_char(max(sent_at), 'YYYY-MM-DD"T"HH24:MI:SS') AS "lastSentAt"
                FROM meta_lead_events WHERE tenant_id = ?
                """, tenantId));

        List<Map<String, Object>> byPage = jdbc.queryForList("""
                SELECT page_id AS "pageId", count(*) AS total, count(sent_at) AS sent,
                       count(*) FILTER (WHERE sent_at IS NULL AND last_error IS NOT NULL) AS failed,
                       count(*) FILTER (WHERE event_time >= date_trunc('day', now() AT TIME ZONE 'Asia/Ho_Chi_Minh')) AS today,
                       to_char(max(sent_at), 'YYYY-MM-DD"T"HH24:MI:SS') AS "lastSentAt",
                       max(last_error) FILTER (WHERE sent_at IS NULL) AS "lastError"
                FROM meta_lead_events WHERE tenant_id = ?
                GROUP BY page_id ORDER BY total DESC
                """, tenantId);
        byPage.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("byPage", byPage);

        out.put("daily", jdbc.queryForList("""
                SELECT to_char(event_time, 'YYYY-MM-DD') AS date, count(*) AS total, count(sent_at) AS sent
                FROM meta_lead_events
                WHERE tenant_id = ? AND event_time >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '14 days'
                GROUP BY 1 ORDER BY 1 DESC
                """, tenantId));

        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT id, crm_row_id AS "crmRowId", page_id AS "pageId", ad_id AS "adId", to_char(event_time, 'YYYY-MM-DD"T"HH24:MI:SS') AS "eventTime",
                       to_char(sent_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS "sentAt", last_error AS error
                FROM meta_lead_events WHERE tenant_id = ?
                ORDER BY event_time DESC, id DESC LIMIT 50
                """, tenantId);
        Set<Long> rowIds = new HashSet<>();
        recent.forEach(r -> {
            if (r.get("crmRowId") instanceof Number n) rowIds.add(n.longValue());
        });
        Map<Long, String> names = safeCustomerNames(rowIds);
        recent.forEach(r -> {
            r.put("pageName", pageNames.get(String.valueOf(r.get("pageId"))));
            Object row = r.get("crmRowId");
            r.put("customerName", row instanceof Number n ? names.get(n.longValue()) : null);
        });
        out.put("recent", recent);
        return out;
    }

    private Map<String, Object> purchases(Long tenantId, Map<String, String> pageNames) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE meta_purchase_sent_at >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days') AS "sent7d",
                       COALESCE(sum(revenue) FILTER (WHERE meta_purchase_sent_at >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '7 days'), 0) AS "value7d",
                       count(meta_purchase_sent_at) AS "sentTotal",
                       count(*) FILTER (WHERE meta_purchase_sent_at IS NULL AND meta_purchase_error IS NOT NULL
                                        AND created_at_external >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '30 days') AS failed,
                       to_char(max(meta_purchase_sent_at), 'YYYY-MM-DD"T"HH24:MI:SS') AS "lastSentAt"
                FROM orders WHERE tenant_id = ?
                """, tenantId));
        List<Map<String, Object>> recent = new ArrayList<>(jdbc.queryForList("""
                SELECT external_order_id AS "orderId", customer_name AS "customerName", revenue, status,
                       fb_page_id AS "pageId", to_char(created_at_external, 'YYYY-MM-DD"T"HH24:MI:SS') AS "orderAt",
                       to_char(meta_purchase_sent_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS "sentAt", meta_purchase_error AS error
                FROM orders
                WHERE tenant_id = ? AND (meta_purchase_sent_at IS NOT NULL OR meta_purchase_error IS NOT NULL)
                ORDER BY COALESCE(meta_purchase_sent_at, created_at_external) DESC NULLS LAST LIMIT 30
                """, tenantId));
        recent.forEach(r -> r.put("pageName", pageNames.get(String.valueOf(r.get("pageId")))));
        out.put("recent", recent);
        return out;
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
