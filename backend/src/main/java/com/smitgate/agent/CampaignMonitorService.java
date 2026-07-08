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
    private final AgentSettingsService agentSettingsService;
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
        AgentSettingsService.AgentSettings settings = agentSettingsService.getSettings(tenantId);

        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        LocalDate from = today.minusDays(settings.analysisWindowDays() - 1L);

        List<AdAccount> accounts = adAccountRepository.findByTenantId(tenantId).stream()
                .filter(a -> a.getPlatform() == AdAccount.Platform.META)
                .toList();

        List<Map<String, Object>> activeAds = new ArrayList<>();
        for (AdAccount account : accounts) {
            try {
                List<Map<String, Object>> ads = metaAdsConnector.listAdPerformance(
                        tenantId, account.getDataSourceId(), account.getExternalAccountId(),
                        from, today, null, null);
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

        AdMetrics metrics = computeMetrics(activeAds, from, today, settings);

        if (metrics.alerts.isEmpty()) {
            log.info("No threshold breaches for tenant {} — skipping Claude call and Telegram notification", tenantId);
            return "✅ Tất cả " + activeAds.size() + " quảng cáo đang chạy đều trong ngưỡng an toàn — không có cảnh báo.";
        }

        String dataJson = toJson(metrics);
        String analysis = claudeAnalysisService.analyzeCampaigns(dataJson);

        if (analysis != null && !analysis.startsWith("Lỗi")) {
            telegramService.sendMessage(analysis);
            log.info("Sent analysis report for tenant {} via Telegram", tenantId);
        }

        return analysis;
    }

    private String toJson(AdMetrics metrics) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics.data);
        } catch (Exception e) {
            log.error("Failed to serialize ad data JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private record AdMetrics(Map<String, Object> data, List<Map<String, Object>> alerts) {
    }

    private AdMetrics computeMetrics(
            List<Map<String, Object>> ads, LocalDate from, LocalDate to, AgentSettingsService.AgentSettings settings) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", from + " → " + to);
            data.put("totalActiveAds", ads.size());
            data.put("thresholds", Map.of(
                    "costPerMessage", settings.costPerMessageThreshold(),
                    "costPerPhone", settings.costPerPhoneThreshold(),
                    "costPerOrder", settings.costPerOrderThreshold(),
                    "lossAfterAds", settings.lossAfterAdsThreshold()
            ));

            BigDecimal totalSpend = BigDecimal.ZERO;
            BigDecimal totalRevenue = BigDecimal.ZERO;
            long totalOrders = 0;

            List<Map<String, Object>> adList = new ArrayList<>();
            List<Map<String, Object>> alerts = new ArrayList<>();

            for (Map<String, Object> ad : ads) {
                BigDecimal spend = toBigDecimal(ad.get("spend"));
                BigDecimal revenue = toBigDecimal(ad.get("sales"));
                BigDecimal orderProfit = toBigDecimal(ad.get("orderProfit"));
                long orders = toLong(ad.get("orderCount"));
                long messages = toLong(ad.get("messageContacts"));
                long phones = toLong(ad.get("phoneCount"));
                String adName = String.valueOf(ad.getOrDefault("adName", ""));

                totalSpend = totalSpend.add(spend);
                totalRevenue = totalRevenue.add(revenue);
                totalOrders += orders;

                BigDecimal costPerMessage = messages > 0
                        ? spend.divide(BigDecimal.valueOf(messages), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                BigDecimal costPerPhone = phones > 0
                        ? spend.divide(BigDecimal.valueOf(phones), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                BigDecimal costPerOrder = orders > 0
                        ? spend.divide(BigDecimal.valueOf(orders), 0, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                BigDecimal profitAfterAds = orderProfit.subtract(spend);
                BigDecimal roas = spend.compareTo(BigDecimal.ZERO) > 0
                        ? revenue.divide(spend, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

                Map<String, Object> am = new LinkedHashMap<>();
                am.put("name", adName);
                am.put("adSet", ad.get("adSetName"));
                am.put("campaign", ad.get("campaignName"));
                am.put("adAccount", ad.get("adAccountName"));
                am.put("spend", spend);
                am.put("revenue", revenue);
                am.put("orders", orders);
                am.put("roas", roas);
                am.put("costPerMessage", costPerMessage);
                am.put("costPerPhone", costPerPhone);
                am.put("costPerOrder", costPerOrder);
                am.put("profitAfterAds", profitAfterAds);
                adList.add(am);

                if (messages > 0 && costPerMessage.compareTo(settings.costPerMessageThreshold()) > 0) {
                    alerts.add(alert(adName, "Chi phí tin nhắn mới " + costPerMessage
                            + "đ vượt ngưỡng " + settings.costPerMessageThreshold() + "đ"));
                }
                if (phones > 0 && costPerPhone.compareTo(settings.costPerPhoneThreshold()) > 0) {
                    alerts.add(alert(adName, "Chi phí số điện thoại mới " + costPerPhone
                            + "đ vượt ngưỡng " + settings.costPerPhoneThreshold() + "đ"));
                }
                if (orders > 0 && costPerOrder.compareTo(settings.costPerOrderThreshold()) > 0) {
                    alerts.add(alert(adName, "Chi phí/đơn hàng " + costPerOrder
                            + "đ vượt ngưỡng " + settings.costPerOrderThreshold() + "đ"));
                }
                if (profitAfterAds.negate().compareTo(settings.lossAfterAdsThreshold()) >= 0) {
                    alerts.add(alert(adName, "Lợi nhuận sau quảng cáo âm " + profitAfterAds
                            + "đ, vượt ngưỡng lỗ cho phép " + settings.lossAfterAdsThreshold() + "đ"));
                }
            }

            data.put("totalSpend", totalSpend);
            data.put("totalRevenue", totalRevenue);
            data.put("totalOrders", totalOrders);
            data.put("alerts", alerts);
            data.put("ads", adList);

            return new AdMetrics(data, alerts);
        } catch (Exception e) {
            log.error("Failed to compute ad metrics: {}", e.getMessage());
            return new AdMetrics(Map.of(), List.of());
        }
    }

    private Map<String, Object> alert(String adName, String reason) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("adName", adName);
        a.put("reason", reason);
        return a;
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
