package com.smitgate.report;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final AdsRefreshService adsRefreshService;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<OverviewResponse>> overview(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        // Ads refresh is triggered by /campaigns endpoint only — avoid duplicate refresh from parallel dashboard calls.
        return ResponseEntity.ok(ApiResponse.ok(reportService.getOverview(tenantId, from, to)));
    }

    @GetMapping("/campaigns")
    public ResponseEntity<ApiResponse<List<CampaignPerfResponse>>> campaigns(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String platform) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        adsRefreshService.refreshAdsMetricsAsync(tenantId, from, to);
        return ResponseEntity.ok(ApiResponse.ok(
                reportService.getCampaignPerformance(tenantId, from, to, platform)));
    }

    @GetMapping("/campaigns/{id}/daily")
    public ResponseEntity<ApiResponse<List<CampaignPerfResponse.DailyData>>> campaignDaily(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(reportService.getCampaignDaily(tenantId, id, from, to)));
    }

    @GetMapping("/campaigns/{id}/funnel")
    public ResponseEntity<ApiResponse<CampaignFunnelResponse>> campaignFunnel(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int limit) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                reportService.getCampaignFunnel(tenantId, id, from, to, limit)));
    }

    /**
     * Diagnostic endpoint to inspect how orders are attributed to ad campaigns.
     * Useful for validating end-to-end sync quality after importing orders + ad metrics.
     */
    @GetMapping("/attributions")
    public ResponseEntity<ApiResponse<List<java.util.Map<String, Object>>>> attributions(
            HttpServletRequest request,
            @RequestParam(defaultValue = "100") int limit) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(reportService.getAttributionDetails(tenantId, limit)));
    }

    /**
     * Data quality endpoint for deterministic attribution readiness.
     * Measures AD_ID/p_utm_id coverage and returns missing-tracking samples for ops action.
     */
    @GetMapping("/attribution-quality")
    public ResponseEntity<ApiResponse<Map<String, Object>>> attributionQuality(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                reportService.getAttributionQuality(tenantId, from, to, limit)
        ));
    }

    @GetMapping("/account-spend")
    public ResponseEntity<ApiResponse<List<AccountSpendResponse>>> accountSpend(
            HttpServletRequest request,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                reportService.getAccountSpend(tenantId, from, to)));
    }
}
