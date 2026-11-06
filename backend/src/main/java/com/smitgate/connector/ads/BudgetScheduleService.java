package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.smitgate.auth.AdAccountAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Daily automatic budget increases or decreases. Every minute, schedules whose time (Vietnam) has passed and
 * that haven't run today read the live daily budget from Meta and change it by the set percentage.
 * A run missed while the server was down still happens later the same day, never twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetScheduleService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);
    /** A -100% step would zero the budget, which Meta rejects. */
    private static final BigDecimal MIN_PERCENT = BigDecimal.valueOf(-90);

    private final BudgetScheduleRepository repository;
    private final MetaAdsConnector metaAdsConnector;
    private final AdAccountAccessService accessService;

    public List<BudgetSchedule> list(Long tenantId) {
        List<BudgetSchedule> all = repository.findByTenantId(tenantId);
        Set<String> allowed = accessService.allowedExternalIds(tenantId);
        if (allowed == null) return all;
        return all.stream().filter(s -> allowed.contains(s.getAdAccountId())).toList();
    }

    /** Creates/updates the schedule for objectId; a percent of 0 (or blank) removes it. Returns null when removed. */
    public BudgetSchedule save(Long tenantId, Long dataSourceId, String objectId,
                               BigDecimal percent, String runTime, Long budgetLimit) {
        if (objectId == null || objectId.isBlank()) throw new IllegalArgumentException("Thiếu mã nhóm quảng cáo");
        accessService.requireMetaObject(tenantId, objectId);

        if (percent == null || percent.signum() == 0) {
            repository.findByTenantIdAndObjectId(tenantId, objectId).ifPresent(repository::delete);
            return null;
        }
        if (percent.compareTo(MAX_PERCENT) > 0 || percent.compareTo(MIN_PERCENT) < 0) {
            throw new IllegalArgumentException("Chỉ được tăng tối đa 100% hoặc giảm tối đa 90%");
        }
        LocalTime time = parseTime(runTime);

        JsonNode state = metaAdsConnector.getBudgetState(tenantId, dataSourceId, objectId);
        if (state == null || state.path("daily_budget").asText("").isBlank()) {
            throw new IllegalArgumentException("Đối tượng này không dùng ngân sách hằng ngày nên không thể hẹn tăng");
        }

        BudgetSchedule schedule = repository.findByTenantIdAndObjectId(tenantId, objectId)
                .orElseGet(BudgetSchedule::new);
        schedule.setTenantId(tenantId);
        schedule.setDataSourceId(dataSourceId);
        schedule.setObjectId(objectId);
        String accountId = state.path("account_id").asText("");
        schedule.setAdAccountId(accountId.isBlank() ? null : (accountId.startsWith("act_") ? accountId : "act_" + accountId));
        schedule.setIncreasePercent(percent);
        schedule.setRunTime(time.toString());
        schedule.setBudgetLimit(budgetLimit != null && budgetLimit > 0 ? budgetLimit : null);
        // A time already past today starts tomorrow, so saving at 20:00 a "18:00" schedule doesn't fire instantly
        ZonedDateTime now = ZonedDateTime.now(VN_ZONE);
        schedule.setLastRunDate(now.toLocalTime().isBefore(time) ? null : now.toLocalDate());
        return repository.save(schedule);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void runDueSchedules() {
        ZonedDateTime now = ZonedDateTime.now(VN_ZONE);
        for (BudgetSchedule schedule : repository.findAll()) {
            if (now.toLocalDate().equals(schedule.getLastRunDate())) continue;
            if (now.toLocalTime().isBefore(LocalTime.parse(schedule.getRunTime()))) continue;
            String result;
            try {
                result = increase(schedule);
            } catch (Exception e) {
                result = "Lỗi: " + describe(e);
                log.warn("Budget schedule {} ({}) failed: {}", schedule.getId(), schedule.getObjectId(), result);
            }
            schedule.setLastRunDate(now.toLocalDate());
            schedule.setLastRunAt(LocalDateTime.now(VN_ZONE));
            schedule.setLastResult(result.length() > 500 ? result.substring(0, 500) : result);
            repository.save(schedule);
        }
    }

    private String increase(BudgetSchedule s) {
        JsonNode state = metaAdsConnector.getBudgetState(s.getTenantId(), s.getDataSourceId(), s.getObjectId());
        String status = state == null ? "" : state.path("effective_status").asText("");
        if (!"ACTIVE".equals(status)) {
            return "Bỏ qua: đang tắt (" + status + ")";
        }
        long current = new BigDecimal(state.path("daily_budget").asText("0")).longValue();
        if (current <= 0) return "Bỏ qua: không có ngân sách hằng ngày";

        boolean increasing = s.getIncreasePercent().signum() > 0;
        long target = new BigDecimal(current)
                .multiply(BigDecimal.ONE.add(s.getIncreasePercent().movePointLeft(2)))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        if (s.getBudgetLimit() != null) {
            target = increasing ? Math.min(target, s.getBudgetLimit()) : Math.max(target, s.getBudgetLimit());
        }
        if (increasing ? target <= current : target >= current) {
            return "Bỏ qua: đã đạt mức " + (increasing ? "tối đa " : "tối thiểu ") + current;
        }

        metaAdsConnector.updateBudget(s.getTenantId(), s.getDataSourceId(), s.getObjectId(), target, "daily_budget");
        log.info("Budget schedule {}: {} daily budget {} -> {}", s.getId(), s.getObjectId(), current, target);
        return (increasing ? "Đã tăng " : "Đã giảm ") + current + " → " + target;
    }

    public static Map<String, Object> toView(BudgetSchedule s) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("objectId", s.getObjectId());
        view.put("dataSourceId", s.getDataSourceId());
        view.put("increasePercent", s.getIncreasePercent());
        view.put("runTime", s.getRunTime());
        view.put("budgetLimit", s.getBudgetLimit());
        view.put("lastRunAt", s.getLastRunAt());
        view.put("lastResult", s.getLastResult());
        return view;
    }

    private static LocalTime parseTime(String raw) {
        try {
            return LocalTime.parse(raw == null ? "" : raw.trim()).withSecond(0).withNano(0);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Giờ tăng không hợp lệ (định dạng HH:mm)");
        }
    }

    /** Meta error text without the request URL — that URL carries the access token. */
    private static String describe(Exception e) {
        if (e instanceof WebClientResponseException w) {
            return w.getStatusCode().value() + " " + w.getResponseBodyAsString();
        }
        String msg = String.valueOf(e.getMessage());
        return msg.replaceAll("access_token=[^&\\s]+", "access_token=***");
    }
}
