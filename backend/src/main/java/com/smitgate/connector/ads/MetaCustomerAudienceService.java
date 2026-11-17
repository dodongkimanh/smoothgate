package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.connector.pos.OrderRepository;
import com.smitgate.connector.pos.OrderStatusClassifier;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Keeps two Meta customer-list audiences per ad account filled with the phones customers left in Pancake
 * (everyone who left a phone, and buyers only), and creates a lookalike of each once Meta accepts it.
 * The lists are replaced in full on every run, so new customers flow into the lookalikes automatically.
 *
 * <p>Phones are normalized to 84... and SHA-256 hashed before leaving the server; Meta never sees them in clear.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaCustomerAudienceService {

    private static final String GRAPH_BASE = "https://graph.facebook.com";
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Meta accepts at most 10,000 entries per upload request. */
    static final int BATCH_SIZE = 10_000;

    private static final Map<MetaCustomerAudience.Kind, String> NAMES = Map.of(
            MetaCustomerAudience.Kind.BUYERS, "Smoothgate | Khách đã mua (SĐT)",
            MetaCustomerAudience.Kind.ALL_PHONES, "Smoothgate | Tất cả khách để lại SĐT",
            MetaCustomerAudience.Kind.LAL_BUYERS, "Smoothgate | LAL 1% khách đã mua",
            MetaCustomerAudience.Kind.LAL_ALL_PHONES, "Smoothgate | LAL 2% tất cả SĐT");

    private final OrderRepository orderRepository;
    private final AdAccountRepository adAccountRepository;
    private final MetaCustomerAudienceRepository audienceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.facebook.api-version}")
    private String apiVersion;

    @Value("${app.meta-audiences.enabled:true}")
    private boolean enabled;

    /** Comma-separated "act_..." ids to limit the sync to; blank = every Meta ad account. */
    @Value("${app.meta-audiences.account-ids:}")
    private String accountIdsFilter;

    @Scheduled(
            fixedDelayString = "${app.meta-audiences.fixed-delay-ms:86400000}",
            initialDelayString = "${app.meta-audiences.initial-delay-ms:600000}")
    public synchronized void syncAll() {
        if (!enabled) {
            return;
        }
        List<DataSource> sources;
        try {
            sources = dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE);
        } catch (Exception e) {
            log.warn("Meta customer audiences skipped: {}", e.getMessage());
            return;
        }
        for (DataSource ds : sources) {
            try {
                syncDataSource(ds);
            } catch (Exception e) {
                log.error("Meta customer audiences failed for data source {}: {}", ds.getId(), e.getMessage());
            }
        }
    }

    void syncDataSource(DataSource ds) {
        Long tenantId = ds.getTenantId();
        List<AdAccount> accounts = adAccountRepository.findByTenantIdAndDataSourceId(tenantId, ds.getId()).stream()
                .filter(a -> a.getPlatform() == AdAccount.Platform.META)
                .filter(a -> isIncluded(a.getExternalAccountId()))
                .toList();
        if (accounts.isEmpty()) {
            return;
        }

        List<String> buyers = hashPhones(orderRepository.findDistinctBuyerPhones(
                tenantId, OrderStatusClassifier.validStatusesForAggregation()));
        List<String> allPhones = hashPhones(orderRepository.findDistinctPhones(
                tenantId, OrderStatusClassifier.leadStatusesForAggregation()));
        String token = dataSourceService.decryptSecret(ds);

        Map<String, MetaCustomerAudience> rows = new LinkedHashMap<>();
        for (MetaCustomerAudience row : audienceRepository.findByTenantId(tenantId)) {
            rows.put(row.getAdAccountId() + ":" + row.getKind(), row);
        }

        for (AdAccount account : accounts) {
            MetaCustomerAudience buyerList = row(rows, tenantId, account, MetaCustomerAudience.Kind.BUYERS);
            MetaCustomerAudience allList = row(rows, tenantId, account, MetaCustomerAudience.Kind.ALL_PHONES);
            syncList(token, account, buyerList, buyers);
            syncList(token, account, allList, allPhones);
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL_BUYERS), buyerList, 0.01);
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL_ALL_PHONES), allList, 0.02);
        }
        log.info("Meta customer audiences: tenant {} synced {} accounts ({} buyers, {} phones)",
                tenantId, accounts.size(), buyers.size(), allPhones.size());
    }

    private void syncList(String token, AdAccount account, MetaCustomerAudience row, List<String> hashes) {
        if (hashes.isEmpty()) {
            return;
        }
        try {
            if (row.getExternalAudienceId() == null) {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("name", NAMES.get(row.getKind()));
                form.add("subtype", "CUSTOM");
                form.add("description", "Tự động đồng bộ từ Smoothgate (SĐT khách trên Pancake), cập nhật mỗi ngày");
                form.add("customer_file_source", "USER_PROVIDED_ONLY");
                row.setExternalAudienceId(requireId(post(account.getExternalAccountId() + "/customaudiences", form, token)));
                row.setLastSyncedAt(null);
                audienceRepository.save(row);
            }
            // The first upload adds; later ones replace the whole list so removed or changed phones drop out.
            String edge = row.getLastSyncedAt() == null ? "users" : "usersreplace";
            upload(token, row.getExternalAudienceId(), edge, hashes);
            row.setLastSyncedAt(LocalDateTime.now(VIETNAM_ZONE));
            row.setLastCount(hashes.size());
            row.setLastError(null);
        } catch (Exception e) {
            String message = explain(e.getMessage(), account);
            if (isMissingObject(message)) {
                row.setExternalAudienceId(null);
                row.setLastSyncedAt(null);
            }
            row.setLastError(truncate(message));
            log.warn("Meta customer audience {} for {} failed: {}", row.getKind(), account.getExternalAccountId(), message);
        }
        audienceRepository.save(row);
    }

    /** Created once from a synced list; Meta refreshes a lookalike on its own as its source list changes. */
    private void ensureLookalike(String token, AdAccount account, MetaCustomerAudience row,
                                 MetaCustomerAudience source, double ratio) {
        if (row.getExternalAudienceId() != null || source.getExternalAudienceId() == null || source.getLastSyncedAt() == null) {
            return;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("name", NAMES.get(row.getKind()));
            form.add("subtype", "LOOKALIKE");
            form.add("origin_audience_id", source.getExternalAudienceId());
            form.add("lookalike_spec", objectMapper.writeValueAsString(Map.of("ratio", ratio, "country", "VN")));
            row.setExternalAudienceId(requireId(post(account.getExternalAccountId() + "/customaudiences", form, token)));
            row.setLastSyncedAt(LocalDateTime.now(VIETNAM_ZONE));
            row.setLastError(null);
        } catch (Exception e) {
            // Usually the source is still being matched or has fewer than 100 people; retried next run.
            String message = explain(e.getMessage(), account);
            row.setLastError(truncate(message));
            log.warn("Meta lookalike {} for {} not created: {}", row.getKind(), account.getExternalAccountId(), message);
        }
        audienceRepository.save(row);
    }

    private void upload(String token, String audienceId, String edge, List<String> hashes) throws Exception {
        long sessionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        List<List<String>> batches = batches(hashes);
        for (int i = 0; i < batches.size(); i++) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("session_id", sessionId);
            session.put("batch_seq", i + 1);
            session.put("last_batch_flag", i == batches.size() - 1);
            session.put("estimated_num_total", hashes.size());

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("session", objectMapper.writeValueAsString(session));
            form.add("payload", objectMapper.writeValueAsString(Map.of("schema", "PHONE_SHA256", "data", batches.get(i))));
            JsonNode res = post(audienceId + "/" + edge, form, token);
            if (!res.has("num_received")) {
                throw new IllegalStateException("Meta không xác nhận danh sách: " + res);
            }
        }
    }

    /** Normalized (84...), de-duplicated, SHA-256 hashed phones; invalid numbers are dropped. */
    static List<String> hashPhones(Collection<String> phones) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String phone : phones) {
            String n = MetaPurchaseEventService.normalizePhone(phone);
            if (n != null) normalized.add(n);
        }
        return normalized.stream().map(MetaPurchaseEventService::sha256).toList();
    }

    static List<List<String>> batches(List<String> items) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += BATCH_SIZE) {
            out.add(items.subList(i, Math.min(items.size(), i + BATCH_SIZE)));
        }
        return out;
    }

    private MetaCustomerAudience row(Map<String, MetaCustomerAudience> rows, Long tenantId, AdAccount account,
                                     MetaCustomerAudience.Kind kind) {
        return rows.computeIfAbsent(account.getId() + ":" + kind, k -> {
            MetaCustomerAudience row = new MetaCustomerAudience();
            row.setTenantId(tenantId);
            row.setAdAccountId(account.getId());
            row.setKind(kind);
            return row;
        });
    }

    private boolean isIncluded(String externalAccountId) {
        if (accountIdsFilter == null || accountIdsFilter.isBlank()) return true;
        Set<String> allowed = Arrays.stream(accountIdsFilter.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return allowed.contains(externalAccountId);
    }

    private static String requireId(JsonNode res) {
        String id = res.path("id").asText("");
        if (id.isBlank()) throw new IllegalStateException("Meta không trả về id đối tượng: " + res);
        return id;
    }

    /** Adds where to fix the most common setup problem: custom audience terms not accepted on the account. */
    private static String explain(String message, AdAccount account) {
        String m = message == null ? "Lỗi không rõ" : message;
        String lower = m.toLowerCase();
        if (lower.contains("terms") || lower.contains("điều khoản")) {
            String act = account.getExternalAccountId().replace("act_", "");
            return m + " — chấp nhận điều khoản đối tượng tùy chỉnh tại "
                    + "https://business.facebook.com/ads/manage/customaudiences/tos/?act=" + act;
        }
        return m;
    }

    private static boolean isMissingObject(String message) {
        String lower = message.toLowerCase();
        return lower.contains("does not exist") || lower.contains("không tồn tại");
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String token) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>(form);
        body.add("access_token", token);
        try {
            JsonNode res = webClientBuilder.build().post()
                    .uri(GRAPH_BASE + "/" + apiVersion + "/" + path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(body))
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(60));
            if (res == null) throw new IllegalStateException("Meta không phản hồi");
            return res;
        } catch (WebClientResponseException e) {
            // Meta's own message, never the request (which carries the token).
            String message = e.getStatusCode().toString();
            try {
                JsonNode err = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
                message = err.path("error_user_msg").asText(err.path("message").asText(message));
            } catch (Exception ignored) {
                // keep the status code
            }
            throw new IllegalStateException("Meta: " + message);
        }
    }
}
