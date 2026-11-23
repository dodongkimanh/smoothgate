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
import java.util.concurrent.ConcurrentHashMap;

/**
 * The worker's ad board: the ads of its account that spent today, with the same columns as the Ads Manager page,
 * each one's numbers for today / yesterday / 3 / 7 / 30 days / since launch (so the page can explain any cell),
 * the CRM leads each ad brought (tag, who it was assigned to) and the owner's threshold rules it breaks.
 */
@Service
@RequiredArgsConstructor
public class WorkerAdBoardService {

    static final String[] PERIODS = {"today", "yesterday", "last3", "last7", "last30", "total", "thisMonth", "lastMonth"};
    /** The periods the board can show (as on the Ads Manager page); an ad is listed when it spent in one of them. */
    static final String[] BOARD_PERIODS = {"today", "yesterday", "last3", "last7", "thisMonth", "lastMonth"};
    private static final String[] NUMBERS = {"spend", "impressions", "reach", "clicks", "comments", "messageContacts",
            "phoneCount", "orderCount", "sales", "profitOrderCount", "orderProfit"};
    private static final String[] INFO = {"adId", "adName", "adSetId", "adSetName", "campaignId", "campaignName",
            "adAccountId", "adAccountName", "dataSourceId", "thumbnailUrl", "createdDate", "delivery", "status",
            "configuredStatus", "budget", "budgetSource", "postId"};
    private static final long CACHE_MS = 30_000;

    private final AdWorkerService workers;
    private final CrmCustomerSource crm;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
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
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate[] from = {today, today.minusDays(1), today.minusDays(2), today.minusDays(6), today.minusDays(29), today.minusDays(89),
                monthStart, monthStart.minusMonths(1)};
        LocalDate[] to = {today, today.minusDays(1), today, today, today, today, today, monthStart.minusDays(1)};
        // One after another (not in parallel) and through the shared cache: Meta limits calls per ad account.
        Map<String, Map<String, Map<String, Object>>> byPeriod = new LinkedHashMap<>();
        for (int i = 0; i < PERIODS.length; i++) {
            byPeriod.put(PERIODS[i], perAd(workers.fullAds(w, from[i], to[i])));
        }
        LocalDateTime fetched = workers.dataUpdatedAt(w);

