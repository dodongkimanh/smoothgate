package com.smitgate.agent;

import com.smitgate.connector.ads.CrmCustomerSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The worker's ad board: the ads of its account that spent today, with the same columns as the Ads Manager page,
 * each one's numbers for today / yesterday / 3 / 7 / 30 days / since launch (so the page can explain any cell),
 * the CRM leads each ad brought (tag, who it was assigned to) and the owner's threshold rules it breaks.
 */
@Service
@RequiredArgsConstructor
public class WorkerAdBoardService {

    static final String[] PERIODS = {"today", "yesterday", "last3", "last7", "last30", "total"};
    private static final String[] NUMBERS = {"spend", "impressions", "reach", "clicks", "comments", "messageContacts",
            "phoneCount", "orderCount", "sales", "profitOrderCount", "orderProfit"};
    private static final String[] INFO = {"adId", "adName", "adSetId", "adSetName", "campaignId", "campaignName",
            "adAccountId", "adAccountName", "dataSourceId", "thumbnailUrl", "createdDate", "delivery", "status",
            "configuredStatus", "budget", "budgetSource", "postId"};
    private static final long CACHE_MS = 60_000;

    private final AdWorkerService workers;
    private final CrmCustomerSource crm;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<Long, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public Map<String, Object> board(AdWorkerService.Worker w, boolean fresh) {
        Map<String, Object> hit = cache.get(w.id());
        if (!fresh && hit != null && System.currentTimeMillis() - (long) hit.get("_at") < CACHE_MS) {
            return hit;
        }
        Map<String, Object> out = build(w);
        out.put("_at", System.currentTimeMillis());
        cache.put(w.id(), out);
        return out;
    }

