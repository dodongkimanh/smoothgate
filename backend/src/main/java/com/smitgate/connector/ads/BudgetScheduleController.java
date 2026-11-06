package com.smitgate.connector.ads;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations/meta/budget-schedules")
@RequiredArgsConstructor
public class BudgetScheduleController {

    private final BudgetScheduleService budgetScheduleService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(budgetScheduleService.list(tenantId).stream()
                .map(BudgetScheduleService::toView)
                .toList()));
    }

    /** Body: objectId, dataSourceId, increasePercent (negative decreases, 0 removes), runTime "HH:mm", budgetLimit (optional ceiling/floor). */
    @PutMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> save(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String objectId = str(body.get("objectId"));
        Long dataSourceId = Long.valueOf(str(body.get("dataSourceId")));
        BigDecimal percent = str(body.get("increasePercent")).isBlank()
                ? BigDecimal.ZERO : new BigDecimal(str(body.get("increasePercent")));
        Long budgetLimit = str(body.get("budgetLimit")).isBlank()
                ? null : new BigDecimal(str(body.get("budgetLimit"))).longValue();

        BudgetSchedule saved = budgetScheduleService.save(
                tenantId, dataSourceId, objectId, percent, str(body.get("runTime")), budgetLimit);
        return ResponseEntity.ok(ApiResponse.ok(saved == null
                ? Map.of("objectId", objectId, "removed", true)
                : BudgetScheduleService.toView(saved)));
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }
}
