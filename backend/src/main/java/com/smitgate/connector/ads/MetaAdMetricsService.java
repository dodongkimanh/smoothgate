package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Stores Meta insights per ad and day in ads_ad_metrics_daily (ads_metrics_daily only has campaign totals),
 * so each ad's spend and messages can be compared with the CRM leads tagged with its ad_id.
 *
 * <p>Every 30 minutes it refreshes yesterday and today for every selected ad account. When the table has
 * no rows for a tenant yet, the first run backfills the last {@value #BACKFILL_DAYS} days.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaAdMetricsService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int BACKFILL_DAYS = 30;
    private static final int MAX_ACTIONS_JSON = 4000;
    private static final String FIELDS = "campaign_id,campaign_name,adset_id,adset_name,ad_id,ad_name,"
            + "spend,impressions,reach,clicks,actions";

    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final AdAccountRepository adAccountRepository;
    private final JdbcTemplate jdbcTemplate;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.facebook.api-version}")
    private String apiVersion;

    @Value("${app.ad-metrics.enabled:true}")
    private boolean enabled;

    @Scheduled(
            fixedDelayString = "${app.ad-metrics.fixed-delay-ms:1800000}",
            initialDelayString = "${app.ad-metrics.initial-delay-ms:150000}")
    public void syncScheduled() {
        if (!enabled) return;
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        List<DataSource> sources;
        try {
            sources = dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE);
        } catch (Exception e) {
            log.warn("Ad metrics sync skipped: {}", e.getMessage());
            return;
        }
        for (DataSource ds : sources) {
            if (dataSourceService.isSoftDeleted(ds)) continue;
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ads_ad_metrics_daily WHERE tenant_id = ?", Integer.class, ds.getTenantId());
            LocalDate from = existing != null && existing == 0 ? today.minusDays(BACKFILL_DAYS) : today.minusDays(1);
            try {
                int rows = sync(ds, from, today);
                log.info("Ad metrics: tenant {} stored {} ad-day rows for {}..{}", ds.getTenantId(), rows, from, today);
            } catch (Exception e) {
                log.warn("Ad metrics sync failed for datasource {}: {}", ds.getId(), e.getMessage());
            }
        }
    }

    int sync(DataSource ds, LocalDate from, LocalDate to) {
        String token = dataSourceService.decryptSecret(ds);
        if (token == null || token.isBlank()) return 0;
        int rows = 0;
        for (AdAccount account : adAccountRepository.findByTenantIdAndDataSourceId(ds.getTenantId(), ds.getId())) {
            try {
                rows += syncAccount(ds.getTenantId(), account, token, from, to);
            } catch (Exception e) {
                // Never log the request URI: it carries the access token.
                log.warn("Ad metrics: account {} failed: {}", account.getExternalAccountId(), shortError(e));
            }
        }
        return rows;
    }

    private int syncAccount(Long tenantId, AdAccount account, String token, LocalDate from, LocalDate to) {
        String timeRange = String.format("{\"since\":\"%s\",\"until\":\"%s\"}", from, to);
        int count = 0;
        String after = "";
        while (true) {
            UriComponentsBuilder b = UriComponentsBuilder
                    .fromUriString("https://graph.facebook.com/" + apiVersion + "/" + account.getExternalAccountId() + "/insights")
                    .queryParam("level", "ad")
                    .queryParam("fields", FIELDS)
                    .queryParam("time_range", timeRange)
                    .queryParam("time_increment", "1")
                    .queryParam("limit", "500")
                    .queryParam("access_token", token);
            if (!after.isBlank()) b.queryParam("after", after);
            URI uri = b.build().encode().toUri();
            JsonNode res = webClientBuilder.build().get().uri(uri)
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(90));
            if (res == null) break;
            for (JsonNode row : res.path("data")) {
                upsert(tenantId, account, row);
                count++;
            }
            String next = res.path("paging").path("cursors").path("after").asText("");
            if (!res.path("paging").has("next") || next.isBlank()) break;
            after = next;
        }
        return count;
    }

    private void upsert(Long tenantId, AdAccount account, JsonNode row) {
        String adId = row.path("ad_id").asText("");
        String date = row.path("date_start").asText("");
        if (adId.isEmpty() || date.isEmpty()) return;
        JsonNode actions = row.path("actions");
        String actionsJson = actions.isArray() ? actions.toString() : null;
        if (actionsJson != null && actionsJson.length() > MAX_ACTIONS_JSON) {
            actionsJson = actionsJson.substring(0, MAX_ACTIONS_JSON);
        }
        jdbcTemplate.update("""
                INSERT INTO ads_ad_metrics_daily (tenant_id, ad_account_id, account_external, date, campaign_id,
                    campaign_name, adset_id, adset_name, ad_id, ad_name, spend, impressions, reach, clicks,
                    message_contacts, meta_leads, actions_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (tenant_id, ad_id, date) DO UPDATE SET
                    ad_account_id = EXCLUDED.ad_account_id, account_external = EXCLUDED.account_external,
                    campaign_id = EXCLUDED.campaign_id, campaign_name = EXCLUDED.campaign_name,
                    adset_id = EXCLUDED.adset_id, adset_name = EXCLUDED.adset_name, ad_name = EXCLUDED.ad_name,
                    spend = EXCLUDED.spend, impressions = EXCLUDED.impressions, reach = EXCLUDED.reach,
                    clicks = EXCLUDED.clicks, message_contacts = EXCLUDED.message_contacts,
                    meta_leads = EXCLUDED.meta_leads, actions_json = EXCLUDED.actions_json, updated_at = NOW()
                """,
                tenantId, account.getId(), account.getExternalAccountId(), Date.valueOf(LocalDate.parse(date)),
                text(row, "campaign_id"), text(row, "campaign_name"), text(row, "adset_id"), text(row, "adset_name"),
                adId, text(row, "ad_name"), decimal(row.path("spend").asText("0")),
                row.path("impressions").asLong(0), row.path("reach").asLong(0), row.path("clicks").asLong(0),
                action(actions, "onsite_conversion.messaging_first_reply"),
                action(actions, "onsite_conversion.lead"),
                actionsJson);
    }

    static long action(JsonNode actions, String type) {
        long total = 0;
        if (actions != null && actions.isArray()) {
            for (JsonNode a : actions) {
                if (type.equalsIgnoreCase(a.path("action_type").asText(""))) total += a.path("value").asLong(0);
            }
        }
        return total;
    }

    private static String text(JsonNode row, String field) {
        String v = row.path(field).asText("");
        if (v.isEmpty()) return null;
        return v.length() > 500 ? v.substring(0, 500) : v;
    }

    private static BigDecimal decimal(String v) {
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String shortError(Exception e) {
        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException w) {
            String body = w.getResponseBodyAsString();
            return w.getStatusCode().value() + " " + (body.length() > 300 ? body.substring(0, 300) : body);
        }
        String m = String.valueOf(e.getMessage());
        // WebClient request errors can include the URI (with the token); keep only the part before it.
        int q = m.indexOf("access_token");
        return q >= 0 ? m.substring(0, q) : m;
    }
}
