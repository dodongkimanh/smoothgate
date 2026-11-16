package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.connector.pos.Order;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports paid Pancake orders to Meta as Purchase events (Conversions API for business messaging),
 * so Meta can tie each sale to the ad conversation it came from. Without this Meta only sees messages,
 * cannot tell buyers from browsers, and Ads Manager shows almost no purchases.
 *
 * <p>Only orders in a confirmed status with revenue are sent — a sent event cannot be taken back.
 * Each order is sent once (event_id = order id, so a retry is de-duplicated by Meta too).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaPurchaseEventService {

    private static final String GRAPH_BASE = "https://graph.facebook.com";
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Meta rejects events older than 7 days; keep a margin so a slow run never sends a stale one. */
    private static final Duration MAX_EVENT_AGE = Duration.ofDays(7).minusHours(1);

    private final OrderRepository orderRepository;
    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.facebook.api-version}")
    private String apiVersion;

    @Value("${app.meta-capi.enabled:true}")
    private boolean enabled;

    /** Set while checking setup in Events Manager → Test events; events then don't count for ads. */
    @Value("${app.meta-capi.test-event-code:}")
    private String testEventCode;

    /** pageId → dataset id, resolved once per page. */
    private final Map<String, String> datasetByPage = new ConcurrentHashMap<>();

    @Scheduled(
            fixedDelayString = "${app.meta-capi.fixed-delay-ms:1800000}",
            initialDelayString = "${app.meta-capi.initial-delay-ms:300000}")
    public void sendPendingPurchases() {
        if (!enabled) {
            return;
        }
        List<DataSource> sources;
        try {
            sources = dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE);
        } catch (Exception e) {
            log.warn("Meta purchase events skipped: {}", e.getMessage());
            return;
        }
        Map<Long, DataSource> sourceByTenant = new LinkedHashMap<>();
        for (DataSource ds : sources) {
            sourceByTenant.putIfAbsent(ds.getTenantId(), ds);
        }
        sourceByTenant.forEach((tenantId, ds) -> {
            try {
                sendForTenant(tenantId, ds);
            } catch (Exception e) {
                log.error("Meta purchase events failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }

    void sendForTenant(Long tenantId, DataSource ds) {
        LocalDateTime now = LocalDateTime.now(VIETNAM_ZONE);
        LocalDateTime oldest = now.minus(MAX_EVENT_AGE);
        // Orders created up to 30 days ago may have been confirmed within the last 7 days.
        List<Order> candidates = orderRepository.findUnsentMetaPurchases(tenantId, now.minusDays(30));

        Map<String, List<Order>> byPage = new LinkedHashMap<>();
        for (Order order : candidates) {
            if (!OrderStatusClassifier.isValidOrderStatus(order.getStatus())) continue;
            LocalDateTime eventTime = eventTime(order, now);
            if (eventTime.isBefore(oldest)) continue;
            byPage.computeIfAbsent(order.getFbPageId(), k -> new ArrayList<>()).add(order);
        }
        if (byPage.isEmpty()) {
            return;
        }

        String userToken = dataSourceService.decryptSecret(ds);
        byPage.forEach((pageId, orders) -> {
            try {
                String pageToken = pageAccessToken(userToken, pageId);
                String datasetId = datasetFor(pageToken, pageId);
                int received = postEvents(pageToken, datasetId, pageId, orders, now);
                LocalDateTime sentAt = LocalDateTime.now(VIETNAM_ZONE);
                for (Order order : orders) {
                    order.setMetaPurchaseSentAt(sentAt);
                    order.setMetaPurchaseError(null);
                }
                orderRepository.saveAll(orders);
                log.info("Meta purchase events: page {} dataset {} sent {} accepted {}",
                        pageId, datasetId, orders.size(), received);
            } catch (Exception e) {
                String msg = truncate(e.getMessage(), 500);
                for (Order order : orders) {
                    order.setMetaPurchaseError(msg);
                }
                orderRepository.saveAll(orders);
                log.warn("Meta purchase events: page {} failed for {} orders: {}", pageId, orders.size(), msg);
            }
        });
    }

    /** When the order became a sale: the last Pancake update (confirmation) if known, never in the future. */
    private static LocalDateTime eventTime(Order order, LocalDateTime now) {
        LocalDateTime t = order.getUpdatedAtExternal() != null ? order.getUpdatedAtExternal() : order.getCreatedAtExternal();
        if (t == null || t.isAfter(now)) return now;
        return t;
    }

    private String pageAccessToken(String userToken, String pageId) {
        JsonNode page = get(pageId, Map.of("fields", "access_token"), userToken);
        String token = page.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("Tài khoản Facebook đã kết nối không quản lý trang " + pageId);
        }
        return token;
    }

    /** Each Page has one dataset for business messaging events; POST returns the existing one or creates it. */
    private String datasetFor(String pageToken, String pageId) {
        String cached = datasetByPage.get(pageId);
        if (cached != null) return cached;
        JsonNode res = post(pageId + "/dataset", new LinkedMultiValueMap<>(), pageToken);
        String id = res.path("id").asText("");
        if (id.isBlank()) {
            throw new IllegalStateException("Meta không trả về dataset cho trang " + pageId + ": " + res);
        }
        datasetByPage.put(pageId, id);
        return id;
    }

    private int postEvents(String pageToken, String datasetId, String pageId, List<Order> orders, LocalDateTime now) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Order order : orders) {
            Map<String, Object> userData = new LinkedHashMap<>();
            userData.put("page_id", pageId);
            userData.put("page_scoped_user_id", order.getFbPsid());
            String phone = normalizePhone(order.getCustomerPhone());
            if (phone != null) {
                userData.put("ph", List.of(sha256(phone)));
            }

            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("currency", "VND");
            customData.put("value", order.getRevenue().setScale(0, RoundingMode.HALF_UP).longValue());
            customData.put("order_id", order.getExternalOrderId());

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_name", "Purchase");
            event.put("event_time", eventTime(order, now).atZone(VIETNAM_ZONE).toEpochSecond());
            event.put("event_id", "smoothgate-order-" + order.getId());
            event.put("action_source", "business_messaging");
            event.put("messaging_channel", "messenger");
            event.put("user_data", userData);
            event.put("custom_data", customData);
            events.add(event);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        try {
            form.add("data", objectMapper.writeValueAsString(events));
        } catch (Exception e) {
            throw new IllegalStateException("Không tạo được dữ liệu sự kiện: " + e.getMessage());
        }
        if (testEventCode != null && !testEventCode.isBlank()) {
            form.add("test_event_code", testEventCode.trim());
        }
        JsonNode res = post(datasetId + "/events", form, pageToken);
        if (!res.has("events_received")) {
            throw new IllegalStateException("Meta không xác nhận sự kiện: " + res);
        }
        return res.get("events_received").asInt();
    }

    /** Vietnamese numbers to E.164 digits without "+": 0912... → 84912... */
    static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        if (digits.startsWith("0")) digits = "84" + digits.substring(1);
        if (digits.length() < 10 || digits.length() > 15) return null;
        return digits;
    }

    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode get(String path, Map<String, String> params, String token) {
        return call(() -> webClientBuilder.build().get()
                .uri(uri -> {
                    var b = uri.scheme("https").host("graph.facebook.com").path("/" + apiVersion + "/" + path);
                    params.forEach(b::queryParam);
                    return b.queryParam("access_token", token).build();
                })
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30)));
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String token) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>(form);
        body.add("access_token", token);
        return call(() -> webClientBuilder.build().post()
                .uri(GRAPH_BASE + "/" + apiVersion + "/" + path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(body))
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30)));
    }

    /** Runs a Graph call and turns Meta's error body into a readable message (never includes the token). */
    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            JsonNode res = request.get();
            if (res == null) throw new IllegalStateException("Meta không phản hồi");
            return res;
        } catch (WebClientResponseException e) {
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

    private static String truncate(String value, int max) {
        if (value == null) return "Lỗi không rõ";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
