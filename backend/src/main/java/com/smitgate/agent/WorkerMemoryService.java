package com.smitgate.agent;

import com.smitgate.connector.pos.OrderStatusClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A worker's memory lives here on the server, not on the phone, so a replaced phone carries on with the same
 * history: 30 days of per-ad-set results (spend from Meta, phones/orders/revenue from orders by ad), the alerts and
 * analyses it posted, and the ledger of suggestions with whether each was followed and turned out right.
 */
@Service
@RequiredArgsConstructor
public class WorkerMemoryService {

    static final int EVALUATE_AFTER_DAYS = 3;

    private final JdbcTemplate jdbc;

    /** Per ad set per day: spend, impressions, messages, phones, orders, revenue. Ordered by date. */
    public List<Map<String, Object>> dailyHistory(AdWorkerService.Worker w, LocalDate from) {
        List<String> valid = OrderStatusClassifier.validStatusesForAggregation();
        List<String> leads = OrderStatusClassifier.leadStatusesForAggregation();
        String sql = """
                with m as (
                    select date, ad_id, adset_id, adset_name, campaign_id, campaign_name, spend, impressions, message_contacts
                    from ads_ad_metrics_daily where ad_account_id = ? and date >= ?),
                admap as (
                    select distinct on (ad_id) ad_id, adset_id, adset_name, campaign_id, campaign_name
                    from ads_ad_metrics_daily where ad_account_id = ? and date >= ? - 90
                    order by ad_id, date desc),
                ph as (
                    select o.created_at_external::date d, o.click_id ad_id, count(distinct o.customer_phone) phones
                    from orders o join admap a on a.ad_id = o.click_id
                    where o.tenant_id = ? and o.created_at_external >= ? and o.customer_phone <> ''
                      and lower(o.status) in (%s)
                    group by 1, 2),
                od as (
                    select o.created_at_external::date d, o.click_id ad_id, count(*) orders, coalesce(sum(o.revenue), 0) revenue
                    from orders o join admap a on a.ad_id = o.click_id
                    where o.tenant_id = ? and o.created_at_external >= ? and lower(o.status) in (%s)
                    group by 1, 2),
                k as (select date d, ad_id from m union select d, ad_id from ph union select d, ad_id from od)
                select k.d::text as date, a.adset_id as "adSetId", max(a.adset_name) as "adSetName",
                       max(a.campaign_id) as "campaignId", max(a.campaign_name) as "campaignName",
                       round(coalesce(sum(m.spend), 0)) as spend, coalesce(sum(m.impressions), 0) as impressions,
                       coalesce(sum(m.message_contacts), 0) as messages, coalesce(sum(ph.phones), 0) as phones,
                       coalesce(sum(od.orders), 0) as orders, round(coalesce(sum(od.revenue), 0)) as revenue
                from k join admap a on a.ad_id = k.ad_id
                left join m on m.date = k.d and m.ad_id = k.ad_id
                left join ph on ph.d = k.d and ph.ad_id = k.ad_id
                left join od on od.d = k.d and od.ad_id = k.ad_id
                where k.d >= ?
                group by k.d, a.adset_id order by k.d, a.adset_id
                """.formatted(placeholders(leads.size()), placeholders(valid.size()));
        List<Object> args = new ArrayList<>(List.of(w.adAccountId(), from, w.adAccountId(), from, w.tenantId(), from.atStartOfDay()));
        args.addAll(leads);
        args.addAll(List.of(w.tenantId(), from.atStartOfDay()));
        args.addAll(valid);
        args.add(from);
        return jdbc.queryForList(sql, args.toArray());
    }

    public List<Map<String, Object>> recentReports(AdWorkerService.Worker w, String kind, int days, int limit) {
        return jdbc.queryForList("""
                select id, kind, severity, title, body, to_char(created_at, 'YYYY-MM-DD HH24:MI') as at
                from ad_worker_reports where worker_id = ? and kind = ? and created_at >= now() - make_interval(days => ?)
                order by created_at desc limit ?
                """, w.id(), kind, days, limit);
    }

    public List<Map<String, Object>> dueRecommendations(AdWorkerService.Worker w, LocalDate today) {
        return jdbc.queryForList("""
                select id, level, entity_id as "entityId", entity_name as "entityName", action, detail, baseline,
                       created_at::date::text as "createdDate", due_date::text as "dueDate"
                from ad_worker_recommendations
                where worker_id = ? and status = 'PENDING' and due_date <= ?
                order by created_at limit 12
                """, w.id(), today);
    }

    public List<Map<String, Object>> lessons(AdWorkerService.Worker w, int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select created_at::date::text as "createdDate", entity_name as "entityName", action, detail,
                       followed, verdict, lesson
                from ad_worker_recommendations
                where worker_id = ? and status = 'EVALUATED' and verdict in ('DUNG', 'SAI')
                order by evaluated_at desc limit ?
                """, w.id(), limit);
        Collections.reverse(rows);
        return rows;
    }

    public Map<String, Object> scorecard(AdWorkerService.Worker w, int days) {
        return jdbc.queryForMap("""
                select count(*) filter (where verdict = 'DUNG') as dung, count(*) filter (where verdict = 'SAI') as sai,
                       count(*) filter (where verdict = 'CHUA_RO') as "chuaRo",
                       count(*) filter (where status = 'PENDING') as pending
                from ad_worker_recommendations where worker_id = ? and created_at >= now() - make_interval(days => ?)
                """, w.id(), days);
    }

    public void saveRecommendation(AdWorkerService.Worker w, Long reportId, String level, String entityId,
                                   String entityName, String action, String detail, String baselineJson) {
        jdbc.update("""
                insert into ad_worker_recommendations
                    (worker_id, report_id, level, entity_id, entity_name, action, detail, baseline, due_date)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, w.id(), reportId, level, entityId, cut(entityName, 500), action, detail, baselineJson,
                LocalDate.now(AdWorkerService.VIETNAM_ZONE).plusDays(EVALUATE_AFTER_DAYS));
    }

    /** Stores the follow-up numbers when a due suggestion is shown to the AI, before it gives its verdict. */
    public void recordFollowUp(long id, String followed, String afterJson) {
        jdbc.update("update ad_worker_recommendations set followed = ?, after_metrics = ? where id = ?", followed, afterJson, id);
    }

    public int applyVerdict(AdWorkerService.Worker w, long id, String verdict, String lesson) {
        return jdbc.update("""
                update ad_worker_recommendations set verdict = ?, lesson = ?, status = 'EVALUATED', evaluated_at = ?
                where id = ? and worker_id = ? and status = 'PENDING'
                """, verdict, lesson, LocalDateTime.now(AdWorkerService.VIETNAM_ZONE), id, w.id());
    }

    public List<Map<String, Object>> listRecommendations(long tenantId, Long workerId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select r.id, r.worker_id as "workerId", w.name as "workerName", r.level, r.entity_name as "entityName",
                       r.action, r.detail, r.status, r.followed, r.verdict, r.lesson,
                       to_char(r.created_at, 'YYYY-MM-DD"T"HH24:MI:SS') as "createdAt", r.due_date::text as "dueDate",
                       to_char(r.evaluated_at, 'YYYY-MM-DD"T"HH24:MI:SS') as "evaluatedAt"
                from ad_worker_recommendations r join ad_workers w on w.id = r.worker_id
                where w.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (workerId != null) {
            sql.append(" and r.worker_id = ?");
            args.add(workerId);
        }
        sql.append(" order by r.created_at desc limit ?");
        args.add(Math.max(1, Math.min(limit, 300)));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(Math.max(n, 1), "?"));
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
