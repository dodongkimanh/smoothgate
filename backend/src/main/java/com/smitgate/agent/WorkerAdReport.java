package com.smitgate.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import static com.smitgate.agent.WorkerAnalysisService.money;
import static com.smitgate.agent.WorkerAnalysisService.str;

/**
 * The ad-by-ad comparison the owner reads like the detailed ads table: every ad that spent today or is running,
 * with CPM, CTR, cost per message / phone / order for today, yesterday, 3 days, 30 days and since launch, the CRM
 * tags of its messages, and a check against the owner's thresholds. Computed, not written by the AI, so the numbers
 * are exact; the AI comments on it.
 */
final class WorkerAdReport {

    private WorkerAdReport() {
    }

    static final String[] PERIODS = {"today", "yesterday", "last3", "last30", "total"};
    private static final String[] LABELS = {"Hôm nay", "Hôm qua", "3 ngày", "30 ngày", "Từ khi chạy"};

    /** The ads to report: spent today or still running, running ones first, then by today's spend. */
    static List<String> adsInScope(Map<String, Map<String, WorkerAnalysisService.Agg>> byPeriod) {
        Map<String, WorkerAnalysisService.Agg> today = byPeriod.get("today");
        Set<String> ids = new LinkedHashSet<>();
        today.values().stream()
                .filter(a -> a.spend > 0 || a.active)
                .sorted(Comparator.comparing((WorkerAnalysisService.Agg a) -> !a.active).thenComparing(a -> -a.spend))
                .forEach(a -> ids.add(a.id));
        return new ArrayList<>(ids);
    }

    static String build(Map<String, Map<String, WorkerAnalysisService.Agg>> byPeriod,
                        Map<String, LeadQuality.Count[]> leadsByAd) {
        List<String> ids = adsInScope(byPeriod);
        if (ids.isEmpty()) {
            return "Hôm nay không có quảng cáo nào chạy hoặc tiêu tiền.\n";
        }
        StringBuilder sb = new StringBuilder();
        long running = ids.stream().filter(id -> byPeriod.get("today").get(id).active).count();
        sb.append("Hôm nay có ").append(ids.size()).append(" quảng cáo đã tiêu tiền hoặc đang chạy: ")
                .append(running).append(" đang chạy, ").append(ids.size() - running).append(" đã dừng.\n");

        for (String id : ids) {
            WorkerAnalysisService.Agg t = byPeriod.get("today").get(id);
            WorkerAnalysisService.Agg y = byPeriod.get("yesterday").get(id);
            WorkerAnalysisService.Agg total = byPeriod.get("total").get(id);
            sb.append("\n**").append(t.name).append("** — ").append(t.active ? "🟢 ĐANG CHẠY" : "⚪ ĐÃ DỪNG")
                    .append(t.budget > 0 ? ", ngân sách " + money(t.budget) : "")
                    .append("\n  Nhóm: ").append(t.setName).append(" · ").append(t.campaignName).append('\n');
            for (int i = 0; i < PERIODS.length; i++) {
                WorkerAnalysisService.Agg a = byPeriod.get(PERIODS[i]).get(id);
                sb.append("  ").append(LABELS[i]).append(": ").append(metrics(a));
                LeadQuality.Count[] lc = leadsByAd.get(id);
                if (lc != null && i < 4 && lc[i] != null && lc[i].total > 0) {
                    sb.append("\n      CRM: ").append(tags(lc[i]));
                }
                sb.append('\n');
            }
            String vs = compare(t, y);
            if (!vs.isEmpty()) {
                sb.append("  Hôm nay so với hôm qua: ").append(vs).append('\n');
            }
            sb.append("  Ngưỡng: ").append(verdict(total, byPeriod.get("last3").get(id))).append('\n');
        }

        sb.append("\n**Xếp hạng quảng cáo**\n");
        rank(sb, "Chi phí/SĐT 3 ngày (thấp là tốt)", ids, byPeriod.get("last3"), a -> a.phones > 0 ? a.spend / a.phones : -1, true, true);
        rank(sb, "Chi phí/tin nhắn hôm nay (thấp là tốt)", ids, byPeriod.get("today"), a -> a.messages > 0 ? a.spend / a.messages : -1, true, true);
        rank(sb, "CPM hôm nay (thấp là tốt)", ids, byPeriod.get("today"), a -> a.impressions > 0 ? a.spend * 1000 / a.impressions : -1, true, true);
        rank(sb, "CTR hôm nay (cao là tốt)", ids, byPeriod.get("today"), a -> a.impressions > 0 ? 100.0 * a.clicks / a.impressions : -1, false, false);
        rank(sb, "Chi phí/đơn 30 ngày (thấp là tốt)", ids, byPeriod.get("last30"), a -> a.orders > 0 ? a.spend / a.orders : -1, true, true);
        return sb.toString();
    }

