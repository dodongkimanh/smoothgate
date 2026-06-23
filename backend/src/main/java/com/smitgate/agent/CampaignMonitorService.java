package com.smitgate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.report.CampaignPerfResponse;
import com.smitgate.report.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignMonitorService {

    private final ReportService reportService;
    private final DataSourceRepository dataSourceRepository;
    private final ClaudeAnalysisService claudeAnalysisService;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;

    @Value("${app.agent.enabled:false}")
    private boolean agentEnabled;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Scheduled(
            fixedDelayString = "${app.agent.analysis-interval-ms:3600000}",
            initialDelayString = "${app.agent.analysis-initial-delay-ms:120000}")
    public void analyzeAllTenants() {
        if (!agentEnabled) {
            return;
        }

        List<Long> tenantIds = dataSourceRepository
                .findDistinctTenantIdsByStatus(DataSource.Status.ACTIVE);

        for (Long tenantId : tenantIds) {
            try {
                analyzeForTenant(tenantId);
            } catch (Exception e) {
                log.error("Agent analysis failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    public String analyzeForTenant(Long tenantId) {
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        LocalDate sevenDaysAgo = today.minusDays(6);

        List<CampaignPerfResponse> campaigns = reportService
                .getCampaignPerformance(tenantId, sevenDaysAgo, today, null);

        if (campaigns.isEmpty()) {
            log.info("No campaign data for tenant {} — skipping analysis", tenantId);
            return null;
        }

        String dataJson = buildCampaignDataJson(campaigns, sevenDaysAgo, today);
        String analysis = claudeAnalysisService.analyzeCampaigns(dataJson);

        if (analysis != null && !analysis.startsWith("Lỗi")) {
            telegramService.sendMessage(analysis);
            log.info("Sent analysis report for tenant {} via Telegram", tenantId);
        }

        return analysis;
    }

    private String buildCampaignDataJson(List<CampaignPerfResponse> campaigns, LocalDate from, LocalDate to) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", from + " → " + to);
            data.put("totalCampaigns", campaigns.size());

            BigDecimal totalSpend = BigDecimal.ZERO;
            BigDecimal totalRevenue = BigDecimal.ZERO;
            int totalOrders = 0;

            List<Map<String, Object>> campaignList = new ArrayList<>();
            for (CampaignPerfResponse c : campaigns) {
                totalSpend = totalSpend.add(c.getTotalSpend() != null ? c.getTotalSpend() : BigDecimal.ZERO);
                totalRevenue = totalRevenue.add(c.getTotalRevenue() != null ? c.getTotalRevenue() : BigDecimal.ZERO);
                totalOrders += c.getValidOrders();

                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.getCampaignName());
                cm.put("platform", c.getPlatform());
                cm.put("status", c.getStatus());
                cm.put("spend", c.getTotalSpend());
                cm.put("revenue", c.getTotalRevenue());
                cm.put("orders", c.getValidOrders());
                cm.put("newContacts", c.getNewContacts());
                cm.put("messageContacts", c.getMessageContacts());
                cm.put("roas", c.getRoas());
                cm.put("cpo", c.getCpo());
                cm.put("orderProfit", c.getTotalOrderProfit());
                campaignList.add(cm);
            }

            data.put("totalSpend", totalSpend);
            data.put("totalRevenue", totalRevenue);
            data.put("totalOrders", totalOrders);
            data.put("campaigns", campaignList);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            log.error("Failed to build campaign data JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
