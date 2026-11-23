package com.smitgate.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The worker's thinking side, run on the server so it survives a phone swap:
 * <ul>
 *   <li>{@link #analyze}: at 12h and 21h, builds the full picture (live numbers, 30-day history, winning and
 *   money-burning ads, alerts, due suggestions with what happened since, past lessons and analyses), asks Claude,
 *   stores the write-up, records its new suggestions and the verdicts on old ones. Sunday 21h is the weekly review.</li>
 *   <li>{@link #dailyClose}: at 23:30, a rule-based end-of-day summary (no AI).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerAnalysisService {

    static final int MAX_ANALYSES_PER_DAY = 6;
    static final double MAX_COST_PER_PHONE = 350_000;
    static final double GOOD_COST_PER_ORDER = 2_000_000;
    static final double NO_PHONE_SPEND = 500_000;
    private static final Set<String> ACTIONS = Set.of("TAT", "TANG_NGAN_SACH", "GIAM_NGAN_SACH", "GIU", "THEO_DOI", "KHAC");
    private static final Set<String> VERDICTS = Set.of("DUNG", "SAI", "CHUA_RO");

    private final AdWorkerService workers;
    private final WorkerMemoryService memory;
    private final ClaudeAnalysisService claude;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final com.smitgate.connector.ads.CrmCustomerSource crm;

    // ---------------------------------------------------------------- analysis

    public Map<String, Object> analyze(AdWorkerService.Worker w, String slot) {
        if (!claude.isConfigured()) {
            return Map.of("ok", false, "error", "Claude chưa được cấu hình trên server");
        }
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        LocalDateTime now = LocalDateTime.now(AdWorkerService.VIETNAM_ZONE);
        Integer done = jdbc.queryForObject(
                "select count(*) from ad_worker_reports where worker_id = ? and kind = 'ANALYSIS' and not hidden and created_at >= ?",
                Integer.class, w.id(), today.atStartOfDay());
        if (done != null && done >= MAX_ANALYSES_PER_DAY) {
            return Map.of("ok", false, "error", "Đã đủ " + MAX_ANALYSES_PER_DAY + " lần phân tích hôm nay");
        }

        Map<String, List<Map<String, Object>>> live = workers.liveNumbersFull(w);
        List<Map<String, Object>> due = memory.dueRecommendations(w, today);
        if (sum(live.get("last7"), "spend") <= 0 && due.isEmpty()) {
            return Map.of("ok", true, "skipped", true, "text", "Tài khoản không chi tiêu trong 7 ngày — bỏ qua phân tích.");
        }
        boolean weekly = today.getDayOfWeek() == DayOfWeek.SUNDAY && slot != null && slot.startsWith("21");
        List<Map<String, Object>> history = memory.dailyHistory(w, today.minusDays(29));

        Map<String, Agg> todaySets = aggregate(live.get("today"), a -> str(a.get("adSetId")));
        Map<String, Agg> totalSets = aggregate(live.get("total"), a -> str(a.get("adSetId")));
        Map<String, Agg> weekSets = aggregate(live.get("last7"), a -> str(a.get("adSetId")));
        Map<String, Agg> todayCamps = aggregate(live.get("today"), a -> str(a.get("campaignId")));
        Map<String, Agg> totalCamps = aggregate(live.get("total"), a -> str(a.get("campaignId")));
        Map<String, Agg> weekCamps = aggregate(live.get("last7"), a -> str(a.get("campaignId")));

        // What happened to each due suggestion: was it followed, and the numbers since.
        List<String> dueLines = new ArrayList<>();
        Set<Long> dueIds = new HashSet<>();
        for (Map<String, Object> r : due) {
            long id = ((Number) r.get("id")).longValue();
            dueIds.add(id);
            boolean campaign = "campaign".equals(r.get("level"));
            String entityId = str(r.get("entityId"));
            Agg nowAgg = (campaign ? todayCamps : todaySets).get(entityId);
            Map<String, Object> base = parse(str(r.get("baseline")));
            String followed = followed(str(r.get("action")), nowAgg, base);
            Map<String, Object> after = after(history, campaign ? "campaignId" : "adSetId", entityId, LocalDate.parse(str(r.get("createdDate"))));
            memory.recordFollowUp(id, followed, json(after));
            dueLines.add("- id " + id + " · " + r.get("createdDate") + " · " + r.get("action") + " " + r.get("level") + " \"" + r.get("entityName") + "\": " + r.get("detail")
                    + "\n    Lúc đề xuất: " + describeBaseline(base)
                    + "\n    Có làm theo không: " + followedText(followed) + (nowAgg != null && nowAgg.active ? " (hiện vẫn đang chạy)" : " (hiện không chạy)")
                    + "\n    Từ sau ngày đề xuất đến nay: chi " + money(num(after.get("spend"))) + ", " + num(after.get("messages")) + " tin nhắn, "
                    + (long) num(after.get("phones")) + " SĐT, " + (long) num(after.get("orders")) + " đơn, doanh số " + money(num(after.get("revenue"))));
        }

        Map<String, Map<String, Agg>> byPeriod = adsByPeriod(live);
        LeadStats leadStats = leadStats(live, today);
        Map<String, LeadQuality.Count[]> leadsByAd = leadStats == null ? Map.of() : leadStats.adPeriods;
        String adTable = WorkerAdReport.build(byPeriod, leadsByAd);
        String adData = json(Map.of("ads", WorkerAdReport.rows(byPeriod, leadsByAd)));
        String context = "## Bảng so sánh từng quảng cáo (server tính, số chính xác)\n" + adTable + "\n"
                + buildContext(w, now, slot, weekly, todaySets, weekSets, totalSets, totalCamps, live, history, dueLines);
        String reply = claude.complete(WorkerPrompts.ANALYSIS_SYSTEM, context);
        if (reply == null || reply.isBlank()) {
            String why = claude.getLastError();
            String err = why != null ? why : "Không nhận được phân tích từ Claude";
            // The computed comparison is still worth having without the AI write-up.
            jdbc.update("""
                    insert into ad_worker_reports (worker_id, kind, severity, ref_key, title, body, data)
                    values (?, 'ANALYSIS', 'info', ?, ?, ?, ?)
                    """, w.id(), "adtable:" + today + ":" + now.format(DateTimeFormatter.ofPattern("HHmmss")),
                    "Bảng so sánh quảng cáo " + w.accountName() + (slot == null || slot.isBlank() ? "" : " lúc " + slot),
                    "(Phần nhận xét của AI chưa chạy được: " + err + ")", adData);
            return Map.of("ok", false, "error", err + " — đã lưu bảng so sánh quảng cáo (không có nhận xét AI).");
        }

        // The reply ends with a ```json block of suggestions and verdicts; keep it out of the displayed text.
        JsonNode structured = null;
        String text = reply;
        int start = reply.lastIndexOf("```json");
        if (start >= 0) {
            int end = reply.indexOf("```", start + 7);
            String raw = reply.substring(start + 7, end > 0 ? end : reply.length()).trim();
            try {
                structured = mapper.readTree(raw);
                text = (reply.substring(0, start) + (end > 0 ? reply.substring(end + 3) : "")).trim();
            } catch (Exception e) {
                log.warn("Worker {} analysis JSON could not be parsed: {}", w.name(), e.getMessage());
            }
        }

        String title = (weekly ? "Tổng kết tuần " : "Phân tích ") + w.accountName() + (slot == null || slot.isBlank() ? "" : " lúc " + slot);
        Long reportId = jdbc.queryForObject("""
                insert into ad_worker_reports (worker_id, kind, severity, ref_key, title, body, data)
                values (?, 'ANALYSIS', 'info', ?, ?, ?, ?) returning id
                """, Long.class, w.id(), "analysis:" + today + ":" + slot + ":" + now.format(DateTimeFormatter.ofPattern("HHmmss")),
                title, text, adData);

        int saved = 0;
        int judged = 0;
        if (structured != null) {
            for (JsonNode r : structured.path("recommendations")) {
                if (saved >= 8) {
                    break;
                }
                String level = r.path("level").asText("").toLowerCase();
                String entityId = r.path("entityId").asText("");
                String action = r.path("action").asText("").toUpperCase();
                Agg agg = "campaign".equals(level) ? totalCamps.get(entityId) : "adset".equals(level) ? totalSets.get(entityId) : null;
                if (agg == null || !ACTIONS.contains(action)) {
                    continue;
                }
                Agg week = ("campaign".equals(level) ? weekCamps : weekSets).get(entityId);
                Agg tday = ("campaign".equals(level) ? todayCamps : todaySets).get(entityId);
                Map<String, Object> baseline = new LinkedHashMap<>();
                baseline.put("spendTotal", Math.round(agg.spend));
                baseline.put("phonesTotal", agg.phones);
                baseline.put("ordersTotal", agg.orders);
                baseline.put("spend7", week == null ? 0 : Math.round(week.spend));
                baseline.put("phones7", week == null ? 0 : week.phones);
                baseline.put("orders7", week == null ? 0 : week.orders);
                baseline.put("budget", tday == null ? 0 : Math.round(tday.budget));
                baseline.put("active", tday != null && tday.active);
                memory.saveRecommendation(w, reportId, level, entityId, r.path("entityName").asText(agg.name), action,
                        r.path("detail").asText(""), json(baseline));
                saved++;
            }
            for (JsonNode e : structured.path("evaluations")) {
                long id = e.path("id").asLong(-1);
                String verdict = e.path("verdict").asText("").toUpperCase();
                if (dueIds.contains(id) && VERDICTS.contains(verdict)) {
                    String lesson = e.path("lesson").asText("");
                    judged += memory.applyVerdict(w, id, verdict, lesson.length() > 600 ? lesson.substring(0, 600) : lesson);
                }
            }
        }
        return Map.of("ok", true, "text", text, "recommendations", saved, "evaluated", judged);
    }

    private String buildContext(AdWorkerService.Worker w, LocalDateTime now, String slot, boolean weekly,
                                Map<String, Agg> todaySets, Map<String, Agg> weekSets, Map<String, Agg> totalSets,
                                Map<String, Agg> totalCamps, Map<String, List<Map<String, Object>>> live,
                                List<Map<String, Object>> history, List<String> dueLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là ").append(w.name()).append(", được giao tài khoản này. Tài khoản: ").append(w.accountName()).append(" (").append(w.externalAccountId()).append("). Thời điểm: ")
                .append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(" giờ Việt Nam, ")
                .append(now.getDayOfWeek()).append(". Lần phân tích: ").append(slot).append(".\n");
        if (weekly) {
            sb.append("ĐÂY LÀ LẦN TỔNG KẾT TUẦN (Chủ nhật 21h): làm thêm phần \"Tổng kết tuần\" như hướng dẫn.\n");
        }

        sb.append("\n## Hôm nay theo nhóm (số trực tiếp)\n").append(lines(todaySets.values(), live.get("today")));
        sb.append("\n## 7 ngày gần nhất theo nhóm\n").append(lines(weekSets.values(), null));
        sb.append("\n## Cộng dồn từ khi chạy (90 ngày) theo chiến dịch\n").append(lines(totalCamps.values(), null));
        sb.append("\n## Cộng dồn từ khi chạy (90 ngày) theo nhóm\n").append(lines(totalSets.values(), null));

        sb.append("\n## Chất lượng tin nhắn theo thẻ sale gắn trong CRM\n").append(leadQualityContext(live, weekSets, now.toLocalDate()));

        sb.append("\n## Lịch sử 30 ngày theo ngày và nhóm (chi từ Meta; SĐT/đơn/doanh số theo mã quảng cáo)\n");
        if (history.isEmpty()) {
            sb.append("(không có)\n");
        }
        for (Map<String, Object> d : history) {
            sb.append("- ").append(d.get("date")).append(" \"").append(d.get("adSetName")).append("\": chi ").append(money(num(d.get("spend"))))
                    .append(", ").append((long) num(d.get("messages"))).append(" tin nhắn, ").append((long) num(d.get("phones"))).append(" SĐT, ")
                    .append((long) num(d.get("orders"))).append(" đơn, doanh số ").append(money(num(d.get("revenue")))).append('\n');
        }

        // Winners and money-burners over 90 days, per ad (with the post, so a winning post can be reused).
        Map<String, Agg> totalAds = aggregate(live.get("total"), a -> str(a.get("adId")));
        List<Agg> winners = totalAds.values().stream().filter(a -> a.orders > 0)
                .sorted(Comparator.comparingDouble(a -> a.spend / a.orders)).limit(6).toList();
        List<Agg> burners = totalAds.values().stream().filter(a -> a.orders == 0 && a.spend >= 2_000_000)
                .sorted(Comparator.comparingDouble((Agg a) -> a.spend).reversed()).limit(6).toList();
        sb.append("\n## Quảng cáo từng ra đơn tốt (90 ngày, xếp theo chi phí/đơn)\n");
        if (winners.isEmpty()) {
            sb.append("(chưa có quảng cáo nào ra đơn)\n");
        }
        for (Agg a : winners) {
            sb.append("- \"").append(a.name).append("\" [").append(a.campaignName).append("] bài ").append(a.postId).append(": chi ")
                    .append(money(a.spend)).append(", ").append(a.phones).append(" SĐT, ").append(a.orders).append(" đơn (")
                    .append(money(a.spend / a.orders)).append("/đơn), doanh số ").append(money(a.sales)).append(a.active ? ", ĐANG BẬT" : ", đã tắt").append('\n');
        }
        sb.append("\n## Quảng cáo tiêu nhiều mà chưa có đơn (90 ngày)\n");
        if (burners.isEmpty()) {
            sb.append("(không có)\n");
        }
        for (Agg a : burners) {
            sb.append("- \"").append(a.name).append("\" [").append(a.campaignName).append("]: chi ").append(money(a.spend)).append(", ")
                    .append(a.phones).append(" SĐT, 0 đơn").append(a.active ? ", ĐANG BẬT" : ", đã tắt").append('\n');
        }

        sb.append("\n## Cảnh báo theo luật 3 ngày qua\n");
        List<Map<String, Object>> alerts = memory.recentReports(w, "ALERT", 3, 30);
        if (alerts.isEmpty()) {
            sb.append("(không có)\n");
        }
        for (Map<String, Object> a : alerts) {
            sb.append("- ").append(a.get("at")).append(": ").append(a.get("title")).append('\n');
        }

        sb.append("\n## Đề xuất cũ đến hạn chấm điểm (hãy cho verdict từng id trong JSON)\n")
                .append(dueLines.isEmpty() ? "(không có)\n" : String.join("\n", dueLines) + "\n");

        sb.append("\n## Bài học từ các đề xuất đã chấm trước đây\n");
        List<Map<String, Object>> lessons = memory.lessons(w, 15);
        if (lessons.isEmpty()) {
            sb.append("(chưa có)\n");
        }
        for (Map<String, Object> l : lessons) {
            sb.append("- ").append(l.get("createdDate")).append(" ").append(l.get("action")).append(" \"").append(l.get("entityName"))
                    .append("\" → ").append(l.get("verdict")).append(" (làm theo: ").append(l.get("followed")).append("): ")
                    .append(l.get("lesson")).append('\n');
        }
        Map<String, Object> score = memory.scorecard(w, 7);
        sb.append("Điểm 7 ngày qua: ").append(score.get("dung")).append(" đúng, ").append(score.get("sai")).append(" sai, ")
                .append(score.get("chuaRo")).append(" chưa rõ, ").append(score.get("pending")).append(" đang chờ.\n");

        sb.append("\n## Các lần phân tích trước của bạn (mới nhất ở cuối)\n");
        List<Map<String, Object>> past = new ArrayList<>(memory.recentReports(w, "ANALYSIS", 14, 3));
        java.util.Collections.reverse(past);
        if (past.isEmpty()) {
            sb.append("(chưa có — đây là lần đầu)\n");
        }
        for (Map<String, Object> p : past) {
            String body = str(p.get("body"));
            sb.append("### ").append(p.get("at")).append('\n').append(body == null ? "" : body.length() > 2500 ? body.substring(0, 2500) + "…" : body).append("\n\n");
        }
        return sb.toString();
    }

    private static String lines(Iterable<Agg> groups, List<Map<String, Object>> ads) {
        StringBuilder sb = new StringBuilder();
        for (Agg g : groups) {
            sb.append("- ").append(g.name).append(" [").append(g.campaignName).append("] ID ").append(g.id).append(g.active ? " ĐANG BẬT" : " tắt")
                    .append(g.budget > 0 ? ", ngân sách " + money(g.budget) : "").append(": chi ").append(money(g.spend))
                    .append(", ").append(g.impressions).append(" hiển thị, CPM ").append(g.impressions > 0 ? money(g.spend * 1000 / g.impressions) : "—")
                    .append(", ").append(g.messages).append(" tin nhắn, ").append(g.phones).append(" SĐT (")
                    .append(g.phones > 0 ? money(g.spend / g.phones) : "—").append("/SĐT), ").append(g.orders).append(" đơn (")
                    .append(g.orders > 0 ? money(g.spend / g.orders) : "—").append("/đơn), doanh số ").append(money(g.sales)).append('\n');
            if (ads != null) {
                for (Map<String, Object> a : ads) {
                    if (g.id.equals(str(a.get("adSetId")))) {
                        sb.append("    · QC \"").append(a.get("adName")).append("\" (ID ").append(a.get("adId")).append("): chi ").append(money(num(a.get("spend")))).append(", ")
                                .append((long) num(a.get("messageContacts"))).append(" tin nhắn, ").append((long) num(a.get("phoneCount"))).append(" SĐT, ")
                                .append((long) num(a.get("orderCount"))).append(" đơn\n");
                    }
                }
            }
        }
        return sb.length() == 0 ? "(không có)\n" : sb.toString();
    }

    private static String followed(String action, Agg now, Map<String, Object> base) {
        boolean active = now != null && now.active;
        double budgetNow = now == null ? 0 : now.budget;
        double budgetThen = num(base.get("budget"));
        return switch (action) {
            case "TAT" -> active ? "KHONG" : "CO";
            case "GIU", "THEO_DOI" -> active ? "CO" : "KHONG";
            case "TANG_NGAN_SACH" -> budgetThen <= 0 || budgetNow <= 0 ? "KHONG_RO" : budgetNow > budgetThen * 1.05 ? "CO" : "KHONG";
            case "GIAM_NGAN_SACH" -> budgetThen <= 0 ? "KHONG_RO" : (!active || budgetNow < budgetThen * 0.95) ? "CO" : "KHONG";
            default -> "KHONG_RO";
        };
    }

    private static String followedText(String f) {
        return switch (f) {
            case "CO" -> "CÓ";
            case "KHONG" -> "KHÔNG";
            default -> "không xác định được";
        };
    }

    private static Map<String, Object> after(List<Map<String, Object>> history, String key, String id, LocalDate since) {
        double spend = 0, messages = 0, phones = 0, orders = 0, revenue = 0;
        for (Map<String, Object> d : history) {
            if (id.equals(str(d.get(key))) && LocalDate.parse(str(d.get("date"))).isAfter(since)) {
                spend += num(d.get("spend"));
                messages += num(d.get("messages"));
                phones += num(d.get("phones"));
                orders += num(d.get("orders"));
                revenue += num(d.get("revenue"));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spend", Math.round(spend));
        m.put("messages", (long) messages);
        m.put("phones", (long) phones);
        m.put("orders", (long) orders);
        m.put("revenue", Math.round(revenue));
        return m;
    }

    private static String describeBaseline(Map<String, Object> b) {
        if (b.isEmpty()) {
            return "(không có số)";
        }
        return "từ khi chạy chi " + money(num(b.get("spendTotal"))) + ", " + (long) num(b.get("phonesTotal")) + " SĐT, "
                + (long) num(b.get("ordersTotal")) + " đơn; 7 ngày chi " + money(num(b.get("spend7"))) + ", "
                + (long) num(b.get("phones7")) + " SĐT, " + (long) num(b.get("orders7")) + " đơn"
                + (num(b.get("budget")) > 0 ? "; ngân sách " + money(num(b.get("budget"))) : "");
    }

    // ---------------------------------------------------------------- end of day

    /** 23:30 summary of today per ad set against the owner's targets. No AI. Returns null when already written. */
    public Map<String, Object> dailyClose(AdWorkerService.Worker w) {
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        Map<String, List<Map<String, Object>>> live = workers.liveNumbers(w);
        Map<String, Agg> sets = aggregate(live.get("today"), a -> str(a.get("adSetId")));
        Map<String, Agg> total = aggregate(live.get("total"), a -> str(a.get("adSetId")));
        double spend = 0, sales = 0;
        long phones = 0, orders = 0, messages = 0;
        StringBuilder body = new StringBuilder();
        for (Agg g : sets.values()) {
            if (g.spend <= 0) {
                continue;
            }
            spend += g.spend;
            phones += g.phones;
            orders += g.orders;
            messages += g.messages;
            sales += g.sales;
            Agg t = total.get(g.id);
            String mark = g.orders > 0 && g.spend / g.orders < GOOD_COST_PER_ORDER ? "✅"
                    : g.phones > 0 && g.spend / g.phones <= MAX_COST_PER_PHONE ? "✅"
                    : g.phones == 0 && g.spend >= NO_PHONE_SPEND ? "❌" : "⚠️";
            body.append(mark).append(" ").append(g.name).append(" [").append(g.campaignName).append("]: chi ").append(money(g.spend))
                    .append(", ").append(g.messages).append(" tin nhắn, ").append(g.phones).append(" SĐT (")
                    .append(g.phones > 0 ? money(g.spend / g.phones) : "—").append("/SĐT), ").append(g.orders).append(" đơn");
            if (t != null) {
                body.append(" · từ khi chạy: chi ").append(money(t.spend)).append(", ").append(t.phones).append(" SĐT, ").append(t.orders).append(" đơn");
            }
            body.append('\n');
        }
        Integer alerts = jdbc.queryForObject("select count(*) from ad_worker_reports where worker_id = ? and kind = 'ALERT' and created_at >= ?",
                Integer.class, w.id(), today.atStartOfDay());
        Integer recs = jdbc.queryForObject("select count(*) from ad_worker_recommendations where worker_id = ? and created_at >= ?",
                Integer.class, w.id(), today.atStartOfDay());
        String head = spend <= 0
                ? "Hôm nay tài khoản không chi tiêu."
                : "Tổng hôm nay: chi " + money(spend) + ", " + messages + " tin nhắn, " + phones + " SĐT ("
                + (phones > 0 ? money(spend / phones) : "—") + "/SĐT), " + orders + " đơn"
                + (orders > 0 ? " (" + money(spend / orders) + "/đơn)" : "") + ", doanh số " + money(sales) + ".";
        String quality = "";
        LeadStats ls = leadStats(live, today);
        if (ls != null && ls.allToday.total > 0) {
            StringBuilder q = new StringBuilder("\nChất lượng tin nhắn hôm nay (thẻ CRM, sale có thể gắn muộn): ").append(ls.allToday.describe()).append('\n');
            for (Map.Entry<String, LeadQuality.Count> e : ls.setToday.entrySet()) {
                q.append("· ").append(ls.setNames.get(e.getKey())).append(": ").append(e.getValue().describe()).append('\n');
            }
            quality = q.toString();
        }
        String text = head + "\n" + body + quality + "\nCảnh báo trong ngày: " + alerts + " · Đề xuất mới trong ngày: " + recs
                + "\nMục tiêu: dưới 350k/SĐT, dưới 2tr/đơn. (Đơn có thể về muộn 3–6 ngày.)";
        int added = jdbc.update("""
                insert into ad_worker_reports (worker_id, kind, severity, ref_key, title, body)
                values (?, 'DAILY', 'info', ?, ?, ?)
                on conflict (worker_id, ref_key) where ref_key is not null do nothing
                """, w.id(), "daily:" + today, "Chốt ngày " + today.format(DateTimeFormatter.ofPattern("dd/MM")) + " · " + w.accountName(), text);
        return Map.of("ok", true, "added", added > 0);
    }

    /** Per-ad numbers for each period of the ad-by-ad comparison (see {@link WorkerAdReport}). */
    private static Map<String, Map<String, Agg>> adsByPeriod(Map<String, List<Map<String, Object>>> live) {
        Map<String, Map<String, Agg>> byPeriod = new LinkedHashMap<>();
        for (String p : WorkerAdReport.PERIODS) {
            byPeriod.put(p, aggregate(live.getOrDefault(p, List.of()), a -> str(a.get("adId"))));
        }
        return byPeriod;
    }

    // ---------------------------------------------------------------- lead quality (CRM tags)

    /** CRM leads of this account's ads, counted by tag, per ad set / ad / sale. */
    static final class LeadStats {
        final Map<String, String> setNames = new LinkedHashMap<>();
        final Map<String, String> adNames = new LinkedHashMap<>();
        final Map<String, LeadQuality.Count> setToday = new LinkedHashMap<>();
        final Map<String, LeadQuality.Count> set7 = new LinkedHashMap<>();
        final Map<String, LeadQuality.Count> set30 = new LinkedHashMap<>();
        final Map<String, LeadQuality.Count> ad7 = new LinkedHashMap<>();
        final Map<String, LeadQuality.Count> sale7 = new LinkedHashMap<>();
        final LeadQuality.Count allToday = new LeadQuality.Count();
        final LeadQuality.Count all7 = new LeadQuality.Count();
        final LeadQuality.Count unknown7 = new LeadQuality.Count();
        /** Per ad: today, yesterday, last 3 days, last 30 days. */
        final Map<String, LeadQuality.Count[]> adPeriods = new LinkedHashMap<>();
    }

    private LeadStats leadStats(Map<String, List<Map<String, Object>>> live, LocalDate today) {
        if (!crm.isConfigured()) {
            return null;
        }
        LeadStats st = new LeadStats();
        Map<String, String> adToSet = new LinkedHashMap<>();
        for (String period : List.of("total", "last7", "today")) {
            for (Map<String, Object> a : live.getOrDefault(period, List.of())) {
                String ad = str(a.get("adId"));
                if (ad == null || ad.isBlank()) continue;
                adToSet.put(ad, str(a.get("adSetId")));
                st.adNames.put(ad, str(a.get("adName")) + " [" + str(a.get("adSetName")) + "]");
                st.setNames.put(str(a.get("adSetId")), str(a.get("adSetName")) + " [" + str(a.get("campaignName")) + "]");
            }
        }
        List<com.smitgate.connector.ads.CrmCustomerSource.TaggedLead> leads;
        try {
            leads = crm.taggedLeads(adToSet.keySet(), 30);
        } catch (Exception e) {
            log.warn("CRM lead tags could not be read: {}", e.getMessage());
            return null;
        }
        LocalDate weekStart = today.minusDays(6);
        for (var l : leads) {
            if (l.createdAt() == null) continue;
            LocalDate day = l.createdAt().atZone(AdWorkerService.VIETNAM_ZONE).toLocalDate();
            LeadQuality.Kind kind = LeadQuality.classify(l.tags());
            String adId = l.adId() == null ? null : l.adId().trim();
            String set = adId == null ? null : adToSet.get(adId);
            if (set == null) {
                if (!day.isBefore(weekStart)) st.unknown7.add(kind, l.hasPhone());
                continue;
            }
            st.set30.computeIfAbsent(set, k -> new LeadQuality.Count()).add(kind, l.hasPhone());
            LeadQuality.Count[] per = st.adPeriods.computeIfAbsent(adId, k -> new LeadQuality.Count[]{
                    new LeadQuality.Count(), new LeadQuality.Count(), new LeadQuality.Count(), new LeadQuality.Count()});
            if (day.equals(today)) per[0].add(kind, l.hasPhone());
            if (day.equals(today.minusDays(1))) per[1].add(kind, l.hasPhone());
            if (!day.isBefore(today.minusDays(2))) per[2].add(kind, l.hasPhone());
            per[3].add(kind, l.hasPhone());
            if (!day.isBefore(weekStart)) {
                st.set7.computeIfAbsent(set, k -> new LeadQuality.Count()).add(kind, l.hasPhone());
                st.ad7.computeIfAbsent(adId, k -> new LeadQuality.Count()).add(kind, l.hasPhone());
                st.all7.add(kind, l.hasPhone());
                for (String sale : LeadQuality.sales(l.tags())) {
                    st.sale7.computeIfAbsent(sale, k -> new LeadQuality.Count()).add(kind, l.hasPhone());
                }
            }
            if (day.equals(today)) {
                st.setToday.computeIfAbsent(set, k -> new LeadQuality.Count()).add(kind, l.hasPhone());
                st.allToday.add(kind, l.hasPhone());
            }
        }
        return st;
    }

    /**
     * Lead quality right now, from the tags the sales team keeps updating: today and 7 days per ad set, 7 days per
     * ad and per sale. Used by every 15-minute check and by the live table on the Workers page.
     */
    public Map<String, Object> liveQuality(AdWorkerService.Worker w, Map<String, List<Map<String, Object>>> live) {
        LocalDate today = LocalDate.now(AdWorkerService.VIETNAM_ZONE);
        if (live == null) {
            live = workers.liveNumbers(w);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", LocalDateTime.now(AdWorkerService.VIETNAM_ZONE).withNano(0).toString());
        LeadStats st = leadStats(live, today);
        if (st == null) {
            out.put("error", "Chưa đọc được CRM");
            return out;
        }
        Map<String, Agg> todaySets = aggregate(live.get("today"), a -> str(a.get("adSetId")));
        Map<String, Agg> weekSets = aggregate(live.get("last7"), a -> str(a.get("adSetId")));
        Map<String, Agg> todayAds = aggregate(live.get("today"), a -> str(a.get("adId")));
        Map<String, Agg> weekAds = aggregate(live.get("last7"), a -> str(a.get("adId")));
        out.put("today", st.allToday.toMap());
        out.put("week", st.all7.toMap());
        out.put("weekSpend", Math.round(weekSets.values().stream().mapToDouble(a -> a.spend).sum()));
        out.put("todaySpend", Math.round(todaySets.values().stream().mapToDouble(a -> a.spend).sum()));
        out.put("unknownWeek", st.unknown7.toMap());

        Set<String> setIds = new java.util.LinkedHashSet<>(st.set7.keySet());
        todaySets.values().stream().filter(a -> a.active).forEach(a -> setIds.add(a.id));
        List<Map<String, Object>> sets = new ArrayList<>();
        for (String id : setIds) {
            Agg t = todaySets.get(id);
            Agg wk = weekSets.get(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", st.setNames.getOrDefault(id, wk != null ? wk.name : id));
            m.put("active", t != null && t.active);
            m.put("spendToday", t == null ? 0 : Math.round(t.spend));
            m.put("spendWeek", wk == null ? 0 : Math.round(wk.spend));
            m.put("today", st.setToday.getOrDefault(id, new LeadQuality.Count()).toMap());
            m.put("week", st.set7.getOrDefault(id, new LeadQuality.Count()).toMap());
            sets.add(m);
        }
        out.put("adSets", sets);

        List<Map<String, Object>> ads = new ArrayList<>();
        for (Map.Entry<String, LeadQuality.Count> e : st.ad7.entrySet()) {
            Agg t = todayAds.get(e.getKey());
            Agg wk = weekAds.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("name", st.adNames.getOrDefault(e.getKey(), e.getKey()));
            m.put("active", t != null && t.active);
            m.put("spendWeek", wk == null ? 0 : Math.round(wk.spend));
            m.put("week", e.getValue().toMap());
            m.put("weekTotal", e.getValue().total);
            ads.add(m);
        }
        ads.sort((x, y) -> ((Integer) y.get("weekTotal")) - ((Integer) x.get("weekTotal")));
        out.put("ads", ads);

        List<Map<String, Object>> sales = new ArrayList<>();
        st.sale7.entrySet().stream().sorted((x, y) -> y.getValue().total - x.getValue().total).forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>(e.getValue().toMap());
            m.put("name", e.getKey());
            sales.add(m);
        });
        out.put("sales", sales);
        return out;
    }

    String leadQualityContext(Map<String, List<Map<String, Object>>> live, Map<String, Agg> weekSets, LocalDate today) {
        LeadStats st = leadStats(live, today);
        if (st == null) {
            return "(chưa đọc được CRM)\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Khách chất lượng = có SĐT, hoặc thẻ Tiềm Năng / Có Tương Tác / Hỏi Giá Xong Im / Chốt Đơn. ")
                .append("Số tin nhắn CRM không bằng số tin nhắn của Meta (Meta đếm khác). Tin nhắn mới có thể chưa được gắn thẻ.\n");
        double spend7 = weekSets.values().stream().mapToDouble(a -> a.spend).sum();
        sb.append("Tổng 7 ngày: ").append(st.all7.describe())
                .append(st.all7.qualityCount > 0 ? " · chi phí/khách chất lượng " + money(spend7 / st.all7.qualityCount) : "").append('\n');
        sb.append("Hôm nay: ").append(st.allToday.describe()).append('\n');

        sb.append("### Hôm nay theo nhóm\n").append(countLines(st.setToday, st.setNames, null));
        sb.append("### 7 ngày theo nhóm\n").append(countLines(st.set7, st.setNames, id -> {
            Agg a = weekSets.get(id);
            return a == null ? 0.0 : a.spend;
        }));
        sb.append("### 30 ngày theo nhóm\n").append(countLines(st.set30, st.setNames, null));

        Map<String, Agg> weekAds = aggregate(live.get("last7"), a -> str(a.get("adId")));
        Map<String, LeadQuality.Count> busyAds = new LinkedHashMap<>();
        st.ad7.entrySet().stream().filter(e -> e.getValue().total >= 3)
                .sorted((x, y) -> y.getValue().total - x.getValue().total).forEach(e -> busyAds.put(e.getKey(), e.getValue()));
        sb.append("### 7 ngày theo quảng cáo (từ 3 tin nhắn)\n").append(countLines(busyAds, st.adNames, id -> {
            Agg a = weekAds.get(id);
            return a == null ? 0.0 : a.spend;
        }));
        if (st.unknown7.total > 0) {
            sb.append("### Tin nhắn 7 ngày trên cùng page nhưng không rõ quảng cáo (không tính vào nhóm nào)\n- ")
                    .append(st.unknown7.describe()).append('\n');
        }
        sb.append("### Theo sale 7 ngày (tin nhắn của tài khoản này)\n");
        if (st.sale7.isEmpty()) {
            sb.append("(không có)\n");
        }
        st.sale7.entrySet().stream().sorted((x, y) -> y.getValue().total - x.getValue().total).forEach(e -> {
            LeadQuality.Count c = e.getValue();
            sb.append("- ").append(e.getKey()).append(": ").append(c.total).append(" tin nhắn, có SĐT ").append(c.phones)
                    .append(", tiềm năng ").append(c.of(LeadQuality.Kind.TIEM_NANG)).append(", có tương tác ").append(c.of(LeadQuality.Kind.CO_TUONG_TAC))
                    .append(", hỏi giá xong im ").append(c.of(LeadQuality.Kind.HOI_GIA)).append(", im lặng ").append(c.of(LeadQuality.Kind.IM_LANG)).append('\n');
        });
        return sb.toString();
    }

    private static String countLines(Map<String, LeadQuality.Count> counts, Map<String, String> names, Function<String, Double> spendOf) {
        if (counts.isEmpty()) {
            return "(không có)\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LeadQuality.Count> e : counts.entrySet()) {
            LeadQuality.Count c = e.getValue();
            sb.append("- ").append(names.getOrDefault(e.getKey(), e.getKey())).append(" (id ").append(e.getKey()).append("): ").append(c.describe());
            if (spendOf != null) {
                double spend = spendOf.apply(e.getKey());
                if (spend > 0) {
                    sb.append(" · chi ").append(money(spend)).append(c.qualityCount > 0 ? ", " + money(spend / c.qualityCount) + "/khách chất lượng" : ", chưa có khách chất lượng");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    static final class Agg {
        String id;
        String name;
        String campaignName;
        String postId;
        boolean active;
        double budget;
        double spend;
        double sales;
        long impressions;
        long messages;
        long phones;
        long orders;
        long clicks;
        String setName;
        String setId;
        String campaignId;
    }

    static Map<String, Agg> aggregate(List<Map<String, Object>> ads, Function<Map<String, Object>, String> key) {
        Map<String, Agg> out = new LinkedHashMap<>();
        if (ads == null) {
            return out;
        }
        for (Map<String, Object> a : ads) {
            String k = key.apply(a);
            if (k == null || k.isBlank()) {
                continue;
            }
            Agg g = out.computeIfAbsent(k, x -> {
                Agg n = new Agg();
                n.id = x;
                return n;
            });
            boolean byAd = k.equals(str(a.get("adId")));
            boolean byCampaign = k.equals(str(a.get("campaignId"))) && !byAd;
            g.name = byAd ? str(a.get("adName")) : byCampaign ? str(a.get("campaignName")) : str(a.get("adSetName"));
            g.campaignName = str(a.get("campaignName"));
            if (g.postId == null) {
                g.postId = str(a.get("postId"));
            }
            g.active |= "ACTIVE".equalsIgnoreCase(str(a.get("delivery")));
            g.budget = Math.max(g.budget, num(a.get("budget")));
            g.spend += num(a.get("spend"));
            g.sales += num(a.get("sales"));
            g.impressions += (long) num(a.get("impressions"));
            g.clicks += (long) num(a.get("clicks"));
            g.setName = str(a.get("adSetName"));
            g.setId = str(a.get("adSetId"));
            g.campaignId = str(a.get("campaignId"));
            g.messages += (long) num(a.get("messageContacts"));
            g.phones += (long) num(a.get("phoneCount"));
            g.orders += (long) num(a.get("orderCount"));
        }
        return out;
    }

    private Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static double sum(List<Map<String, Object>> rows, String f) {
        return rows == null ? 0 : rows.stream().mapToDouble(r -> num(r.get(f))).sum();
    }

    static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? 0 : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String money(double v) {
        if (v >= 1_000_000) {
            String s = String.format(java.util.Locale.ROOT, v >= 10_000_000 ? "%.0f" : "%.2f", v / 1_000_000);
            if (s.contains(".")) {
                s = s.replaceAll("0+$", "").replaceAll("\\.$", "").replace('.', ',');
            }
            return s + "tr";
        }
        if (v >= 1000) {
            return Math.round(v / 1000) + "k";
        }
        return Math.round(v) + "đ";
    }
}
