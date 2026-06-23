package com.smitgate.connector.ads;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/integrations/meta")
@RequiredArgsConstructor
public class MetaAdsController {

    private final MetaAdsConnector metaAdsConnector;
    private final AdAccountRepository adAccountRepository;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @GetMapping("/oauth/url")
    public ResponseEntity<ApiResponse<Map<String, String>>> getOAuthUrl(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String url = metaAdsConnector.getAuthorizationUrl(tenantId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url)));
    }

    /** Debug endpoint to verify the exact OAuth callback URL used in runtime. */
    @GetMapping("/oauth/debug")
    public ResponseEntity<ApiResponse<Map<String, String>>> debugOAuth(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String url = metaAdsConnector.getAuthorizationUrl(tenantId);
        String callback = metaAdsConnector.getEffectiveRedirectUri();
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "redirectUri", callback,
                "authorizationUrl", url
        )));
    }

    /**
     * OAuth callback from Meta — receives code, exchanges for token, redirects browser to frontend.
     * This endpoint is called by Meta's servers after user authorization.
     */
    @GetMapping("/oauth/callback")
    public void handleCallback(
            @RequestParam String code,
            @RequestParam String state,
            HttpServletResponse httpResponse) throws IOException {
        Long tenantId;
        try {
            tenantId = Long.valueOf(state);
        } catch (NumberFormatException e) {
            httpResponse.sendRedirect(frontendUrl + "/connect-ads?error=invalid_state");
            return;
        }
        try {
            Long dataSourceId = metaAdsConnector.handleCallback(tenantId, code);
            httpResponse.sendRedirect(frontendUrl + "/ad-accounts?connected=meta&dataSourceId=" + dataSourceId);
        } catch (Exception e) {
            log.error("Meta OAuth callback error: {}", e.getMessage());
            String errorMsg = URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "OAuth thất bại",
                    StandardCharsets.UTF_8);
            httpResponse.sendRedirect(frontendUrl + "/connect-ads?error=" + errorMsg);
        }
    }

    @GetMapping("/ad-accounts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAdAccounts(
            HttpServletRequest request,
            @RequestParam Long dataSourceId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<Map<String, Object>> accounts = metaAdsConnector.listAdAccounts(tenantId, dataSourceId);
        return ResponseEntity.ok(ApiResponse.ok(accounts));
    }

    @PostMapping("/ad-accounts/select")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<List<AdAccount>>> selectAdAccounts(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        Long dataSourceId = Long.valueOf(body.get("dataSourceId").toString());
        List<Map<String, String>> accounts = (List<Map<String, String>>) body.get("accounts");
        List<AdAccount> saved = metaAdsConnector.selectAdAccounts(tenantId, dataSourceId, accounts);
        return ResponseEntity.ok(ApiResponse.ok(saved));
    }

    /**
     * Get all active Meta ad accounts for this tenant.
     */
    @GetMapping("/ad-accounts/selected")
    public ResponseEntity<ApiResponse<List<AdAccount>>> selectedAccounts(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(adAccountRepository.findByTenantId(tenantId)));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncMetrics(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        Long dataSourceId = Long.valueOf(body.get("dataSourceId").toString());
        LocalDate from = LocalDate.parse(body.getOrDefault("from", LocalDate.now().minusDays(7).toString()).toString());
        LocalDate to = LocalDate.parse(body.getOrDefault("to", LocalDate.now().toString()).toString());
        int count = metaAdsConnector.syncMetricsDaily(tenantId, dataSourceId, from, to);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("synced", count, "message", "Đồng bộ " + count + " dòng metrics thành công")));
    }

    @GetMapping("/campaigns")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> campaigns(
            HttpServletRequest request,
            @RequestParam Long dataSourceId,
            @RequestParam String adAccountId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                metaAdsConnector.listCampaignHierarchy(tenantId, dataSourceId, adAccountId)
        ));
    }

    @GetMapping("/adsets")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> adsets(
            HttpServletRequest request,
            @RequestParam Long dataSourceId,
            @RequestParam String adAccountId,
            @RequestParam(required = false) String campaignId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                metaAdsConnector.listAdSetHierarchy(tenantId, dataSourceId, adAccountId, campaignId)
        ));
    }

    @GetMapping("/ads")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> ads(
            HttpServletRequest request,
            @RequestParam Long dataSourceId,
            @RequestParam String adAccountId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String adSetId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                metaAdsConnector.listAdHierarchy(tenantId, dataSourceId, adAccountId, campaignId, adSetId)
        ));
    }

    @GetMapping("/ads/performance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> adsPerformance(
            HttpServletRequest request,
            @RequestParam Long dataSourceId,
            @RequestParam String adAccountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String adSetId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                metaAdsConnector.listAdPerformance(tenantId, dataSourceId, adAccountId, from, to, campaignId, adSetId)
        ));
    }

    /**
     * Debug endpoint: returns RAW Meta API insights at both campaign-level and ad-level
     * so we can compare exactly what Meta returns vs what we display.
     * Useful for verifying messageContacts and phoneCount discrepancies.
     */
    @GetMapping("/ads/debug")
    public ResponseEntity<ApiResponse<Map<String, Object>>> debugInsights(
            HttpServletRequest request,
            @RequestParam Long dataSourceId,
            @RequestParam String adAccountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String campaignId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(
                metaAdsConnector.debugInsightsRaw(tenantId, dataSourceId, adAccountId, from, to, campaignId)
        ));
    }
}