    private Map<String, Object> build(AdWorkerService.Worker w) {
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        LocalDate[] from = {today, today.minusDays(1), today.minusDays(2), today.minusDays(6), today.minusDays(29), today.minusDays(89)};
        LocalDate[] to = {today, today.minusDays(1), today, today, today, today};
        List<CompletableFuture<List<Map<String, Object>>>> calls = new ArrayList<>();
        for (int i = 0; i < PERIODS.length; i++) {
            int k = i;
            calls.add(CompletableFuture.supplyAsync(() -> workers.fullAds(w, from[k], to[k]), pool));
        }
        Map<String, Map<String, Map<String, Object>>> byPeriod = new LinkedHashMap<>();
        for (int i = 0; i < PERIODS.length; i++) {
            byPeriod.put(PERIODS[i], perAd(calls.get(i).join()));
        }

        // The ads that spent today, biggest spend first.
        List<Map<String, Object>> todayAds = new ArrayList<>(byPeriod.get("today").values());
        todayAds.removeIf(a -> num(a.get("spend")) <= 0);
        todayAds.sort(Comparator.comparingDouble((Map<String, Object> a) -> -num(a.get("spend"))));
        List<String> ids = todayAds.stream().map(a -> str(a.get("adId"))).toList();

        Map<String, List<CrmCustomerSource.AdLead>> leadsByAd = new LinkedHashMap<>();
        String crmError = null;
        try {
            for (CrmCustomerSource.AdLead l : crm.adLeads(ids, 90)) {
                leadsByAd.computeIfAbsent(l.adId(), x -> new ArrayList<>()).add(l);
            }
        } catch (Exception e) {
            crmError = "Không đọc được CRM: " + e.getMessage();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : todayAds) {
            String id = str(t.get("adId"));
            Map<String, Object> row = new LinkedHashMap<>();
            for (String f : INFO) {
                row.put(f, t.get(f));
            }
            Map<String, Object> periods = new LinkedHashMap<>();
            for (String p : PERIODS) {
                Map<String, Object> a = byPeriod.get(p).get(id);
                Map<String, Object> m = new LinkedHashMap<>();
                for (String f : NUMBERS) {
                    m.put(f, a == null ? 0 : num(a.get(f)));
                }
                periods.put(p, m);
            }
            row.put("periods", periods);
            List<CrmCustomerSource.AdLead> leads = leadsByAd.getOrDefault(id, List.of());
            row.put("leadStats", leadStats(leads, today));
            row.put("leads", leadList(leads, today));
            row.put("alerts", alerts(byPeriod.get("total").get(id), byPeriod.get("last3").get(id)));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", w.accountName());
        out.put("at", LocalDateTime.now(AdWorkerService.VIETNAM_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        out.put("rules", Map.of("noPhoneSpend", WorkerAnalysisService.NO_PHONE_SPEND,
                "maxCostPerPhone", WorkerAnalysisService.MAX_COST_PER_PHONE, "costPerPhoneFrom", 1_500_000,
                "noOrderSpend", 5_000_000, "goodCostPerOrder", WorkerAnalysisService.GOOD_COST_PER_ORDER));
        out.put("ads", rows);
        if (crmError != null) {
            out.put("crmError", crmError);
        }
        return out;
    }

    static final String[] SALE_PERIODS = {"today", "yesterday", "last3", "last7", "thisMonth", "lastMonth", "max"};
    private final Map<Long, Map<String, Object>> accountAds = new ConcurrentHashMap<>();

    /**
     * Leads per sale (the CRM "Sale" column = who the lead was assigned to) for every ad this account ever ran,
     * for each of {@link #SALE_PERIODS}.
     */
    public Map<String, Object> sales(AdWorkerService.Worker w) {
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        List<String> ids = accountAdIds(w, today);
        List<CrmCustomerSource.AdLead> leads = crm.adLeads(ids, 3700);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate lastMonthStart = monthStart.minusMonths(1);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String p : SALE_PERIODS) {
            LeadQuality.Count all = new LeadQuality.Count();
            Map<String, LeadQuality.Count> bySale = new LinkedHashMap<>();
            for (CrmCustomerSource.AdLead l : leads) {
                if (l.createdAt() == null) continue;
                LocalDate d = l.createdAt().atZone(AdWorkerService.VIETNAM_ZONE).toLocalDate();
                boolean in = switch (p) {
                    case "today" -> d.equals(today);
                    case "yesterday" -> d.equals(today.minusDays(1));
                    case "last3" -> !d.isBefore(today.minusDays(2));
                    case "last7" -> !d.isBefore(today.minusDays(6));
                    case "thisMonth" -> !d.isBefore(monthStart);
                    case "lastMonth" -> !d.isBefore(lastMonthStart) && d.isBefore(monthStart);
                    default -> true;
                };
                if (!in) continue;
                LeadQuality.Kind k = LeadQuality.classify(l.tags());
                all.add(k, l.hasPhone());
                bySale.computeIfAbsent(saleOf(l), x -> new LeadQuality.Count()).add(k, l.hasPhone());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            bySale.entrySet().stream().sorted((a, b) -> b.getValue().total - a.getValue().total).forEach(e -> {
                Map<String, Object> s = e.getValue().toMap();
                s.put("sale", e.getKey());
                rows.add(s);
            });
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", all.toMap());
            m.put("sales", rows);
            out.put(p, m);
        }
        LocalDate first = leads.stream().filter(l -> l.createdAt() != null)
                .map(l -> l.createdAt().atZone(AdWorkerService.VIETNAM_ZONE).toLocalDate()).min(LocalDate::compareTo).orElse(null);
        out.put("firstDate", first == null ? null : first.toString());
        out.put("ads", ids.size());
        out.put("at", LocalDateTime.now(AdWorkerService.VIETNAM_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return out;
    }

    /** Every ad the account ran in Meta's longest window (37 months), refreshed every 30 minutes. */
    @SuppressWarnings("unchecked")
    private List<String> accountAdIds(AdWorkerService.Worker w, LocalDate today) {
        Map<String, Object> hit = accountAds.get(w.id());
        if (hit != null && System.currentTimeMillis() - (long) hit.get("at") < 30 * 60_000L) {
            return (List<String>) hit.get("ids");
        }
        List<Map<String, Object>> rows;
        try {
            rows = workers.fullAds(w, today.minusMonths(37).plusDays(1), today);
        } catch (Exception e) {
            rows = workers.fullAds(w, today.minusDays(89), today);
        }
        List<String> ids = new ArrayList<>(perAd(rows).keySet());
        accountAds.put(w.id(), Map.of("at", System.currentTimeMillis(), "ids", ids));
        return ids;
    }

    /** One entry per ad (the connector can return an ad more than once, e.g. per day). */
    private static Map<String, Map<String, Object>> perAd(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String id = str(r.get("adId"));
            if (id.isEmpty()) continue;
            Map<String, Object> cur = out.get(id);
            if (cur == null) {
                out.put(id, new LinkedHashMap<>(r));
                continue;
            }
            for (String f : NUMBERS) {
                cur.put(f, num(cur.get(f)) + num(r.get(f)));
            }
        }
        return out;
    }

    /** Lead counts per period: by quality tag, with a phone, and per sale. */
    private static Map<String, Object> leadStats(List<CrmCustomerSource.AdLead> leads, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String p : PERIODS) {
            LeadQuality.Count all = new LeadQuality.Count();
            Map<String, LeadQuality.Count> bySale = new LinkedHashMap<>();
            for (CrmCustomerSource.AdLead l : leads) {
                if (!inPeriod(l, p, today)) continue;
                LeadQuality.Kind k = LeadQuality.classify(l.tags());
                all.add(k, l.hasPhone());
                bySale.computeIfAbsent(saleOf(l), x -> new LeadQuality.Count()).add(k, l.hasPhone());
            }
            Map<String, Object> m = all.toMap();
            List<Map<String, Object>> sales = new ArrayList<>();
            bySale.entrySet().stream().sorted((a, b) -> b.getValue().total - a.getValue().total).forEach(e -> {
                Map<String, Object> s = e.getValue().toMap();
                s.put("sale", e.getKey());
                sales.add(s);
            });
            m.put("bySale", sales);
            out.put(p, m);
        }
        return out;
    }

    /** The last 7 days' leads one by one, for the popup. */
    private static List<Map<String, Object>> leadList(List<CrmCustomerSource.AdLead> leads, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CrmCustomerSource.AdLead l : leads) {
            if (!inPeriod(l, "last7", today)) continue;
            LeadQuality.Kind k = LeadQuality.classify(l.tags());
            var at = l.createdAt().atZone(AdWorkerService.VIETNAM_ZONE).toLocalDateTime();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", at.toLocalDate().toString());
            m.put("time", at.format(DateTimeFormatter.ofPattern("HH:mm")));
            m.put("customer", l.customer());
            m.put("sale", saleOf(l));
            m.put("tags", l.tags() == null ? "" : l.tags());
            m.put("kind", k.name());
            m.put("kindLabel", k.label);
            m.put("hasPhone", l.hasPhone());
            m.put("status", l.status());
            out.add(m);
        }
        return out;
    }

    private static String saleOf(CrmCustomerSource.AdLead l) {
        return l.sale() == null || l.sale().isBlank() ? "Chưa chia" : l.sale().trim();
    }

    private static boolean inPeriod(CrmCustomerSource.AdLead l, String p, LocalDate today) {
        if (l.createdAt() == null) return false;
        LocalDate d = l.createdAt().atZone(AdWorkerService.VIETNAM_ZONE).toLocalDate();
        return switch (p) {
            case "today" -> d.equals(today);
            case "yesterday" -> d.equals(today.minusDays(1));
            case "last3" -> !d.isBefore(today.minusDays(2));
            case "last7" -> !d.isBefore(today.minusDays(6));
            case "last30" -> !d.isBefore(today.minusDays(29));
            default -> true;
        };
    }

    /** The owner's rules, counted since the ad started; "column" is the cell to paint red. */
    private static List<Map<String, Object>> alerts(Map<String, Object> total, Map<String, Object> last3) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (total == null) return out;
        double spend = num(total.get("spend"));
        double phones = num(total.get("phoneCount"));
        double orders = num(total.get("orderCount"));
        boolean sellsWell = orders > 0 && spend / orders < WorkerAnalysisService.GOOD_COST_PER_ORDER;
        if (spend >= WorkerAnalysisService.NO_PHONE_SPEND && phones == 0) {
            out.add(alert("bad", "phoneCount", "Từ khi chạy đã chi " + money(spend) + " mà 0 SĐT (luật 500k) → nên tắt"));
        } else if (!sellsWell && spend >= 1_500_000 && phones > 0 && spend / phones > WorkerAnalysisService.MAX_COST_PER_PHONE) {
            out.add(alert("bad", "costPerPhone", "Từ khi chạy " + money(spend / phones) + "/SĐT, vượt 350k khi đã chi từ 1,5tr → nên tắt"));
        }
        if (spend >= 5_000_000 && orders == 0) {
            out.add(alert("bad", "orderCount", "Từ khi chạy đã chi " + money(spend) + " mà 0 đơn (luật 5tr) → nên tắt"));
        }
        if (sellsWell) {
            out.add(alert("good", "costPerOrder", money(spend / orders) + "/đơn, dưới 2tr → có thể tăng 20% mỗi 3 ngày"));
        }
        if (last3 != null && out.stream().noneMatch(a -> "bad".equals(a.get("level")))) {
            double s3 = num(last3.get("spend"));
            double p3 = num(last3.get("phoneCount"));
            if (p3 > 0 && s3 / p3 > WorkerAnalysisService.MAX_COST_PER_PHONE) {
                out.add(alert("warn", "costPerPhone", "3 ngày qua " + money(s3 / p3) + "/SĐT, trên 350k → theo dõi"));
            } else if (p3 == 0 && s3 >= WorkerAnalysisService.NO_PHONE_SPEND) {
                out.add(alert("warn", "phoneCount", "3 ngày qua chi " + money(s3) + " mà 0 SĐT → theo dõi"));
            }
        }
        return out;
    }

    private static Map<String, Object> alert(String level, String column, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("column", column);
        m.put("text", text);
        return m;
    }

    private static String money(double v) {
        return String.format(java.util.Locale.GERMANY, "%,.0f", v) + "đ";
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
