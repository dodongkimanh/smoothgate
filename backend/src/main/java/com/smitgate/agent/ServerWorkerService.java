package com.smitgate.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs every account worker on the Smoothgate server, no phone needed (what worker/worker.mjs did on a phone):
 * every 15 minutes it checks each account against the owner's rules and files new alerts; at 12:00 and 21:00 it
 * runs the AI analysis (Sunday 21:00 is the weekly review); at 23:30 the end-of-day summary. It only reads
 * Smoothgate and the CRM — never Meta — and never turns ads on or off.
 * Every Meta ad account gets a worker of its own automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerWorkerService {

    static final int CHECK_MINUTES = 15;
    static final String[] ANALYSIS_SLOTS = {"12:00", "21:00"};
    static final String DAILY_CLOSE_AT = "23:30";

    // The owner's rules (spend counted since the ad set / campaign started, i.e. the 90-day "total").
    static final double NO_PHONE_SPEND = 500_000;
    static final double COST_PER_PHONE_SPEND = 1_500_000;
    static final double MAX_COST_PER_PHONE = 350_000;
    static final double NO_ORDER_SPEND = 5_000_000;
    static final double GOOD_COST_PER_ORDER = 2_000_000;
    static final double CPM_SPIKE_RATIO = 1.8;
    static final double CPM_SPIKE_MIN_SPEND = 150_000;
    static final int SILENT_MIN_TAGGED = 10;
    static final double SILENT_RATE = 0.7;
    private static final String LATE_ORDERS = "(Đơn có thể chốt muộn 3–6 ngày sau khi khách để lại SĐT.)";

    private final JdbcTemplate jdbc;
    private final AdWorkerService workers;
    private final WorkerAnalysisService analysis;

    @Value("${app.workers.server-enabled:true}")
    private boolean enabled;

    /** Checks are quick (database only); AI analyses are slow, so they get their own queue. */
    private final ExecutorService checks = Executors.newFixedThreadPool(2);
    private final ExecutorService analyses = Executors.newSingleThreadExecutor();
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    /** Today's orders per ad at the previous check, to announce new orders. */
    private final Map<Long, Map<String, Double>> lastOrders = new ConcurrentHashMap<>();
    private volatile long lastEnsure;

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        if (!enabled) return;
        try {
            if (System.currentTimeMillis() - lastEnsure > 3_600_000L) {
                ensureWorkers();
                lastEnsure = System.currentTimeMillis();
            }
            LocalDateTime now = LocalDateTime.now(AdWorkerService.VIETNAM_ZONE);
            for (AdWorkerService.Worker w : workers.serverWorkers()) {
                schedule(w, now);
            }
        } catch (Exception e) {
            log.warn("Server workers tick failed: {}", e.getMessage());
        }
    }

    /** Every Meta ad account without a worker gets one ("Worker 07" for TKQC07US). */
    void ensureWorkers() {
        int n = jdbc.update("""
                insert into ad_workers (tenant_id, name, ad_account_id, key_hash, enabled, device, mode, created_at)
                select a.tenant_id, 'Worker ' || coalesce(substring(a.name from 'TKQC(\\d+)'), a.name), a.id,
                       md5(random()::text) || md5(clock_timestamp()::text), true, 'Server', 'server', now()
                from ad_accounts a
                where a.platform = 'META' and not exists (select 1 from ad_workers w where w.ad_account_id = a.id)
                """);
        if (n > 0) log.info("Server workers: created {} new workers", n);
    }

    private void schedule(AdWorkerService.Worker w, LocalDateTime now) {
        if (busy.contains(w.id())) return;
        String day = now.toLocalDate().toString();
        int minutes = now.getHour() * 60 + now.getMinute();

        Run check = run(w.id(), "check");
        if (check.lastTry == null || java.time.Duration.between(check.lastTry, now).toMinutes() >= CHECK_MINUTES) {
            submit(checks, w, "check", () -> check(w));
            return;
        }
        for (String slot : ANALYSIS_SLOTS) {
            int start = minutes(slot);
            String key = "analysis:" + day + ":" + slot;
            Run r = run(w.id(), key);
            if (minutes >= start && minutes < start + 180 && r.doneAt == null && r.tries < 3
                    && (r.lastTry == null || java.time.Duration.between(r.lastTry, now).toMinutes() >= 30)) {
                // created_at columns hold UTC (now() on a UTC database).
                LocalDateTime slotAt = now.toLocalDate().atStartOfDay().plusMinutes(start).minusHours(7);
                // Already analysed since the slot (a phone, or "Phân tích ngay"), or the worker did not exist yet then.
                Integer seen = jdbc.queryForObject("""
                        select (select count(*) from ad_worker_reports where worker_id = ? and kind = 'ANALYSIS' and created_at >= ?)
                             + (select count(*) from ad_workers where id = ? and created_at > ?)
                        """, Integer.class, w.id(), slotAt, w.id(), slotAt);
                if (seen != null && seen > 0) {
                    tried(w.id(), key);
                    done(w.id(), key);
                    continue;
                }
                tried(w.id(), key);
                submit(analyses, w, key, () -> {
                    Map<String, Object> res = analysis.analyze(w, slot);
                    if (!Boolean.TRUE.equals(res.get("ok"))) throw new IllegalStateException(String.valueOf(res.get("error")));
                    done(w.id(), key);
                    String what = Boolean.TRUE.equals(res.get("skipped")) ? "bỏ qua (tài khoản không chạy)"
                            : "xong, " + res.getOrDefault("recommendations", 0) + " đề xuất mới, chấm " + res.getOrDefault("evaluated", 0) + " đề xuất cũ";
                    workers.touch(w, "Server", "Phân tích " + slot + ": " + what);
                });
                return;
            }
        }
        String closeKey = "close:" + day;
        Run c = run(w.id(), closeKey);
        if (minutes >= minutes(DAILY_CLOSE_AT) && c.doneAt == null && c.tries < 3
                && (c.lastTry == null || java.time.Duration.between(c.lastTry, now).toMinutes() >= 5)) {
            tried(w.id(), closeKey);
            submit(checks, w, closeKey, () -> {
                Map<String, Object> res = analysis.dailyClose(w);
                if (!Boolean.TRUE.equals(res.get("ok"))) throw new IllegalStateException(String.valueOf(res.get("error")));
                done(w.id(), closeKey);
            });
        }
    }

    private void submit(ExecutorService pool, AdWorkerService.Worker w, String what, ThrowingRunnable job) {
        busy.add(w.id());
        pool.submit(() -> {
            try {
                job.run();
            } catch (Exception e) {
                log.warn("Server worker {} {} failed: {}", w.name(), what, e.getMessage());
                String hm = LocalDateTime.now(AdWorkerService.VIETNAM_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"));
                workers.touch(w, "Server", ("Lỗi lúc " + hm + ": " + e.getMessage()));
            } finally {
                busy.remove(w.id());
            }
        });
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---------- the 15-minute check ----------

    @SuppressWarnings("unchecked")
    void check(AdWorkerService.Worker w) {
        tried(w.id(), "check");
        LocalDateTime now = LocalDateTime.now(AdWorkerService.VIETNAM_ZONE);
        Map<String, List<Map<String, Object>>> live = workers.liveNumbers(w);
        Map<String, Object> quality = null;
        try {
            quality = analysis.liveQuality(w, live);
        } catch (Exception e) {
            log.warn("Server worker {} lead quality failed: {}", w.name(), e.getMessage());
        }
        List<Map<String, Object>> found = evaluate(w.id(), live, quality, now);
        for (Map<String, Object> a : found) {
            a.put("kind", "info".equals(a.get("severity")) ? "INFO" : "ALERT");
        }
        int added = found.isEmpty() ? 0 : workers.saveReports(w, found);
        List<Map<String, Object>> t = live.getOrDefault("today", List.of());
        String qText = "";
        if (quality != null && quality.get("today") instanceof Map<?, ?> qt && num(qt.get("total")) > 0) {
            qText = " · CRM: " + (long) num(qt.get("quality")) + "/" + (long) num(qt.get("total")) + " khách chất lượng, "
                    + (long) num(qt.get("IM_LANG")) + " im lặng";
        }
        String status = "Kiểm tra " + now.format(DateTimeFormatter.ofPattern("HH:mm")) + " · hôm nay chi " + money(sum(t, "spend"))
                + ", " + (long) sum(t, "messageContacts") + " tin nhắn, " + (long) sum(t, "phoneCount") + " SĐT, "
                + (long) sum(t, "orderCount") + " đơn" + qText + " · " + added + " cảnh báo mới";
        workers.touch(w, "Server", status);
        done(w.id(), "check");
    }

    /** The owner's rules, the same as the phone worker had. Returns alerts with a ref_key (filed once). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> evaluate(long workerId, Map<String, List<Map<String, Object>>> live,
                                       Map<String, Object> quality, LocalDateTime now) {
        List<Map<String, Object>> out = new ArrayList<>();
        String day = now.toLocalDate().toString();
        String hm = now.format(DateTimeFormatter.ofPattern("HH:mm"));
        List<Map<String, Object>> todayAds = live.getOrDefault("today", List.of());
        List<Group> today = byAdSet(todayAds);
        List<Group> week = byAdSet(live.getOrDefault("last7", List.of()));
        List<Group> total = byAdSet(live.getOrDefault("total", live.getOrDefault("last7", List.of())));
        Map<String, Group> weekById = new HashMap<>();
        week.forEach(g -> weekById.put(g.id, g));
        Map<String, Group> todayById = new HashMap<>();
        today.forEach(g -> todayById.put(g.id, g));
        long threeDay = LocalDate.parse(day).toEpochDay() / 3;

        for (Group g : total) {
            Group t = todayById.get(g.id);
            if (t == null || !t.active) continue; // only judge ad sets still running
            Group w7 = weekById.get(g.id);
            String name = g.name + " (" + nz(g.campaignName) + ")";
            boolean sellsWell = g.orders > 0 && g.spend / g.orders < GOOD_COST_PER_ORDER;
            String facts = "Từ khi chạy: chi " + money(g.spend) + ", " + (long) g.messages + " tin nhắn, " + (long) g.phones + " SĐT, " + (long) g.orders + " đơn.";
            if (g.spend >= NO_PHONE_SPEND && g.phones == 0) {
                out.add(alert("high", "nophone:" + g.id + ":" + day, g.name + ": đã chi " + money(g.spend) + " mà chưa có SĐT → nên tắt",
                        name + "\n" + facts + "\nLuật: nhóm tiêu 500k mà 0 SĐT thì tắt."));
            } else if (!sellsWell && g.spend >= COST_PER_PHONE_SPEND && g.phones > 0 && g.spend / g.phones > MAX_COST_PER_PHONE) {
                out.add(alert("high", "cpp:" + g.id + ":" + day, g.name + ": " + money(g.spend / g.phones) + "/SĐT, vượt 350k → nên tắt",
                        name + "\n" + facts + "\nLuật: từ 1,5tr mà trên 350k/SĐT thì tắt."));
            }
            if (g.spend >= NO_ORDER_SPEND && g.orders == 0) {
                out.add(alert("high", "noorder:" + g.id + ":" + day, g.name + ": đã chi " + money(g.spend) + " mà chưa có đơn → nên tắt",
                        name + "\n" + facts + "\nLuật: nhóm hoặc chiến dịch tiêu 5tr mà 0 đơn thì tắt. " + LATE_ORDERS));
            }
            if (sellsWell) {
                out.add(alert("info", "scale:" + g.id + ":" + threeDay, g.name + ": " + money(g.spend / g.orders) + "/đơn, dưới 2tr → có thể tăng 20% ngân sách",
                        name + "\n" + facts + "\nLuật: dưới 2tr/đơn thì tăng 20%, mỗi lần cách nhau 3 ngày."));
            }
            if (w7 != null && t.spend >= CPM_SPIKE_MIN_SPEND && w7.cpm() > 0 && t.cpm() > w7.cpm() * CPM_SPIKE_RATIO) {
                out.add(alert("medium", "cpm:" + g.id + ":" + day,
                        g.name + ": CPM hôm nay " + money(t.cpm()) + ", cao gấp " + String.format(java.util.Locale.ROOT, "%.1f", t.cpm() / w7.cpm()) + " lần trung bình 7 ngày",
                        name + "\nHôm nay: chi " + money(t.spend) + ", " + (long) t.impressions + " hiển thị, CPM " + money(t.cpm()) + ". CPM 7 ngày: " + money(w7.cpm()) + "."));
            }
            double adsetBudget = t.budgetSource != null && !t.budgetSource.toLowerCase().contains("camp") ? t.budget : 0;
            if (now.getHour() >= 15 && adsetBudget > 0 && t.spend < adsetBudget * 0.25) {
                out.add(alert("medium", "slow:" + g.id + ":" + day, g.name + ": đến " + hm + " mới tiêu " + money(t.spend) + "/" + money(adsetBudget) + " ngân sách",
                        name + "\nNhóm đang bật nhưng phân phối chậm. Kiểm tra trạng thái duyệt quảng cáo, đối tượng quá hẹp hoặc giá thầu."));
            }
        }

        // Each ad on its own, when its ad set has several ads (with one ad, the ad set alert above covers it).
        Map<String, Map<String, Object>> adsTotal = new LinkedHashMap<>();
        for (Map<String, Object> a : live.getOrDefault("total", List.of())) {
            Map<String, Object> r = adsTotal.computeIfAbsent(str(a.get("adId")), k -> {
                Map<String, Object> m = new HashMap<>(a);
                m.put("spend", 0d);
                m.put("phoneCount", 0d);
                m.put("orderCount", 0d);
                m.put("messageContacts", 0d);
                return m;
            });
            for (String f : List.of("spend", "phoneCount", "orderCount", "messageContacts")) {
                r.put(f, num(r.get(f)) + num(a.get(f)));
            }
        }
        Map<String, Integer> adsPerSet = new HashMap<>();
        for (Map<String, Object> a : adsTotal.values()) adsPerSet.merge(str(a.get("adSetId")), 1, Integer::sum);
        Set<String> activeAds = new java.util.HashSet<>();
        for (Map<String, Object> a : todayAds) {
            if ("ACTIVE".equalsIgnoreCase(str(a.get("delivery")))) activeAds.add(str(a.get("adId")));
        }
        for (Map<String, Object> a : adsTotal.values()) {
            String id = str(a.get("adId"));
            if (!activeAds.contains(id) || adsPerSet.getOrDefault(str(a.get("adSetId")), 0) < 2) continue;
            double spend = num(a.get("spend")), phones = num(a.get("phoneCount")), orders = num(a.get("orderCount"));
            String name = "Quảng cáo \"" + str(a.get("adName")) + "\" (nhóm " + str(a.get("adSetName")) + ")";
            String facts = "Từ khi chạy: chi " + money(spend) + ", " + (long) num(a.get("messageContacts")) + " tin nhắn, " + (long) phones + " SĐT, " + (long) orders + " đơn.";
            boolean sellsWell = orders > 0 && spend / orders < GOOD_COST_PER_ORDER;
            if (spend >= NO_PHONE_SPEND && phones == 0) {
                out.add(alert("high", "ad-nophone:" + id + ":" + day, name + ": đã chi " + money(spend) + " mà chưa có SĐT → nên tắt quảng cáo này",
                        facts + "\nLuật: tiêu 500k mà 0 SĐT thì tắt."));
            } else if (!sellsWell && spend >= COST_PER_PHONE_SPEND && phones > 0 && spend / phones > MAX_COST_PER_PHONE) {
                out.add(alert("high", "ad-cpp:" + id + ":" + day, name + ": " + money(spend / phones) + "/SĐT, vượt 350k → nên tắt quảng cáo này",
                        facts + "\nLuật: từ 1,5tr mà trên 350k/SĐT thì tắt."));
            }
            if (spend >= NO_ORDER_SPEND && orders == 0) {
                out.add(alert("high", "ad-noorder:" + id + ":" + day, name + ": đã chi " + money(spend) + " mà chưa có đơn → nên tắt quảng cáo này",
                        facts + "\nLuật: tiêu 5tr mà 0 đơn thì tắt. " + LATE_ORDERS));
            }
        }

        // Whole campaign: 5tr spent since it started with no order, while some of its ad sets still run.
        Map<String, double[]> camps = new LinkedHashMap<>(); // spend, phones, orders, active, sets
        Map<String, String> campNames = new HashMap<>();
        for (Group g : total) {
            double[] c = camps.computeIfAbsent(nz(g.campaignId), k -> new double[5]);
            c[0] += g.spend;
            c[1] += g.phones;
            c[2] += g.orders;
            Group t = todayById.get(g.id);
            if (t != null && t.active) c[3] = 1;
            c[4] += 1;
            campNames.put(nz(g.campaignId), g.campaignName);
        }
        for (Map.Entry<String, double[]> e : camps.entrySet()) {
            double[] c = e.getValue();
            if (c[3] > 0 && c[0] >= NO_ORDER_SPEND && c[2] == 0) {
                out.add(alert("high", "noorder-camp:" + e.getKey() + ":" + day,
                        "Chiến dịch \"" + campNames.get(e.getKey()) + "\": đã chi " + money(c[0]) + " mà chưa có đơn → nên tắt",
                        "Từ khi chạy: " + (long) c[4] + " nhóm, chi " + money(c[0]) + ", " + (long) c[1] + " SĐT, 0 đơn.\nLuật: nhóm hoặc chiến dịch tiêu 5tr mà 0 đơn thì tắt. " + LATE_ORDERS));
            }
        }

        // Message quality (CRM tags, updated by sales all day).
        if (quality != null && quality.get("error") == null) {
            for (Map<String, Object> g : (List<Map<String, Object>>) quality.getOrDefault("adSets", List.of())) silent(out, "Nhóm", g, day);
            for (Map<String, Object> a : (List<Map<String, Object>>) quality.getOrDefault("ads", List.of())) silent(out, "Quảng cáo", a, day);
            for (Map<String, Object> g : (List<Map<String, Object>>) quality.getOrDefault("adSets", List.of())) {
                Map<String, Object> t = (Map<String, Object>) g.getOrDefault("today", Map.of());
                long good = (long) (num(t.get("TIEM_NANG")) + num(t.get("CHOT")));
                if (good > 0) {
                    long chot = (long) num(t.get("CHOT"));
                    out.add(alert("info", "potential:" + g.get("id") + ":" + day + ":" + good,
                            "⭐ \"" + g.get("name") + "\": hôm nay " + good + " khách tiềm năng" + (chot > 0 ? " (" + chot + " chốt đơn)" : ""),
                            "Hôm nay: " + qualityLine(t) + "\nChi hôm nay: " + money(num(g.get("spendToday"))) + "."));
                }
            }
        }

        // Good news between two checks: a new order on an ad.
        Map<String, Double> prev = lastOrders.get(workerId);
        Map<String, Double> nowOrders = new HashMap<>();
        for (Map<String, Object> a : todayAds) {
            String id = str(a.get("adId"));
            double orders = num(a.get("orderCount"));
            nowOrders.put(id, orders);
            Double before = prev == null ? null : prev.get(id);
            if (before != null && orders > before) {
                out.add(alert("info", "order:" + id + ":" + day + ":" + (long) orders,
                        "🎉 Có đơn mới từ \"" + str(a.get("adName")) + "\" (hôm nay " + (long) orders + " đơn)",
                        str(a.get("adSetName")) + " · " + str(a.get("campaignName")) + "\nHôm nay: chi " + money(num(a.get("spend"))) + ", "
                                + (long) num(a.get("phoneCount")) + " SĐT, " + (long) orders + " đơn, doanh số " + money(num(a.get("sales"))) + "."));
            }
        }
        lastOrders.put(workerId, nowOrders);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void silent(List<Map<String, Object>> out, String label, Map<String, Object> item, String day) {
        if (!Boolean.TRUE.equals(item.get("active"))) return;
        Map<String, Object> w = (Map<String, Object>) item.getOrDefault("week", Map.of());
        double tagged = num(w.get("tagged"));
        if (tagged < SILENT_MIN_TAGGED) return;
        double rate = num(w.get("IM_LANG")) / tagged;
        if (rate < SILENT_RATE) return;
        double spendWeek = num(item.get("spendWeek"));
        double q = num(w.get("quality"));
        out.add(alert("medium", "silent:" + item.get("id") + ":" + day,
                label + " \"" + item.get("name") + "\": " + Math.round(rate * 100) + "% khách im lặng (" + (long) num(w.get("IM_LANG")) + "/" + (long) tagged + " tin nhắn đã gắn thẻ, 7 ngày)",
                qualityLine(w) + "\nChi 7 ngày: " + money(spendWeek) + (q > 0 ? ", " + money(spendWeek / q) + "/khách chất lượng" : ", chưa có khách chất lượng")
                        + ".\nNhiều khách chỉ bấm câu hỏi mẫu rồi im: nhắm đối tượng hoặc nội dung đang kéo người tò mò."));
    }

    private static final String[][] KIND_LABEL = {{"CHOT", "chốt đơn"}, {"TIEM_NANG", "tiềm năng"}, {"CO_TUONG_TAC", "có tương tác"},
            {"HOI_GIA", "hỏi giá xong im"}, {"IM_LANG", "im lặng"}, {"KHONG_NHU_CAU", "không nhu cầu"}, {"CHUA_GAN", "chưa gắn thẻ"}};

    private static String qualityLine(Map<String, Object> c) {
        if (c == null || num(c.get("total")) == 0) return "0 tin nhắn trong CRM";
        List<String> parts = new ArrayList<>();
        for (String[] k : KIND_LABEL) {
            if (num(c.get(k[0])) > 0) parts.add(k[1] + " " + (long) num(c.get(k[0])));
        }
        return (long) num(c.get("total")) + " tin nhắn: " + String.join(", ", parts) + " · có SĐT " + (long) num(c.get("phones"))
                + " · khách chất lượng " + (long) num(c.get("quality"));
    }

    private static Map<String, Object> alert(String severity, String refKey, String title, String body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("refKey", refKey);
        m.put("title", title);
        m.put("body", body);
        return m;
    }

    private static final class Group {
        String id, name, campaignId, campaignName, budgetSource;
        boolean active;
        double budget, spend, impressions, messages, phones, orders, sales;

        double cpm() {
            return impressions > 0 ? spend * 1000 / impressions : 0;
        }
    }

    private static List<Group> byAdSet(List<Map<String, Object>> ads) {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Map<String, Object> a : ads) {
            String k = str(a.get("adSetId")).isEmpty() ? str(a.get("adId")) : str(a.get("adSetId"));
            Group g = m.computeIfAbsent(k, x -> {
                Group n = new Group();
                n.id = x;
                n.name = str(a.get("adSetName")).isEmpty() ? str(a.get("adName")) : str(a.get("adSetName"));
                n.campaignId = str(a.get("campaignId"));
                n.campaignName = str(a.get("campaignName"));
                n.budgetSource = a.get("budgetSource") == null ? null : a.get("budgetSource").toString();
                return n;
            });
            g.active |= "ACTIVE".equalsIgnoreCase(str(a.get("delivery")));
            g.budget = Math.max(g.budget, num(a.get("budget")));
            g.spend += num(a.get("spend"));
            g.impressions += num(a.get("impressions"));
            g.messages += num(a.get("messageContacts"));
            g.phones += num(a.get("phoneCount"));
            g.orders += num(a.get("orderCount"));
            g.sales += num(a.get("sales"));
        }
        return new ArrayList<>(m.values());
    }

    // ---------- bookkeeping ----------

    private record Run(int tries, LocalDateTime lastTry, LocalDateTime doneAt) {
    }

    private Run run(long workerId, String key) {
        return jdbc.query("select tries, last_try_at, done_at from ad_worker_runs where worker_id = ? and run_key = ?",
                (rs, i) -> new Run(rs.getInt(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toLocalDateTime(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toLocalDateTime()),
                workerId, key).stream().findFirst().orElse(new Run(0, null, null));
    }

    private void tried(long workerId, String key) {
        LocalDateTime now = LocalDateTime.now(AdWorkerService.VIETNAM_ZONE);
        jdbc.update("""
                insert into ad_worker_runs (worker_id, run_key, tries, last_try_at) values (?, ?, 1, ?)
                on conflict (worker_id, run_key) do update set tries = ad_worker_runs.tries + 1, last_try_at = excluded.last_try_at
                """, workerId, key, now);
        if (key.startsWith("check")) {
            jdbc.update("delete from ad_worker_runs where worker_id = ? and run_key <> 'check' and last_try_at < ?", workerId, now.minusDays(14));
        }
    }

    private void done(long workerId, String key) {
        jdbc.update("update ad_worker_runs set done_at = ? where worker_id = ? and run_key = ?",
                LocalDateTime.now(AdWorkerService.VIETNAM_ZONE), workerId, key);
    }

    private static int minutes(String hm) {
        String[] p = hm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static double sum(List<Map<String, Object>> rows, String f) {
        return rows.stream().mapToDouble(r -> num(r.get(f))).sum();
    }

    static String money(double v) {
        if (v >= 1_000_000) {
            String s = String.format(java.util.Locale.ROOT, v >= 10_000_000 ? "%.0f" : "%.2f", v / 1_000_000);
            if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "").replace('.', ',');
            return s + "tr";
        }
        if (v >= 1000) return Math.round(v / 1000) + "k";
        return Math.round(v) + "đ";
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
