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

        Map<String, Map<String, Object>> adSetAgg = new LinkedHashMap<>();
        for (AdAccount account : accounts) {
            try {
                List<Map<String, Object>> ads = metaAdsConnector.listAdPerformance(
                        tenantId, account.getDataSourceId(), account.getExternalAccountId(),
                        sevenDaysAgo, today, null, null);
                aggregateByAdSet(adSetAgg, account, ads);
            } catch (Exception e) {
                log.warn("Skip account {} for tenant {} agent analysis: {}",
                        account.getExternalAccountId(), tenantId, e.getMessage());
            }
        }

        if (adSetAgg.isEmpty()) {
            log.info("No ad set data for tenant {} — skipping analysis", tenantId);
            return null;
        }

        String dataJson = buildAdSetDataJson(adSetAgg.values(), sevenDaysAgo, today);
        String analysis = claudeAnalysisService.analyzeCampaigns(dataJson);

        if (analysis != null && !analysis.startsWith("Lỗi")) {
            telegramService.sendMessage(analysis);
            log.info("Sent analysis report for tenant {} via Telegram", tenantId);
        }

        return analysis;
    }

    private void aggregateByAdSet(Map<String, Map<String, Object>> adSetAgg, AdAccount account, List<Map<String, Object>> ads) {
        for (Map<String, Object> ad : ads) {
            String adSetId = String.valueOf(ad.getOrDefault("adSetId", "")).trim();
            if (adSetId.isEmpty()) {
                continue;
            }
            String key = account.getExternalAccountId() + ":" + adSetId;
            Map<String, Object> agg = adSetAgg.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", ad.get("adSetName"));
                m.put("campaignName", ad.get("campaignName"));
                m.put("adAccountName", ad.get("adAccountName"));
                m.put("status", ad.getOrDefault("adSetStatus", ""));
                m.put("spend", BigDecimal.ZERO);
                m.put("revenue", BigDecimal.ZERO);
                m.put("orderProfit", BigDecimal.ZERO);
                m.put("orders", 0L);
                return m;
            });
            agg.put("spend", toBigDecimal(agg.get("spend")).add(toBigDecimal(ad.get("spend"))));
            agg.put("revenue", toBigDecimal(agg.get("revenue")).add(toBigDecimal(ad.get("sales"))));
            agg.put("orderProfit", toBigDecimal(agg.get("orderProfit")).add(toBigDecimal(ad.get("orderProfit"))));
            agg.put("orders", toLong(agg.get("orders")) + toLong(ad.get("orderCount")));
        }
    }

    private String buildAdSetDataJson(java.util.Collection<Map<String, Object>> adSets, LocalDate from, LocalDate to) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", from + " → " + to);
            data.put("totalAdSets", adSets.size());

            BigDecimal totalSpend = BigDecimal.ZERO;
            BigDecimal totalRevenue = BigDecimal.ZERO;
            long totalOrders = 0;

            List<Map<String, Object>> adSetList = new ArrayList<>();
            for (Map<String, Object> as : adSets) {
                BigDecimal spend = toBigDecimal(as.get("spend"));
                BigDecimal revenue = toBigDecimal(as.get("revenue"));
                long orders = toLong(as.get("orders"));

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
                am.put("name", as.get("name"));
                am.put("campaign", as.get("campaignName"));
                am.put("adAccount", as.get("adAccountName"));
                am.put("status", as.get("status"));
                am.put("spend", spend);
                am.put("revenue", revenue);
                am.put("orders", orders);
                am.put("roas", roas);
                am.put("cpo", cpo);
                am.put("orderProfit", as.get("orderProfit"));
                adSetList.add(am);
            }

            data.put("totalSpend", totalSpend);
            data.put("totalRevenue", totalRevenue);
            data.put("totalOrders", totalOrders);
            data.put("adSets", adSetList);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            log.error("Failed to build ad set data JSON: {}", e.getMessage());
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
