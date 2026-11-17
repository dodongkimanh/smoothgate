package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds which ad account an ad belongs to ("act_..."), asking Meta once per ad and remembering the answer.
 * Ads never move between accounts, so the cache never expires. Ads Meta cannot read stay unresolved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaAdAccountResolver {

    private static final int BATCH = 50;

    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.facebook.api-version}")
    private String apiVersion;

    /** ad id → "act_..." (empty string = Meta could not tell us; not asked again). */
    private final Map<String, String> accountByAd = new ConcurrentHashMap<>();

    /** Ad id → "act_..." for the given ads; unknown ads are looked up on Meta in batches of 50. */
    public Map<String, String> accountsOf(Long tenantId, Collection<String> adIds) {
        List<String> missing = new ArrayList<>();
        for (String id : adIds) {
            if (id != null && id.matches("\\d+") && !accountByAd.containsKey(id) && !missing.contains(id)) missing.add(id);
        }
        if (!missing.isEmpty()) {
            String token = token(tenantId);
            if (token != null) {
                for (int i = 0; i < missing.size(); i += BATCH) {
                    lookup(missing.subList(i, Math.min(missing.size(), i + BATCH)), token);
                }
            }
        }
        Map<String, String> out = new HashMap<>();
        for (String id : adIds) {
            String act = id == null ? null : accountByAd.get(id);
            if (act != null && !act.isEmpty()) out.put(id, act);
        }
        return out;
    }

    private void lookup(List<String> ids, String token) {
        try {
            JsonNode res = get(String.join(",", ids), token);
            for (String id : ids) {
                String account = res.path(id).path("account_id").asText("");
                accountByAd.put(id, account.isEmpty() ? "" : "act_" + account);
            }
        } catch (Exception batchError) {
            // One unreadable ad fails the whole ?ids= call; fall back to asking one by one.
            if (ids.size() == 1) {
                // Remember only Meta's "can't read this ad"; a network hiccup is retried next time.
                if (batchError instanceof org.springframework.web.reactive.function.client.WebClientResponseException e
                        && e.getStatusCode().is4xxClientError()) {
                    accountByAd.put(ids.get(0), "");
                }
                return;
            }
            for (String id : ids) {
                lookup(List.of(id), token);
            }
        }
    }

    private JsonNode get(String ids, String token) {
        return webClientBuilder.build().get()
                .uri(uri -> uri.scheme("https").host("graph.facebook.com").path("/" + apiVersion + "/")
                        .queryParam("ids", ids)
                        .queryParam("fields", "account_id")
                        .queryParam("access_token", token)
                        .build())
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30));
    }

    private String token(Long tenantId) {
        try {
            for (DataSource ds : dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE)) {
                if (tenantId.equals(ds.getTenantId())) return dataSourceService.decryptSecret(ds);
            }
        } catch (Exception e) {
            log.warn("Meta token unavailable for ad account lookup: {}", e.getMessage());
        }
        return null;
    }
}
