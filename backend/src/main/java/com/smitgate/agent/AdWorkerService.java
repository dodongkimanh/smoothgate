package com.smitgate.agent;

import com.smitgate.connector.ads.MetaAdsConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workers are small devices (phones running Termux) that each watch one ad account: every half hour they pull a
 * snapshot from here, check it against the rules, and post alerts and twice-daily AI analyses back. The Meta token
 * and the Claude key never leave the server; a worker only holds its own key, which can read one account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdWorkerService {

    static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<String> AD_FIELDS = List.of(
            "adId", "adName", "adSetId", "adSetName", "adSetStatus", "campaignId", "campaignName",
            "delivery", "status", "budget", "budgetSource", "spend", "impressions", "reach", "clicks",
            "messageContacts", "phoneCount", "orderCount", "sales", "cpm", "postId", "createdDate");

    private final JdbcTemplate jdbc;
    private final MetaAdsConnector metaAdsConnector;

    public record Worker(long id, long tenantId, String name, long adAccountId, String accountName,
                         String externalAccountId, long dataSourceId) {
    }

    /** A worker of this tenant, for the admin page. */
    public Optional<Worker> find(long tenantId, long id) {
        return jdbc.query("""
                select w.id, w.tenant_id, w.name, w.ad_account_id, a.name as account_name,
                       a.external_account_id, a.data_source_id
                from ad_workers w join ad_accounts a on a.id = w.ad_account_id and a.tenant_id = w.tenant_id
                where w.tenant_id = ? and w.id = ?
                """, (rs, i) -> new Worker(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("name"),
                rs.getLong("ad_account_id"), rs.getString("account_name"), rs.getString("external_account_id"),
                rs.getLong("data_source_id")), tenantId, id).stream().findFirst();
    }

    public Optional<Worker> authenticate(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        List<Worker> rows = jdbc.query("""
                select w.id, w.tenant_id, w.name, w.ad_account_id, a.name as account_name,
                       a.external_account_id, a.data_source_id
                from ad_workers w join ad_accounts a on a.id = w.ad_account_id and a.tenant_id = w.tenant_id
                where w.key_hash = ? and w.enabled
                """, (rs, i) -> new Worker(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("name"),
                rs.getLong("ad_account_id"), rs.getString("account_name"), rs.getString("external_account_id"),
                rs.getLong("data_source_id")), sha256(key.trim()));
        return rows.stream().findFirst();
    }

    public void touch(Worker w, String device, String status) {
        jdbc.update("update ad_workers set last_seen_at = ?, device = coalesce(?, device), last_status = coalesce(?, last_status) where id = ?",
                LocalDateTime.now(VIETNAM_ZONE), truncate(device, 200), truncate(status, 500), w.id());
    }

    /**
     * Per-ad numbers as on the ads screen for today, the last 7 days and the last 90 days ("total"), plus 14 days
     * of per-ad-set daily history.
     */
    public Map<String, Object> snapshot(Worker w) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("worker", Map.of("id", w.id(), "name", w.name()));
        out.put("account", Map.of("id", w.adAccountId(), "name", w.accountName(), "externalId", w.externalAccountId()));
        out.put("now", LocalDateTime.now(VIETNAM_ZONE).withNano(0).toString());
        out.putAll(liveNumbers(w));
        touch(w, null, null);
        return out;
    }

    /**
     * Per-ad numbers straight from Meta + orders, as on the ads screen: today, the last 7 days, and "total" — the
     * last 90 days, which stands in for "since it started" in the kill rules (no live campaign is older).
     */
    public Map<String, List<Map<String, Object>>> liveNumbers(Worker w) {
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("today", ads(w, today, today));
        out.put("last7", ads(w, today.minusDays(6), today));
        out.put("total", ads(w, today.minusDays(89), today));
        return out;
    }

    /** For the analyses: also yesterday, the last 3 days and the last 30 days, per ad. */
    public Map<String, List<Map<String, Object>>> liveNumbersFull(Worker w) {
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        Map<String, List<Map<String, Object>>> out = liveNumbers(w);
        out.put("yesterday", ads(w, today.minusDays(1), today.minusDays(1)));
        out.put("last3", ads(w, today.minusDays(2), today));
        out.put("last30", ads(w, today.minusDays(29), today));
        return out;
    }

    private List<Map<String, Object>> ads(Worker w, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = metaAdsConnector.listAdPerformance(
                w.tenantId(), w.dataSourceId(), w.externalAccountId(), from, to, null, null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double spend = number(row.get("spend"));
            boolean active = "ACTIVE".equalsIgnoreCase(String.valueOf(row.getOrDefault("delivery", "")));
            if (spend <= 0 && !active) {
                continue;
            }
            Map<String, Object> ad = new LinkedHashMap<>();
            for (String f : AD_FIELDS) {
                if (row.get(f) != null) {
                    ad.put(f, row.get(f));
                }
            }
            out.add(ad);
        }
        return out;
    }

    /** Stores reports; one with a ref_key already stored for this worker is skipped. Returns how many were new. */
    public int saveReports(Worker w, List<Map<String, Object>> reports) {
        int added = 0;
        for (Map<String, Object> r : reports) {
            String kind = String.valueOf(r.getOrDefault("kind", "ALERT")).toUpperCase();
            if (!kind.equals("ALERT") && !kind.equals("INFO")) { // ANALYSIS and DAILY are written by the server
                continue;
            }
            String title = truncate(str(r.get("title")), 300);
            if (title == null || title.isBlank()) {
                continue;
            }
            added += jdbc.update("""
                    insert into ad_worker_reports (worker_id, kind, severity, ref_key, title, body)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (worker_id, ref_key) where ref_key is not null do nothing
                    """, w.id(), kind, truncate(str(r.get("severity")), 10), truncate(str(r.get("refKey")), 200),
                    title, str(r.get("body")));
        }
        return added;
    }

    // ---- admin side ----

    public List<Map<String, Object>> listWorkers(long tenantId) {
        return jdbc.queryForList("""
                select w.id, w.name, w.enabled, w.device, w.last_status as "lastStatus",
                       to_char(w.last_seen_at, 'YYYY-MM-DD"T"HH24:MI:SS') as "lastSeenAt",
                       a.id as "adAccountId", a.name as "accountName",
                       (select count(*) from ad_worker_reports r where r.worker_id = w.id and r.kind = 'ALERT'
                          and r.created_at >= now() - interval '24 hours') as "alerts24h",
                       (select to_char(max(r.created_at), 'YYYY-MM-DD"T"HH24:MI:SS') from ad_worker_reports r
                          where r.worker_id = w.id and r.kind = 'ANALYSIS') as "lastAnalysisAt"
                from ad_workers w join ad_accounts a on a.id = w.ad_account_id
                where w.tenant_id = ? order by w.name
                """, tenantId);
    }

    public List<Map<String, Object>> listReports(long tenantId, Long workerId, String kind, int limit) {
        StringBuilder sql = new StringBuilder("""
                select r.id, r.worker_id as "workerId", w.name as "workerName", a.name as "accountName",
                       r.kind, r.severity, r.title, r.body,
                       to_char(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') as "createdAt"
                from ad_worker_reports r join ad_workers w on w.id = r.worker_id
                join ad_accounts a on a.id = w.ad_account_id
                where w.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (workerId != null) {
            sql.append(" and r.worker_id = ?");
            args.add(workerId);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" and r.kind = ?");
            args.add(kind.toUpperCase());
        }
        sql.append(" order by r.created_at desc limit ?");
        args.add(Math.max(1, Math.min(limit, 300)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Creates a worker for one ad account and returns its key; only the hash is kept, so the key is shown once. */
    public Map<String, Object> createWorker(long tenantId, String name, long adAccountId) {
        Integer owns = jdbc.queryForObject("select count(*) from ad_accounts where id = ? and tenant_id = ?",
                Integer.class, adAccountId, tenantId);
        if (owns == null || owns == 0) {
            throw new IllegalArgumentException("Không tìm thấy tài khoản quảng cáo");
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String key = "sgw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Long id = jdbc.queryForObject(
                "insert into ad_workers (tenant_id, name, ad_account_id, key_hash) values (?, ?, ?, ?) returning id",
                Long.class, tenantId, truncate(name, 100), adAccountId, sha256(key));
        return Map.of("id", id, "key", key);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static double number(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? 0 : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
