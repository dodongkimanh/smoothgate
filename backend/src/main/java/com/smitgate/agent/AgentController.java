package com.smitgate.agent;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final CampaignMonitorService campaignMonitorService;

    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<String>> triggerAnalysis(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String report = campaignMonitorService.analyzeForTenant(tenantId);
        if (report == null) {
            return ResponseEntity.ok(ApiResponse.ok("Không có dữ liệu chiến dịch để phân tích"));
        }
        return ResponseEntity.ok(ApiResponse.ok(report));
    }
}
