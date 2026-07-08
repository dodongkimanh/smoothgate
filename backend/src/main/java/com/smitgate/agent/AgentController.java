package com.smitgate.agent;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final CampaignMonitorService campaignMonitorService;
    private final AgentSettingsService agentSettingsService;

    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<String>> triggerAnalysis(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String report = campaignMonitorService.analyzeForTenant(tenantId);
        if (report == null) {
            return ResponseEntity.ok(ApiResponse.ok("Không có quảng cáo đang chạy để phân tích"));
        }
        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<AgentSettingsService.AgentSettings>> getSettings(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(agentSettingsService.getSettings(tenantId)));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<AgentSettingsService.AgentSettings>> saveSettings(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        AgentSettingsService.AgentSettings settings = new AgentSettingsService.AgentSettings(
                toBigDecimal(body.get("costPerMessageThreshold"), AgentSettingsService.DEFAULT_COST_PER_MESSAGE),
                toBigDecimal(body.get("costPerPhoneThreshold"), AgentSettingsService.DEFAULT_COST_PER_PHONE),
                toBigDecimal(body.get("costPerOrderThreshold"), AgentSettingsService.DEFAULT_COST_PER_ORDER),
                toBigDecimal(body.get("lossAfterAdsThreshold"), AgentSettingsService.DEFAULT_LOSS_AFTER_ADS),
                toInt(body.get("analysisWindowDays"), AgentSettingsService.DEFAULT_ANALYSIS_WINDOW_DAYS)
        );
        agentSettingsService.saveSettings(tenantId, settings);
        return ResponseEntity.ok(ApiResponse.ok(agentSettingsService.getSettings(tenantId)));
    }

    private BigDecimal toBigDecimal(Object value, BigDecimal fallback) {
        if (value == null) return fallback;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int toInt(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.toString());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