        // The ads that spent in any of the board's periods (today … last month), today's biggest spend first.
        Map<String, Map<String, Object>> scope = new LinkedHashMap<>();
        for (String p : BOARD_PERIODS) {
            for (Map<String, Object> a : byPeriod.get(p).values()) {
                if (num(a.get("spend")) > 0) scope.putIfAbsent(str(a.get("adId")), a);
            }
        }
        List<Map<String, Object>> todayAds = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : scope.entrySet()) {
            // Status, budget and creative are current whatever the period: prefer today's row when there is one.
            Map<String, Object> t = byPeriod.get("today").get(e.getKey());
            todayAds.add(t != null ? t : e.getValue());
        }
        todayAds.sort(Comparator.comparingDouble((Map<String, Object> a) -> -num(byPeriod.get("today").getOrDefault(str(a.get("adId")), Map.of()).get("spend")))
                .thenComparingDouble(a -> -num(byPeriod.get("thisMonth").getOrDefault(str(a.get("adId")), Map.of()).get("spend"))));
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
        // updated_at is stored in the server's time (UTC); show it in Vietnam time.
        out.put("at", fetched == null ? null : fetched.atZone(java.time.ZoneOffset.UTC)
                .withZoneSameInstant(AdWorkerService.VIETNAM_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        out.put("rules", Map.of("noPhoneSpend", WorkerAnalysisService.NO_PHONE_SPEND,
                "maxCostPerPhone", WorkerAnalysisService.MAX_COST_PER_PHONE, "costPerPhoneFrom", 1_500_000,
                "noOrderSpend", 5_000_000, "goodCostPerOrder", WorkerAnalysisService.GOOD_COST_PER_ORDER));
        out.put("ads", rows);
        if (crmError != null) {
            out.put("crmError", crmError);
        }
        return out;
    }

    /**
     * Meta's split by age + gender and by region for this account (or one ad), for a period, from what the sync stored.
     */
    public Map<String, Object> breakdown(AdWorkerService.Worker w, String period, String adId) {
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        LocalDate from = switch (period == null ? "today" : period) {
            case "yesterday" -> today.minusDays(1);
            case "last3" -> today.minusDays(2);
            case "last7" -> today.minusDays(6);
            case "last30" -> today.minusDays(29);
            default -> today;
        };
        LocalDate to = "yesterday".equals(period) ? today.minusDays(1) : today;
        boolean oneAd = adId != null && !adId.isBlank();
        String sql = """
                select dim_key, max(age) as age, max(gender) as gender, max(region) as region,
                       sum(spend) as spend, sum(impressions) as impressions, sum(clicks) as clicks,
                       sum(message_contacts) as messages
                from ads_ad_breakdown_daily
                where ad_account_id = ? and dimension = ? and date between ? and ?
                """ + (oneAd ? " and ad_id = ?" : "") + " group by dim_key order by sum(spend) desc";
        Map<String, Object> out = new LinkedHashMap<>();
        for (String dim : List.of("age_gender", "region")) {
            List<Object> args = new ArrayList<>(List.of(w.adAccountId(), dim, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)));
            if (oneAd) args.add(adId);
            out.put(dim, jdbc.queryForList(sql, args.toArray()));
        }
        try {
            out.put("region", withCrm(w, castRows(out.get("region")), from, to, oneAd ? adId : null));
        } catch (Exception e) {
            out.put("crmError", "Không đọc được CRM: " + e.getMessage());
        }
        java.sql.Timestamp t = jdbc.queryForObject("select max(updated_at) from ads_ad_breakdown_daily where ad_account_id = ?",
                java.sql.Timestamp.class, w.adAccountId());
        out.put("at", t == null ? null : t.toLocalDateTime().atZone(java.time.ZoneOffset.UTC)
                .withZoneSameInstant(AdWorkerService.VIETNAM_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object o) {
        return (List<Map<String, Object>>) o;
    }

    /**
     * Meta's split by region joined with the CRM by province: leads and phones (province from the lead's address, or
     * from any CRM order/lead with the same phone) and CRM orders (don_hang) of these ads or of their leads' phones.
     */
    private List<Map<String, Object>> withCrm(AdWorkerService.Worker w, List<Map<String, Object>> meta, LocalDate from,
                                              LocalDate to, String adId) {
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        List<String> ids = adId != null ? List.of(adId) : accountAdIds(w, today);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> m : meta) {
            String region = str(m.get("region"));
            String key = com.smitgate.connector.ads.CustomerContact.provinceOf(region);
            Map<String, Object> r = row(rows, key == null ? "?" + region : key, key == null ? region : null);
            for (String f : List.of("spend", "impressions", "clicks", "messages")) {
                r.put(f, num(r.get(f)) + num(m.get(f)));
            }
        }
        // Leads of the last 60 days give the phones whose orders count for these ads.
        List<CrmCustomerSource.LeadGeo> leads = crm.leadGeo(ids, 60);
        java.util.Set<String> phones = new java.util.LinkedHashSet<>();
        for (CrmCustomerSource.LeadGeo l : leads) {
            if (l.phone9() != null && l.phone9().length() == 9) phones.add(l.phone9());
        }
        Map<String, String> addr = crm.addressByPhone(phones);
        java.util.Set<String> phonesCounted = new java.util.HashSet<>();
        for (CrmCustomerSource.LeadGeo l : leads) {
            if (l.createdAt() == null) continue;
            LocalDate d = l.createdAt().atZone(AdWorkerService.VIETNAM_ZONE).toLocalDate();
            if (d.isBefore(from) || d.isAfter(to)) continue;
            String key = provinceKey(l.address(), l.phone9() == null ? null : addr.get(l.phone9()));
            Map<String, Object> r = row(rows, key, null);
            r.put("crmLeads", num(r.get("crmLeads")) + 1);
            boolean hasPhone = l.phone9() != null && l.phone9().length() == 9;
            if (hasPhone && phonesCounted.add(l.phone9())) {
                r.put("phones", num(r.get("phones")) + 1);
            }
            if (LeadQuality.isQuality(LeadQuality.classify(l.tags()), hasPhone)) {
                r.put("quality", num(r.get("quality")) + 1);
            }
        }
        java.util.Set<String> idSet = new java.util.HashSet<>(ids);
        for (CrmCustomerSource.CrmOrder o : crm.ordersFor(ids, phones, 60)) {
            if (o.day() == null || o.day().isBefore(from) || o.day().isAfter(to)) continue;
            String status = str(o.status()).toLowerCase(java.util.Locale.ROOT);
            if (status.contains("hủy") || status.contains("huỷ")) continue;
            // An order with another ad's id belongs to that ad, even if the phone also wrote to one of ours.
            String oAd = str(o.adId()).trim();
            if (!oAd.isEmpty() && oAd.matches("\\d{6,}") && !idSet.contains(oAd)) continue;
            String key = provinceKey(o.address(), o.phone9() == null ? null : addr.get(o.phone9()));
            Map<String, Object> r = row(rows, key, null);
            r.put("orders", num(r.get("orders")) + 1);
            r.put("revenue", num(r.get("revenue")) + o.amount());
            if (status.contains("giao thành công")) r.put("delivered", num(r.get("delivered")) + 1);
            if (status.contains("hoàn")) r.put("returned", num(r.get("returned")) + 1);
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparingDouble((Map<String, Object> r) -> -num(r.get("spend")))
                .thenComparingDouble(r -> -num(r.get("orders"))).thenComparingDouble(r -> -num(r.get("phones"))));
        return out;
    }

    private static String provinceKey(String address, String phoneAddress) {
        String k = com.smitgate.connector.ads.CustomerContact.provinceOf(address);
        if (k == null) k = com.smitgate.connector.ads.CustomerContact.provinceOf(phoneAddress);
        return k == null ? "unknown" : k;
    }

    private static Map<String, Object> row(Map<String, Map<String, Object>> rows, String key, String metaName) {
        return rows.computeIfAbsent(key, k -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dim_key", k);
            r.put("region", metaName != null ? metaName : PROVINCE_NAMES.getOrDefault(k, "unknown".equals(k) ? "Không rõ tỉnh" : k));
            for (String f : List.of("spend", "impressions", "clicks", "messages", "crmLeads", "phones", "quality", "orders",
                    "delivered", "returned", "revenue")) {
                r.put(f, 0d);
            }
            return r;
        });
    }

    private static final Map<String, String> PROVINCE_NAMES = Map.ofEntries(
            Map.entry("angiang", "An Giang"), Map.entry("bariavungtau", "Bà Rịa – Vũng Tàu"), Map.entry("bacgiang", "Bắc Giang"),
            Map.entry("backan", "Bắc Kạn"), Map.entry("baclieu", "Bạc Liêu"), Map.entry("bacninh", "Bắc Ninh"),
            Map.entry("bentre", "Bến Tre"), Map.entry("binhdinh", "Bình Định"), Map.entry("binhduong", "Bình Dương"),
            Map.entry("binhphuoc", "Bình Phước"), Map.entry("binhthuan", "Bình Thuận"), Map.entry("camau", "Cà Mau"),
            Map.entry("cantho", "Cần Thơ"), Map.entry("caobang", "Cao Bằng"), Map.entry("danang", "Đà Nẵng"),
            Map.entry("daklak", "Đắk Lắk"), Map.entry("daknong", "Đắk Nông"), Map.entry("dienbien", "Điện Biên"),
            Map.entry("dongnai", "Đồng Nai"), Map.entry("dongthap", "Đồng Tháp"), Map.entry("gialai", "Gia Lai"),
            Map.entry("hagiang", "Hà Giang"), Map.entry("hanam", "Hà Nam"), Map.entry("hanoi", "Hà Nội"),
            Map.entry("hatinh", "Hà Tĩnh"), Map.entry("haiduong", "Hải Dương"), Map.entry("haiphong", "Hải Phòng"),
            Map.entry("haugiang", "Hậu Giang"), Map.entry("hoabinh", "Hòa Bình"), Map.entry("hungyen", "Hưng Yên"),
            Map.entry("khanhhoa", "Khánh Hòa"), Map.entry("kiengiang", "Kiên Giang"), Map.entry("kontum", "Kon Tum"),
            Map.entry("laichau", "Lai Châu"), Map.entry("lamdong", "Lâm Đồng"), Map.entry("langson", "Lạng Sơn"),
            Map.entry("laocai", "Lào Cai"), Map.entry("longan", "Long An"), Map.entry("namdinh", "Nam Định"),
            Map.entry("nghean", "Nghệ An"), Map.entry("ninhbinh", "Ninh Bình"), Map.entry("ninhthuan", "Ninh Thuận"),
            Map.entry("phutho", "Phú Thọ"), Map.entry("phuyen", "Phú Yên"), Map.entry("quangbinh", "Quảng Bình"),
            Map.entry("quangnam", "Quảng Nam"), Map.entry("quangngai", "Quảng Ngãi"), Map.entry("quangninh", "Quảng Ninh"),
            Map.entry("quangtri", "Quảng Trị"), Map.entry("soctrang", "Sóc Trăng"), Map.entry("sonla", "Sơn La"),
            Map.entry("tayninh", "Tây Ninh"), Map.entry("thaibinh", "Thái Bình"), Map.entry("thainguyen", "Thái Nguyên"),
            Map.entry("thanhhoa", "Thanh Hóa"), Map.entry("thuathienhue", "Huế"), Map.entry("tiengiang", "Tiền Giang"),
            Map.entry("travinh", "Trà Vinh"), Map.entry("tuyenquang", "Tuyên Quang"), Map.entry("vinhlong", "Vĩnh Long"),
            Map.entry("vinhphuc", "Vĩnh Phúc"), Map.entry("yenbai", "Yên Bái"), Map.entry("hochiminh", "TP. Hồ Chí Minh"));

    static final String[] SALE_PERIODS = {"today", "yesterday", "last3", "last7", "thisMonth", "lastMonth", "max"};

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

    /**
     * The ads of this account, from what Smoothgate stores (daily per-ad numbers and the ad list of the sync).
     */
    private List<String> accountAdIds(AdWorkerService.Worker w, LocalDate today) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(jdbc.queryForList(
                "select distinct ad_id from ads_ad_metrics_daily where ad_account_id = ? and ad_id is not null",
                String.class, w.adAccountId()));
        ids.addAll(jdbc.queryForList("select ad_id from ads_ad_info where ad_account_id = ?", String.class, w.adAccountId()));
        return new ArrayList<>(ids);
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
            case "thisMonth" -> !d.isBefore(today.withDayOfMonth(1));
            case "lastMonth" -> !d.isBefore(today.withDayOfMonth(1).minusMonths(1)) && d.isBefore(today.withDayOfMonth(1));
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