    private static String metrics(WorkerAnalysisService.Agg a) {
        if (a == null || (a.spend <= 0 && a.impressions == 0)) {
            return "không chi";
        }
        return "chi " + money(a.spend)
                + " · CPM " + (a.impressions > 0 ? money(a.spend * 1000 / a.impressions) : "—")
                + " · CTR " + (a.impressions > 0 ? String.format(java.util.Locale.ROOT, "%.2f%%", 100.0 * a.clicks / a.impressions) : "—")
                + " · " + a.messages + " tin nhắn (" + (a.messages > 0 ? money(a.spend / a.messages) : "—") + "/tin)"
                + " · " + a.phones + " SĐT (" + (a.phones > 0 ? money(a.spend / a.phones) : "—") + "/SĐT)"
                + " · " + a.orders + " đơn (" + (a.orders > 0 ? money(a.spend / a.orders) : "—") + "/đơn"
                + (a.sales > 0 ? ", doanh số " + money(a.sales) : "") + ")";
    }

    private static String tags(LeadQuality.Count c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.total).append(" tin nhắn: ");
        List<String> parts = new ArrayList<>();
        for (LeadQuality.Kind k : LeadQuality.Kind.values()) {
            int n = c.of(k);
            if (n > 0) {
                parts.add(k.label + " " + n + (k == LeadQuality.Kind.IM_LANG && c.tagged() > 0 ? " (" + LeadQuality.Count.pct(n, c.tagged()) + " số đã gắn thẻ)" : ""));
            }
        }
        sb.append(String.join(", ", parts)).append(" · khách chất lượng ").append(c.qualityCount);
        return sb.toString();
    }

    private static String compare(WorkerAnalysisService.Agg t, WorkerAnalysisService.Agg y) {
        if (t == null || y == null || y.spend <= 0) {
            return y == null || y.spend <= 0 ? "hôm qua không chi" : "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("chi " + delta(t.spend, y.spend));
        if (t.impressions > 0 && y.impressions > 0) {
            parts.add("CPM " + money(y.spend * 1000 / y.impressions) + " → " + money(t.spend * 1000 / t.impressions)
                    + " (" + delta(t.spend * 1000 / t.impressions, y.spend * 1000 / y.impressions) + ")");
            parts.add(String.format(java.util.Locale.ROOT, "CTR %.2f%% → %.2f%%", 100.0 * y.clicks / y.impressions, 100.0 * t.clicks / t.impressions));
        }
        if (t.messages > 0 && y.messages > 0) {
            parts.add("chi phí/tin " + money(y.spend / y.messages) + " → " + money(t.spend / t.messages));
        }
        parts.add("SĐT " + y.phones + " → " + t.phones);
        if (t.phones > 0 && y.phones > 0) {
            parts.add("chi phí/SĐT " + money(y.spend / y.phones) + " → " + money(t.spend / t.phones));
        }
        return String.join(" · ", parts);
    }

    private static String delta(double now, double before) {
        if (before <= 0) {
            return "—";
        }
        long p = Math.round(100 * (now - before) / before);
        return (p > 0 ? "+" : "") + p + "%";
    }

    /** The owner's thresholds, counted since the ad started. */
    static String verdict(WorkerAnalysisService.Agg total, WorkerAnalysisService.Agg last3) {
        if (total == null || total.spend <= 0) {
            return "chưa chi";
        }
        List<String> out = new ArrayList<>();
        boolean sellsWell = total.orders > 0 && total.spend / total.orders < WorkerAnalysisService.GOOD_COST_PER_ORDER;
        if (total.spend >= WorkerAnalysisService.NO_PHONE_SPEND && total.phones == 0) {
            out.add("❌ đã chi " + money(total.spend) + " mà 0 SĐT (luật 500k) → nên tắt");
        } else if (!sellsWell && total.spend >= 1_500_000 && total.phones > 0 && total.spend / total.phones > WorkerAnalysisService.MAX_COST_PER_PHONE) {
            out.add("❌ " + money(total.spend / total.phones) + "/SĐT, vượt 350k (từ 1,5tr) → nên tắt");
        }
        if (total.spend >= 5_000_000 && total.orders == 0) {
            out.add("❌ đã chi " + money(total.spend) + " mà 0 đơn (luật 5tr) → nên tắt");
        }
        if (sellsWell) {
            out.add("✅ " + money(total.spend / total.orders) + "/đơn, dưới 2tr → có thể tăng 20%");
        }
        if (out.isEmpty()) {
            if (total.phones > 0 && total.spend / total.phones <= WorkerAnalysisService.MAX_COST_PER_PHONE) {
                out.add("✅ " + money(total.spend / total.phones) + "/SĐT, đạt (dưới 350k)");
            } else if (total.phones > 0) {
                out.add("⚠️ " + money(total.spend / total.phones) + "/SĐT, trên 350k nhưng chưa tới 1,5tr chi — theo dõi");
            } else {
                out.add("⚠️ chưa có SĐT, mới chi " + money(total.spend) + " (ngưỡng 500k)");
            }
        }
        if (last3 != null && last3.spend > 0) {
            out.add("3 ngày: " + (last3.phones > 0 ? money(last3.spend / last3.phones) + "/SĐT" : "0 SĐT với " + money(last3.spend))
                    + (last3.phones > 0 && last3.spend / last3.phones > WorkerAnalysisService.MAX_COST_PER_PHONE ? " ⚠️" : ""));
        }
        return String.join(" · ", out);
    }

    private static void rank(StringBuilder sb, String title, List<String> ids, Map<String, WorkerAnalysisService.Agg> period,
                             ToDoubleFunction<WorkerAnalysisService.Agg> value, boolean lowIsGood, boolean isMoney) {
        List<WorkerAnalysisService.Agg> rows = ids.stream().map(period::get)
                .filter(a -> a != null && value.applyAsDouble(a) >= 0).toList();
        if (rows.size() < 2) {
            return;
        }
        Comparator<WorkerAnalysisService.Agg> c = Comparator.comparingDouble(value);
        List<WorkerAnalysisService.Agg> sorted = new ArrayList<>(rows);
        sorted.sort(lowIsGood ? c : c.reversed());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            WorkerAnalysisService.Agg a = sorted.get(i);
            double v = value.applyAsDouble(a);
            parts.add((i + 1) + ". " + a.name + " " + (isMoney ? money(v) : String.format(java.util.Locale.ROOT, "%.2f%%", v)));
        }
        sb.append("- ").append(title).append(": ").append(String.join(" · ", parts)).append('\n');
    }

    /** The same comparison as rows for the page's table: one per ad, numbers per period, CRM tags, threshold check. */
    static List<Map<String, Object>> rows(Map<String, Map<String, WorkerAnalysisService.Agg>> byPeriod,
                                          Map<String, LeadQuality.Count[]> leadsByAd) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : adsInScope(byPeriod)) {
            WorkerAnalysisService.Agg t = byPeriod.get("today").get(id);
            WorkerAnalysisService.Agg total = byPeriod.get("total").get(id);
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", id);
            row.put("name", t.name);
            row.put("setName", t.setName);
            row.put("campaignName", t.campaignName);
            row.put("active", t.active);
            row.put("budget", Math.round(t.budget));
            Map<String, Object> periods = new java.util.LinkedHashMap<>();
            LeadQuality.Count[] lc = leadsByAd.get(id);
            for (int i = 0; i < PERIODS.length; i++) {
                WorkerAnalysisService.Agg a = byPeriod.get(PERIODS[i]).get(id);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("spend", a == null ? 0 : Math.round(a.spend));
                m.put("impressions", a == null ? 0 : a.impressions);
                m.put("clicks", a == null ? 0 : a.clicks);
                m.put("messages", a == null ? 0 : a.messages);
                m.put("phones", a == null ? 0 : a.phones);
                m.put("orders", a == null ? 0 : a.orders);
                m.put("sales", a == null ? 0 : Math.round(a.sales));
                // CRM tags exist for today / yesterday / 3 days / 30 days; "since launch" reuses the 30 days.
                int ci = Math.min(i, 3);
                if (lc != null && lc[ci] != null) {
                    m.put("crm", lc[ci].toMap());
                }
                periods.put(PERIODS[i], m);
            }
            row.put("periods", periods);
            String verdict = verdict(total, byPeriod.get("last3").get(id));
            row.put("verdict", verdict);
            row.put("level", verdict.contains("❌") ? "bad" : verdict.startsWith("✅") ? "good" : "warn");
            out.add(row);
        }
        return out;
    }
}
