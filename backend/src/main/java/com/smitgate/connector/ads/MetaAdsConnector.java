package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.smitgate.config.SystemSettingRepository;
import com.smitgate.connector.pos.OrderRepository;
import com.smitgate.connector.pos.OrderStatusClassifier;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import com.smitgate.datasource.SyncState;
import com.smitgate.datasource.SyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetaAdsConnector implements AdsConnector {

    private static final String GRAPH_BASE = "https://graph.facebook.com";

    private final WebClient.Builder webClientBuilder;
    private final DataSourceService dataSourceService;
    private final DataSourceRepository dataSourceRepository;
    private final AdAccountRepository adAccountRepository;
    private final CampaignRepository campaignRepository;
    private final AdsMetricsDailyRepository metricsRepository;
    private final SyncStateRepository syncStateRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final OrderRepository orderRepository;

    // Fallback values from application.yml (used if DB settings not configured)
    @Value("${app.facebook.app-id}")
    private String defaultAppId;

    @Value("${app.facebook.app-secret}")
    private String defaultAppSecret;

    @Value("${app.facebook.redirect-uri}")
    private String defaultRedirectUri;

    @Value("${app.facebook.api-version}")
    private String apiVersion;

    @Value("${app.sync.ads.max-rows-per-account:5000}")
    private int maxRowsPerAccount;

    @Value("${app.sync.ads.max-campaigns-per-account:2000}")
    private int maxCampaignsPerAccount;

    @Value("${app.sync.ads.max-raw-json-chars:8000}")
    private int maxAdsRawJsonChars;

    /** Returns effective App ID: tenant DB value only (no global fallback). */
    private String getAppId(Long tenantId) {
        return findTenantSetting(tenantId, "meta.app_id")
                .map(s -> s.getValue())
                .filter(v -> v != null && !v.isBlank() && !v.equals("your_facebook_app_id"))
                .orElse(null);
    }

    /** Returns effective App Secret: tenant DB value only (no global fallback). */
    private String getAppSecret(Long tenantId) {
        return findTenantSetting(tenantId, "meta.app_secret")
                .map(s -> s.getValue())
                .filter(v -> v != null && !v.isBlank() && !v.equals("your_facebook_app_secret"))
                .orElse(null);
    }

    private java.util.Optional<com.smitgate.config.SystemSetting> findTenantSetting(Long tenantId, String key) {
        if (tenantId == null) return java.util.Optional.empty();
        String tenantKey = "tenant." + tenantId + "." + key;
        return systemSettingRepository.findById(tenantKey);
    }

    /**
     * Redirect URI must be deterministic in production.
     * We intentionally do NOT read it from DB to avoid stale localhost values
     * breaking OAuth after deployments.
     */
    private String getRedirectUri() {
        return defaultRedirectUri;
    }

    public String getEffectiveRedirectUri() {
        return getRedirectUri();
    }

    @Override
    public String getAuthorizationUrl(Long tenantId) {
        String effectiveAppId = getAppId(tenantId);
        if (effectiveAppId == null || effectiveAppId.isBlank() || effectiveAppId.equals("your_facebook_app_id")) {
                throw new IllegalStateException(
                    "Tenant hiện tại chưa cấu hình Meta App. Vào Cài đặt → Meta Ads để nhập App ID và App Secret của chính tenant này.");
        }

        assertMetaAppIsUsable(tenantId);

        return UriComponentsBuilder
                .fromUriString("https://www.facebook.com/" + apiVersion + "/dialog/oauth")
                .queryParam("client_id", effectiveAppId)
                .queryParam("redirect_uri", getRedirectUri())
                .queryParam("scope", "ads_read,ads_management,read_insights")
                .queryParam("state", tenantId.toString())
                .build().toUriString();
    }

    @Override
    public Long handleCallback(Long tenantId, String code) {
        // Step 1: Exchange code for token (external HTTP call — NO transaction held)
        URI tokenUri = UriComponentsBuilder
                .fromUriString(GRAPH_BASE + "/" + apiVersion + "/oauth/access_token")
                .queryParam("client_id", getAppId(tenantId))
                .queryParam("client_secret", getAppSecret(tenantId))
                .queryParam("redirect_uri", getRedirectUri())
                .queryParam("code", code)
                .build().toUri();

        JsonNode tokenResponse = webClientBuilder.build()
                .get()
                .uri(tokenUri)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(),
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "Không thể lấy Meta access token: " + body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        if (tokenResponse == null || !tokenResponse.has("access_token")) {
            throw new RuntimeException("Meta OAuth thất bại: không nhận được access token");
        }

        String accessToken = tokenResponse.get("access_token").asText();

        // Step 2: Persist token to DB (short transaction, connection held only for DB ops)
        return persistOAuthToken(tenantId, accessToken);
    }

    protected Long persistOAuthToken(Long tenantId, String accessToken) {
        // Keep DB work short. DataSourceService methods are transactional individually,
        // so the external HTTP token exchange is never part of a DB transaction.
        DataSource existing = dataSourceRepository.findByTenantId(tenantId).stream()
                .filter(ds -> ds.getType() == DataSource.Type.META_ADS)
                .findFirst().orElse(null);

        if (existing != null) {
            dataSourceService.updateSecret(tenantId, existing.getId(), accessToken);
            dataSourceService.activate(tenantId, existing.getId());
            return existing.getId();
        } else {
            DataSource ds = dataSourceService.create(
                    tenantId, DataSource.Type.META_ADS, "Meta Ads", null, accessToken);
            dataSourceService.activate(tenantId, ds.getId());
            return ds.getId();
        }
    }

    @Override
    public List<Map<String, Object>> listAdAccounts(Long tenantId, Long dataSourceId) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);

        URI uri = UriComponentsBuilder
                .fromUriString(GRAPH_BASE + "/" + apiVersion + "/me/adaccounts")
                .queryParam("fields", "id,name,account_status,currency,timezone_name")
                .queryParam("access_token", token)
                .queryParam("limit", "100")
                .build().toUri();

        JsonNode response = webClientBuilder.build()
                .get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.is4xxClientError(),
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new IllegalArgumentException(
                                        "Lỗi lấy danh sách tài khoản quảng cáo: " + body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        List<Map<String, Object>> accounts = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode acc : response.get("data")) {
                accounts.add(Map.of(
                        "id", acc.path("id").asText(),
                        "name", acc.path("name").asText(""),
                        "status", acc.path("account_status").asInt(0),
                        "currency", acc.path("currency").asText("VND"),
                        "timezone", acc.path("timezone_name").asText("Asia/Ho_Chi_Minh")
                ));
            }
        }
        return accounts;
    }

    @Transactional
    public List<AdAccount> selectAdAccounts(Long tenantId, Long dataSourceId,
                                             List<Map<String, String>> accounts) {
        List<AdAccount> saved = new ArrayList<>();
        for (Map<String, String> accData : accounts) {
            String extId = accData.get("id");
            if (extId == null || extId.isBlank()) continue;
            AdAccount acc = adAccountRepository.findByTenantIdAndPlatformAndExternalAccountId(
                    tenantId, AdAccount.Platform.META, extId
            ).orElseGet(() -> {
                AdAccount a = new AdAccount();
                a.setTenantId(tenantId);
                a.setDataSourceId(dataSourceId);
                a.setPlatform(AdAccount.Platform.META);
                a.setExternalAccountId(extId);
                return a;
            });
            acc.setName(accData.getOrDefault("name", ""));
            saved.add(adAccountRepository.save(acc));
        }
        return saved;
    }

    @Override
    public int syncMetricsDaily(Long tenantId, Long dataSourceId, LocalDate from, LocalDate to) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);

        List<AdAccount> accounts = adAccountRepository.findByTenantIdAndDataSourceId(tenantId, dataSourceId);
        if (accounts.isEmpty()) {
            log.warn("No ad accounts selected for dataSource={}", dataSourceId);
            return 0;
        }

        int totalSynced = 0;
        String lastError = null;
        for (AdAccount account : accounts) {
            try {
                int synced = syncAccountMetrics(tenantId, account, token, from, to);
                totalSynced += synced;
                log.info("Synced {} metric rows for account={}", synced, account.getExternalAccountId());
            } catch (Exception e) {
                log.error("Error syncing metrics for account {}: {}",
                        account.getExternalAccountId(), e.getMessage());
                lastError = "Lỗi sync metrics: " + e.getMessage();
            }
        }

        SyncState syncState = syncStateRepository.findByTenantIdAndDataSourceIdAndEntity(
                tenantId, dataSourceId, SyncState.Entity.ADS_METRICS
        ).orElseGet(() -> {
            SyncState ss = new SyncState();
            ss.setTenantId(tenantId);
            ss.setDataSourceId(dataSourceId);
            ss.setEntity(SyncState.Entity.ADS_METRICS);
            return ss;
        });
        syncState.setWatermark(LocalDateTime.now());
        syncStateRepository.save(syncState);

        if (totalSynced > 0) {
            dataSourceService.markSuccess(dataSourceId);
        } else if (lastError != null) {
            dataSourceService.markError(dataSourceId, lastError);
        }
        return totalSynced;
    }

    private void assertMetaAppIsUsable(Long tenantId) {
        String appId = getAppId(tenantId);
        String appSecret = getAppSecret(tenantId);

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("Thiếu cấu hình Meta App ID/App Secret cho tenant hiện tại.");
        }

        URI validateUri = UriComponentsBuilder
                .fromUriString(GRAPH_BASE + "/" + apiVersion + "/oauth/access_token")
                .queryParam("client_id", appId)
                .queryParam("client_secret", appSecret)
                .queryParam("grant_type", "client_credentials")
                .build().toUri();

        try {
            JsonNode node = webClientBuilder.build()
                    .get()
                    .uri(validateUri)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            res -> res.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new IllegalStateException(
                                            "Meta App không hợp lệ cho tenant hiện tại: " + body))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10));

            if (node == null || !node.has("access_token")) {
                throw new IllegalStateException("Meta App không hợp lệ cho tenant hiện tại: không lấy được app access token");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Meta App không khả dụng cho tenant hiện tại. Kiểm tra App ID/Secret của tenant. " + e.getMessage());
        }
    }

    private int syncAccountMetrics(Long tenantId, AdAccount account,
                                    String token, LocalDate from, LocalDate to) {
        // Build time_range as JSON - must be passed as a properly encoded query param
        String timeRange = String.format("{\"since\":\"%s\",\"until\":\"%s\"}", from, to);

        int count = 0;
        String after = "";

        while (true) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + account.getExternalAccountId() + "/insights")
                    .queryParam("fields", "campaign_id,campaign_name,spend,impressions,clicks,reach,ctr,cpc,cpm,actions")
                    .queryParam("time_range", timeRange)
                    .queryParam("level", "campaign")
                    .queryParam("time_increment", "1")
                    .queryParam("limit", "500")
                    .queryParam("access_token", token);

            if (!after.isBlank()) {
                uriBuilder.queryParam("after", after);
            }

            URI uri = uriBuilder.build().encode().toUri();

            JsonNode response = webClientBuilder.build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(status -> status.value() == 401 || status.value() == 403,
                            res -> res.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new IllegalArgumentException(
                                            "Meta access token hết hạn hoặc không có quyền. Kết nối lại tài khoản Meta."))))
                    .onStatus(status -> status.value() == 429,
                            res -> Mono.error(new RuntimeException("Meta API rate limit. Thử lại sau.")))
                    .onStatus(status -> status.is4xxClientError(),
                            res -> res.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new RuntimeException("Meta API lỗi: " + body))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(60));

            if (response != null && response.has("data")) {
                for (JsonNode row : response.get("data")) {
                    if (count >= maxRowsPerAccount) {
                        log.warn("Stop sync account={} due to row cap={} in one run",
                                account.getExternalAccountId(), maxRowsPerAccount);
                        return count;
                    }
                    try {
                        upsertMetric(tenantId, account, row);
                        count++;
                    } catch (Exception e) {
                        log.warn("Failed to upsert metric row: {}", e.getMessage());
                    }
                }
            }

            String nextAfter = response != null
                    ? response.path("paging").path("cursors").path("after").asText("")
                    : "";
            boolean hasNext = response != null
                    && response.path("paging").has("next")
                    && !nextAfter.isBlank();
            if (!hasNext) {
                break;
            }
            after = nextAfter;
        }

        return count;
    }

    private void upsertMetric(Long tenantId, AdAccount account, JsonNode row) {
        String extCampaignId = row.path("campaign_id").asText(null);
        String dateStr = row.path("date_start").asText(null);
        if (dateStr == null) return;
        LocalDate date = LocalDate.parse(dateStr);

        Long campaignId = null;
        if (extCampaignId != null && !extCampaignId.isBlank()) {
            Campaign campaign = campaignRepository.findByTenantIdAndPlatformAndExternalCampaignId(
                    tenantId, Campaign.Platform.META, extCampaignId
            ).orElseGet(() -> {
                Campaign c = new Campaign();
                c.setTenantId(tenantId);
                c.setAdAccountId(account.getId());
                c.setPlatform(Campaign.Platform.META);
                c.setExternalCampaignId(extCampaignId);
                c.setName(row.path("campaign_name").asText(""));
                c.setStatus("ACTIVE");
                return campaignRepository.save(c);
            });
            campaignId = campaign.getId();
        }

        AdsMetricsDaily metric = metricsRepository
                .findByTenantIdAndPlatformAndAdAccountIdAndCampaignIdAndDate(
                        tenantId, AdsMetricsDaily.Platform.META, account.getId(), campaignId, date)
                .orElseGet(() -> {
                    AdsMetricsDaily m = new AdsMetricsDaily();
                    m.setTenantId(tenantId);
                    m.setPlatform(AdsMetricsDaily.Platform.META);
                    m.setAdAccountId(account.getId());
                    m.setDate(date);
                    return m;
                });

        metric.setCampaignId(campaignId);
        metric.setSpend(parseDecimal(row, "spend"));
        metric.setImpressions(parseLong(row, "impressions"));
        metric.setClicks(parseLong(row, "clicks"));
        metric.setReach(parseLong(row, "reach"));
        metric.setCtr(parseDecimal(row, "ctr"));
        metric.setCpc(parseDecimal(row, "cpc"));
        metric.setCpm(parseDecimal(row, "cpm"));

        // Extract messageContacts from campaign-level actions (deduplicated by Meta)
        // Using messaging_first_reply: counts unique people who actually REPLIED to messages
        JsonNode actions = row.path("actions");
        if (actions != null && actions.isArray()) {
            long msgContacts = 0;
            for (JsonNode action : actions) {
                String actionType = action.path("action_type").asText("");
                if ("onsite_conversion.messaging_first_reply".equalsIgnoreCase(actionType)) {
                    msgContacts += action.path("value").asLong(0L);
                }
            }
            metric.setMessageContacts(msgContacts);
        }
        String raw = row.toString();
        if (raw.length() > maxAdsRawJsonChars) {
            raw = raw.substring(0, maxAdsRawJsonChars);
        }
        metric.setRawJson(raw);
        metricsRepository.save(metric);
    }

    private BigDecimal parseDecimal(JsonNode node, String field) {
        try {
            return node.has(field) && !node.get(field).isNull()
                    ? new BigDecimal(node.get(field).asText()) : BigDecimal.ZERO;
        } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private Long parseLong(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asLong() : 0L;
    }

    public List<Map<String, Object>> listCampaignHierarchy(Long tenantId, Long dataSourceId, String adAccountId) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);
        String normalizedAccountId = normalizeAdAccountId(adAccountId);
        List<Map<String, Object>> campaigns = new ArrayList<>();
        Set<String> seenCampaignIds = new HashSet<>();
        String after = null;

        while (true) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + normalizedAccountId + "/campaigns")
                    .queryParam("fields", "id,name,status,effective_status")
                    .queryParam("limit", "200")
                    .queryParam("access_token", token);
            if (after != null && !after.isBlank()) {
                uriBuilder.queryParam("after", after);
            }

            URI uri = uriBuilder.build().encode().toUri();
            JsonNode response = webClientBuilder.build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            res -> res.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new RuntimeException("Meta API lỗi lấy chiến dịch: " + body))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));

            JsonNode dataNode = response != null ? response.get("data") : null;
            if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
                break;
            }

            for (JsonNode row : dataNode) {
                String campaignId = row.path("id").asText("");
                if (campaignId.isBlank() || !seenCampaignIds.add(campaignId)) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("id", campaignId);
                item.put("name", row.path("name").asText(""));
                item.put("status", row.path("effective_status").asText(row.path("status").asText("UNKNOWN")));
                item.put("adAccountId", normalizedAccountId);
                campaigns.add(item);

                if (campaigns.size() >= maxCampaignsPerAccount) {
                    log.warn("Stop listing campaigns for account {} because cap {} reached",
                            normalizedAccountId, maxCampaignsPerAccount);
                    return campaigns;
                }
            }

            String nextAfter = response.path("paging").path("cursors").path("after").asText("");
            boolean hasNext = response.path("paging").has("next") && !nextAfter.isBlank();
            if (!hasNext) {
                break;
            }
            after = nextAfter;
        }

        return campaigns;
    }

    public List<Map<String, Object>> listAdSetHierarchy(Long tenantId, Long dataSourceId,
                                                         String adAccountId, String campaignId) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);
        String normalizedAccountId = normalizeAdAccountId(adAccountId);

        URI uri = UriComponentsBuilder
                .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + normalizedAccountId + "/adsets")
                .queryParam("fields", "id,name,status,effective_status,campaign_id,campaign{name}")
                .queryParam("limit", "200")
                .queryParam("access_token", token)
                .build().encode().toUri();

        JsonNode response = webClientBuilder.build()
                .get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException("Meta API lỗi lấy nhóm quảng cáo: " + body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        List<Map<String, Object>> adSets = new ArrayList<>();
        if (response != null && response.has("data") && response.get("data").isArray()) {
            for (JsonNode row : response.get("data")) {
                String rowCampaignId = row.path("campaign_id").asText("");
                if (campaignId != null && !campaignId.isBlank() && !campaignId.equals(rowCampaignId)) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("id", row.path("id").asText(""));
                item.put("name", row.path("name").asText(""));
                item.put("status", row.path("effective_status").asText(row.path("status").asText("UNKNOWN")));
                item.put("campaignId", rowCampaignId);
                item.put("campaignName", row.path("campaign").path("name").asText(""));
                item.put("adAccountId", normalizedAccountId);
                adSets.add(item);
            }
        }
        return adSets;
    }

    public List<Map<String, Object>> listAdHierarchy(Long tenantId, Long dataSourceId,
                                                      String adAccountId, String campaignId, String adSetId) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);
        String normalizedAccountId = normalizeAdAccountId(adAccountId);

        URI uri = UriComponentsBuilder
                .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + normalizedAccountId + "/ads")
                .queryParam("fields", "id,name,status,effective_status,adset_id,adset{name,campaign_id}")
                .queryParam("limit", "300")
                .queryParam("access_token", token)
                .build().encode().toUri();

        JsonNode response = webClientBuilder.build()
                .get()
                .uri(uri)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException("Meta API lỗi lấy quảng cáo: " + body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        List<Map<String, Object>> ads = new ArrayList<>();
        if (response != null && response.has("data") && response.get("data").isArray()) {
            for (JsonNode row : response.get("data")) {
                String rowAdSetId = row.path("adset_id").asText("");
                String rowCampaignId = row.path("adset").path("campaign_id").asText("");

                if (campaignId != null && !campaignId.isBlank() && !campaignId.equals(rowCampaignId)) {
                    continue;
                }
                if (adSetId != null && !adSetId.isBlank() && !adSetId.equals(rowAdSetId)) {
                    continue;
                }

                Map<String, Object> item = new HashMap<>();
                item.put("id", row.path("id").asText(""));
                item.put("name", row.path("name").asText(""));
                item.put("status", row.path("effective_status").asText(row.path("status").asText("UNKNOWN")));
                item.put("adSetId", rowAdSetId);
                item.put("adSetName", row.path("adset").path("name").asText(""));
                item.put("campaignId", rowCampaignId);
                item.put("adAccountId", normalizedAccountId);
                ads.add(item);
            }
        }
        return ads;
    }

    public List<Map<String, Object>> listAdPerformance(
            Long tenantId,
            Long dataSourceId,
            String adAccountId,
            LocalDate from,
            LocalDate to,
            String campaignId,
            String adSetId) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);
        String normalizedAccountId = normalizeAdAccountId(adAccountId);
        String strippedAccountId = normalizedAccountId.startsWith("act_")
            ? normalizedAccountId.substring(4)
            : normalizedAccountId;
        // DB may store externalAccountId with or without "act_" prefix – try both
        String adAccountName = adAccountRepository
            .findByTenantIdAndPlatformAndExternalAccountId(tenantId, AdAccount.Platform.META, normalizedAccountId)
            .or(() -> adAccountRepository.findByTenantIdAndPlatformAndExternalAccountId(tenantId, AdAccount.Platform.META, strippedAccountId))
            .map(AdAccount::getName)
            .filter(n -> n != null && !n.isBlank())
            .orElse(normalizedAccountId);

        LocalDate safeFrom = from != null ? from : LocalDate.now();
        LocalDate safeTo = to != null ? to : safeFrom;
        if (safeTo.isBefore(safeFrom)) {
            LocalDate tmp = safeFrom;
            safeFrom = safeTo;
            safeTo = tmp;
        }

        String insightsField = String.format(
            "insights.time_range({'since':'%s','until':'%s'}){spend,impressions,clicks,cpc,cpm,actions,cost_per_action_type,action_values}",
            safeFrom,
            safeTo
        );

        String fields = String.join(",",
                "id",
                "name",
                "created_time",
                "status",
                "effective_status",
                "adset{id,name,campaign_id,campaign{name},daily_budget,lifetime_budget}",
                insightsField
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        String after = null;
        while (true) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + normalizedAccountId + "/ads")
                    .queryParam("fields", fields)
                    .queryParam("limit", "200")
                    .queryParam("access_token", token);
            if (after != null && !after.isBlank()) {
                uriBuilder.queryParam("after", after);
            }

            URI uri = uriBuilder.build().encode().toUri();
            JsonNode response = webClientBuilder.build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            res -> res.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new RuntimeException("Meta API lỗi lấy hiệu suất quảng cáo: " + body))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(60));

            JsonNode dataNode = response != null ? response.path("data") : null;
            if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
                break;
            }

            for (JsonNode ad : dataNode) {
                JsonNode adset = ad.path("adset");
                String rowAdSetId = adset.path("id").asText("");
                String rowCampaignId = adset.path("campaign_id").asText("");

                if (campaignId != null && !campaignId.isBlank() && !campaignId.equals(rowCampaignId)) {
                    continue;
                }
                if (adSetId != null && !adSetId.isBlank() && !adSetId.equals(rowAdSetId)) {
                    continue;
                }

                JsonNode insightsRow = ad.path("insights").path("data").isArray() && !ad.path("insights").path("data").isEmpty()
                        ? ad.path("insights").path("data").get(0)
                        : null;

                BigDecimal spend = parseInsightDecimal(insightsRow, "spend");
                long impressions = parseInsightLong(insightsRow, "impressions");
                long clicks = parseInsightLong(insightsRow, "clicks");
                BigDecimal cpc = parseInsightDecimal(insightsRow, "cpc");
                BigDecimal cpm = parseInsightDecimal(insightsRow, "cpm");

                JsonNode actions = insightsRow != null ? insightsRow.path("actions") : null;
                JsonNode actionValues = insightsRow != null ? insightsRow.path("action_values") : null;
                JsonNode costPerActions = insightsRow != null ? insightsRow.path("cost_per_action_type") : null;

                BigDecimal comments = sumActionValues(actions, "comment", "post_comment");
                BigDecimal messageContacts = sumActionValues(actions,
                        "onsite_conversion.messaging_first_reply");

                BigDecimal leadPhones = maxActionValues(actions, "lead", "onsite_conversion.lead_grouped", "leadgen_grouped");
                // Meta-reported purchase value (from Pixel/CAPI) — stored separately for reference.
                // Do NOT use as "sales" — that column must only show POS-verified revenue
                // to avoid "ghost revenue" when Meta reports purchases but POS has 0 matching orders.
                BigDecimal metaPurchaseValue = sumActionValuesByContains(actionValues, "purchase");

                BigDecimal costPerResult = firstActionValue(costPerActions,
                        "onsite_conversion.messaging_first_reply",
                        "landing_page_view",
                        "link_click");
                if (costPerResult.compareTo(BigDecimal.ZERO) <= 0) {
                    costPerResult = cpc;
                }

                Map<String, Object> item = new HashMap<>();
                item.put("adId", ad.path("id").asText(""));
                item.put("adName", ad.path("name").asText(""));
                item.put("adAccountId", normalizedAccountId);
                item.put("adAccountName", adAccountName);
                item.put("createdDate", ad.path("created_time").asText(""));
                item.put("delivery", ad.path("effective_status").asText(ad.path("status").asText("UNKNOWN")));
                item.put("campaignId", rowCampaignId);
                item.put("campaignName", adset.path("campaign").path("name").asText(""));
                item.put("adSetId", rowAdSetId);
                item.put("adSetName", adset.path("name").asText(""));
                item.put("budget", parseBudget(adset.path("daily_budget"), adset.path("lifetime_budget")));
                item.put("comments", comments);
                item.put("messageContacts", messageContacts);
                item.put("costPerResult", costPerResult);
                item.put("averageCost", cpm);
                item.put("phoneCount", leadPhones);
                item.put("sales", BigDecimal.ZERO);
                item.put("metaPurchaseValue", metaPurchaseValue);
                item.put("spend", spend);
                item.put("impressions", impressions);
                item.put("clicks", clicks);
                item.put("cpc", cpc);
                item.put("cpm", cpm);
                rows.add(item);
            }

            String nextAfter = response.path("paging").path("cursors").path("after").asText("");
            boolean hasNext = response.path("paging").has("next") && !nextAfter.isBlank();
            if (!hasNext) {
                break;
            }
            after = nextAfter;
        }

        enrichWithPosOrderMetrics(tenantId, safeFrom, safeTo, rows);

        return rows;
    }

    private void enrichWithPosOrderMetrics(
            Long tenantId,
            LocalDate from,
            LocalDate to,
            List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<String> clickIds = rows.stream()
                .map(r -> String.valueOf(r.getOrDefault("adId", "")).trim())
                .filter(id -> !id.isBlank() && !"null".equalsIgnoreCase(id))
                .distinct()
                .toList();

        if (clickIds.isEmpty()) {
            return;
        }

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.atTime(LocalTime.MAX);

        Map<String, BigDecimal> revenueByClickId = new HashMap<>();
        Map<String, Long> orderCountByClickId = new HashMap<>();
        Map<String, BigDecimal> shippingFeeByClickId = new HashMap<>();
        for (Object[] row : orderRepository.aggregateRevenueAndOrderCountByClickIds(
                tenantId,
                clickIds,
                fromDateTime,
                toDateTime,
                OrderStatusClassifier.validStatusesForAggregation())) {
            String clickId = row[0] != null ? row[0].toString() : "";
            if (clickId.isBlank()) {
                continue;
            }
            orderCountByClickId.put(clickId, toLong(row[1]));
            revenueByClickId.put(clickId, toDecimal(row[2]));
            shippingFeeByClickId.put(clickId, toDecimal(row[3]));
        }

        Map<String, Long> phoneCountByClickId = new HashMap<>();
        for (Object[] row : orderRepository.aggregateDistinctPhonesByClickIds(
                tenantId,
                clickIds,
                fromDateTime,
                toDateTime,
                OrderStatusClassifier.leadStatusesForAggregation())) {
            String clickId = row[0] != null ? row[0].toString() : "";
            if (clickId.isBlank()) {
                continue;
            }
            phoneCountByClickId.put(clickId, toLong(row[1]));
        }

        for (Map<String, Object> item : rows) {
            String clickId = String.valueOf(item.getOrDefault("adId", "")).trim();
            long orderCount = orderCountByClickId.getOrDefault(clickId, 0L);
            long phoneCount = phoneCountByClickId.getOrDefault(clickId, 0L);

            if (orderCount > 0) {
                BigDecimal revenue = revenueByClickId.getOrDefault(clickId, BigDecimal.ZERO);
                BigDecimal shippingFee = shippingFeeByClickId.getOrDefault(clickId, BigDecimal.ZERO);
                item.put("sales", revenue);
                item.put("orderProfit", shippingFee);
                item.put("orderCount", orderCount);
            }
            if (phoneCount > 0) {
                // Keep the higher of Meta lead count vs POS distinct phone count.
                // Meta may report more leads (e.g. form fills) than POS has matched orders,
                // so we should not blindly replace a higher Meta count with a lower POS count.
                long metaLeadCount = toLong(item.getOrDefault("phoneCount", 0L));
                item.put("phoneCount", Math.max(metaLeadCount, phoneCount));
            }
        }
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private BigDecimal parseInsightDecimal(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return BigDecimal.ZERO;
        }
        try {
            String raw = node.path(field).asText("0");
            if (raw == null || raw.isBlank()) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(raw);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private long parseInsightLong(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return 0L;
        }
        try {
            return node.path(field).asLong(0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private BigDecimal parseBudget(JsonNode dailyBudget, JsonNode lifetimeBudget) {
        String raw = dailyBudget.asText("");
        if (raw == null || raw.isBlank()) {
            raw = lifetimeBudget.asText("0");
        }
        try {
            return new BigDecimal(raw);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal sumActionValues(JsonNode actions, String... actionTypes) {
        if (actions == null || !actions.isArray()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode action : actions) {
            String type = action.path("action_type").asText("");
            for (String expected : actionTypes) {
                if (expected.equalsIgnoreCase(type)) {
                    total = total.add(toDecimal(action.path("value").asText("0")));
                    break;
                }
            }
        }
        return total;
    }

    private BigDecimal sumActionValuesByContains(JsonNode actions, String keyword) {
        if (actions == null || !actions.isArray()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode action : actions) {
            String type = action.path("action_type").asText("").toLowerCase();
            if (type.contains(keyword.toLowerCase())) {
                total = total.add(toDecimal(action.path("value").asText("0")));
            }
        }
        return total;
    }

    /**
     * Return the MAX single value among matching action types (avoids double-counting
     * when Meta returns both a total type and a subset type in the same actions array).
     */
    private BigDecimal maxActionValues(JsonNode actions, String... actionTypes) {
        if (actions == null || !actions.isArray()) {
            return BigDecimal.ZERO;
        }
        BigDecimal max = BigDecimal.ZERO;
        for (JsonNode action : actions) {
            String type = action.path("action_type").asText("");
            for (String expected : actionTypes) {
                if (expected.equalsIgnoreCase(type)) {
                    BigDecimal val = toDecimal(action.path("value").asText("0"));
                    if (val.compareTo(max) > 0) {
                        max = val;
                    }
                    break;
                }
            }
        }
        return max;
    }

    private BigDecimal firstActionValue(JsonNode actions, String... actionTypes) {
        if (actions == null || !actions.isArray()) {
            return BigDecimal.ZERO;
        }
        for (String expected : actionTypes) {
            for (JsonNode action : actions) {
                String type = action.path("action_type").asText("");
                if (expected.equalsIgnoreCase(type)) {
                    return toDecimal(action.path("value").asText("0"));
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal toDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return toDecimal(value.toString());
    }

    /**
     * Debug endpoint: returns raw Meta API insights at campaign-level and ad-level
     * so the customer can verify exactly what Meta returns vs what we display.
     */
    public Map<String, Object> debugInsightsRaw(
            Long tenantId, Long dataSourceId, String adAccountId,
            LocalDate from, LocalDate to, String campaignId) {
        DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
        String token = dataSourceService.decryptSecret(ds);
        String normalizedAccountId = normalizeAdAccountId(adAccountId);

        String timeRange = String.format("{\"since\":\"%s\",\"until\":\"%s\"}", from, to);
        Map<String, Object> result = new HashMap<>();
        result.put("adAccountId", normalizedAccountId);
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("campaignIdFilter", campaignId);

        // 1. Campaign-level insights (deduplicated by Meta)
        try {
            UriComponentsBuilder campaignUri = UriComponentsBuilder
                    .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + normalizedAccountId + "/insights")
                    .queryParam("fields", "campaign_id,campaign_name,actions,spend,impressions,clicks,reach")
                    .queryParam("time_range", timeRange)
                    .queryParam("level", "campaign")
                    .queryParam("time_increment", "all_days")
                    .queryParam("limit", "500")
                    .queryParam("access_token", token);
            if (campaignId != null && !campaignId.isBlank()) {
                campaignUri.queryParam("filtering", "[{\"field\":\"campaign.id\",\"operator\":\"EQUAL\",\"value\":\"" + campaignId + "\"}]");
            }

            JsonNode campaignResponse = webClientBuilder.build()
                    .get().uri(campaignUri.build().encode().toUri())
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(60));

            List<Map<String, Object>> campaignRows = new ArrayList<>();
            long totalCampaignMsgContacts = 0;
            long totalCampaignLeads = 0;
            if (campaignResponse != null && campaignResponse.has("data")) {
                for (JsonNode row : campaignResponse.get("data")) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("campaign_id", row.path("campaign_id").asText(""));
                    item.put("campaign_name", row.path("campaign_name").asText(""));
                    item.put("spend", row.path("spend").asText("0"));
                    item.put("impressions", row.path("impressions").asText("0"));
                    item.put("clicks", row.path("clicks").asText("0"));

                    // Parse all actions
                    List<Map<String, String>> actionsList = new ArrayList<>();
                    long campaignLeadMax = 0;
                    JsonNode actions = row.path("actions");
                    if (actions != null && actions.isArray()) {
                        for (JsonNode action : actions) {
                            Map<String, String> a = new HashMap<>();
                            a.put("action_type", action.path("action_type").asText(""));
                            a.put("value", action.path("value").asText("0"));
                            actionsList.add(a);

                            String type = action.path("action_type").asText("");
                            if (type.equalsIgnoreCase("onsite_conversion.messaging_first_reply")) {
                                totalCampaignMsgContacts += action.path("value").asLong(0L);
                            }
                            // Track each lead type separately, then take max to avoid double-counting
                            if (type.equalsIgnoreCase("lead") || type.equalsIgnoreCase("onsite_conversion.lead_grouped") || type.equalsIgnoreCase("leadgen_grouped")) {
                                campaignLeadMax = Math.max(campaignLeadMax, action.path("value").asLong(0L));
                            }
                        }
                    }
                    totalCampaignLeads += campaignLeadMax;
                    item.put("actions", actionsList);
                    campaignRows.add(item);
                }
            }
            result.put("campaignLevel", campaignRows);
            result.put("campaignLevel_totalMessageContacts", totalCampaignMsgContacts);
            result.put("campaignLevel_totalLeads", totalCampaignLeads);
        } catch (Exception e) {
            result.put("campaignLevel_error", e.getMessage());
        }

        // 2. Ad-level insights (NOT deduplicated — same person messaging 2 ads = counted 2x)
        try {
            String insightsField = String.format(
                "insights.time_range({'since':'%s','until':'%s'}){actions,spend,impressions,clicks}",
                from, to);

            UriComponentsBuilder adUri = UriComponentsBuilder
                    .fromUriString(GRAPH_BASE + "/" + apiVersion + "/" + normalizedAccountId + "/ads")
                    .queryParam("fields", "id,name,adset{campaign_id,campaign{name}}," + insightsField)
                    .queryParam("limit", "200")
                    .queryParam("access_token", token);

            JsonNode adResponse = webClientBuilder.build()
                    .get().uri(adUri.build().encode().toUri())
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(60));

            List<Map<String, Object>> adRows = new ArrayList<>();
            long totalAdMsgContacts = 0;
            long totalAdLeads = 0;
            if (adResponse != null && adResponse.has("data")) {
                for (JsonNode ad : adResponse.get("data")) {
                    String adCampaignId = ad.path("adset").path("campaign_id").asText("");
                    if (campaignId != null && !campaignId.isBlank() && !campaignId.equals(adCampaignId)) {
                        continue;
                    }

                    Map<String, Object> item = new HashMap<>();
                    item.put("ad_id", ad.path("id").asText(""));
                    item.put("ad_name", ad.path("name").asText(""));
                    item.put("campaign_id", adCampaignId);
                    item.put("campaign_name", ad.path("adset").path("campaign").path("name").asText(""));

                    JsonNode insightsRow = ad.path("insights").path("data").isArray()
                            && !ad.path("insights").path("data").isEmpty()
                            ? ad.path("insights").path("data").get(0) : null;

                    List<Map<String, String>> actionsList = new ArrayList<>();
                    long adLeadMax = 0;
                    if (insightsRow != null) {
                        item.put("spend", insightsRow.path("spend").asText("0"));
                        item.put("impressions", insightsRow.path("impressions").asText("0"));
                        JsonNode actions = insightsRow.path("actions");
                        if (actions != null && actions.isArray()) {
                            for (JsonNode action : actions) {
                                Map<String, String> a = new HashMap<>();
                                a.put("action_type", action.path("action_type").asText(""));
                                a.put("value", action.path("value").asText("0"));
                                actionsList.add(a);

                                String type = action.path("action_type").asText("");
                                if (type.equalsIgnoreCase("onsite_conversion.messaging_first_reply")) {
                                    totalAdMsgContacts += action.path("value").asLong(0L);
                                }
                                // Track each lead type separately, then take max to avoid double-counting
                                if (type.equalsIgnoreCase("lead") || type.equalsIgnoreCase("onsite_conversion.lead_grouped") || type.equalsIgnoreCase("leadgen_grouped")) {
                                    adLeadMax = Math.max(adLeadMax, action.path("value").asLong(0L));
                                }
                            }
                        }
                    }
                    totalAdLeads += adLeadMax;
                    item.put("actions", actionsList);
                    adRows.add(item);
                }
            }
            result.put("adLevel", adRows);
            result.put("adLevel_totalMessageContacts", totalAdMsgContacts);
            result.put("adLevel_totalLeads", totalAdLeads);
        } catch (Exception e) {
            result.put("adLevel_error", e.getMessage());
        }

        // 3. Explanation
        result.put("explanation", Map.of(
            "messageContacts_campaignLevel", "Số liệu từ Meta API level=campaign, action_type=onsite_conversion.messaging_first_reply. Đếm số người THỰC SỰ trả lời tin nhắn (chính xác hơn messaging_conversation_started_7d). Meta TỰ ĐỘNG deduplicate.",
            "messageContacts_adLevel", "Số liệu từ Meta API level=ad, action_type=onsite_conversion.messaging_first_reply. KHÔNG deduplicate: 1 người nhắn qua 2 ads bị đếm 2 lần.",
            "leads", "Tổng tất cả action_type chứa 'lead'. Bao gồm lead form, leadgen_grouped, v.v. KHÔNG phải số điện thoại thực.",
            "so_sanh", "Nếu campaignLevel_totalMessageContacts < adLevel_totalMessageContacts, sự chênh lệch = số người nhắn tin qua NHIỀU quảng cáo."
        ));

        return result;
    }

    private String normalizeAdAccountId(String adAccountId) {
        if (adAccountId == null || adAccountId.isBlank()) {
            throw new IllegalArgumentException("Thiếu adAccountId");
        }
        String id = adAccountId.trim();
        if (id.startsWith("act_")) {
            return id;
        }
        return "act_" + id;
    }
}
