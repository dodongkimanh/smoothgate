package com.smitgate.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.connector.ads.AdAccount;
import com.smitgate.connector.ads.AdAccountRepository;
import com.smitgate.connector.ads.MetaAdsConnector;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private final DataSourceRepository dataSourceRepository;
    private final AdAccountRepository adAccountRepository;
    private final MetaAdsConnector metaAdsConnector;
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

        List<AdAccount> accounts = adAccountRepository.findByTenantId(tenantId).stream()
                .filter(a -> a.getPlatform() == AdAccount.Platform.META)
                .toList();

        List<Map<String, Object>> activeAds = new ArrayList<>();
        for (AdAccount account : accounts) {
            try {
                List<Map<String, Object>> ads = metaAdsConnector.listAdPerformance(
                        tenantId, account.getDataSourceId(), account.getExternalAccountId(),
                        sevenDaysAgo, today, null, null);
                for (Map<String, Object> ad : ads) {
                    String delivery = String.valueOf(ad.getOrDefault("delivery", "")).trim();
                    if ("ACTIVE".equalsIgnoreCase(delivery)) {
                        activeAds.add(ad);
                    }
                }
            } catch (Exception e) {
                log.warn("Skip account {} for tenant {} agent analysis: {}",
                        account.getExternalAccountId(), tenantId, e.getMessage());
            }
        }

        if (activeAds.isEmpty()) {
            log.info("No active ads for tenant {} — skipping analysis", tenantId);
            return null;
        }

        String dataJson = buildAdDataJson(activeAds, sevenDaysAgo, today);
        String analysis = claudeAnalysisService.analyzeCampaigns(dataJson);

        if (analysis != null && !analysis.startsWith("Lỗi")) {
            telegramService.sendMessage(analysis);
            log.info("Sent analysis report for tenant {} via Telegram", tenantId);
        }

        return analysis;
    }

    private String buildAdDataJson(List<Map<String, Object>> ads, LocalDate from, LocalDate to) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", from + " → " + to);
            data.put("totalActiveAds", ads.size());

            BigDecimal totalSpend = BigDecimal.ZERO;
            BigDecimal totalRevenue = BigDecimal.ZERO;
            long totalOrders = 0;

            List<Map<String, Object>> adList = new ArrayList<>();
            for (Map<String, Object> ad : ads) {
                BigDecimal spend = toBigDecimal(ad.get("spend"));
                BigDecimal revenue = toBigDecimal(ad.get("sales"));
                BigDecimal orderProfit = toBigDecimal(ad.get("orderProfit"));
                long orders = toLong(ad.get("orderCount"));

                totalSpend = totalSpend.add(spend);
                totalRevenue = totalRevenue.add(revenue);
                totalOrders += orders;

                BigDecimal roas = spend.compareTo(BigDecimal.ZERO) > 0
                        ? revenue.divide(spend, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal cpo = orders > 0
                        ? spend.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                Map<String, Object> am = new LinkedHashMap<>();
                am.put("name", ad.get("adName"));
                am.put("adSet", ad.get("adSetName"));
                am.put("campaign", ad.get("campaignName"));
                am.put("adAccount", ad.get("adAccountName"));
                am.put("spend", spend);
                am.put("revenue", revenue);
                am.put("orders", orders);
                am.put("roas", roas);
                am.put("cpo", cpo);
                am.put("orderProfit", orderProfit);
                adList.add(am);
            }

            data.put("totalSpend", totalSpend);
            data.put("totalRevenue", totalRevenue);
            data.put("totalOrders", totalOrders);
            data.put("ads", adList);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            log.error("Failed to build ad data JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
