package com.smitgate.connector.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import com.smitgate.datasource.SyncState;
import com.smitgate.datasource.SyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.AssertionFailure;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.core.NestedExceptionUtils;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class PancakeConnector {

    private final WebClient.Builder webClientBuilder;
    private final DataSourceService dataSourceService;
    private final DataSourceRepository dataSourceRepository;
    private final PosShopRepository posShopRepository;
    private final OrderRepository orderRepository;
    private final SyncStateRepository syncStateRepository;

    @Value("${app.poscake.base-url}")
    private String baseUrl;

    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private String appTimezone;

    @Value("${app.poscake.timestamp-source-timezone:UTC}")
    private String poscakeTimestampSourceTimezone;

    @Value("${app.sync.pos.max-pages-per-shop:200}")
    private int maxPagesPerShop;

    @Value("${app.sync.pos.max-pages-per-shop-full:3000}")
    private int maxPagesPerShopFull;

    @Value("${app.sync.pos.max-orders-per-shop:20000}")
    private int maxOrdersPerShop;

    @Value("${app.sync.pos.max-orders-per-shop-full:200000}")
    private int maxOrdersPerShopFull;

    @Value("${app.sync.pos.page-size:100}")
    private int pageSize;

    @Value("${app.sync.pos.max-raw-json-chars:12000}")
    private int maxRawJsonChars;

    @Value("${app.sync.pos.max-consecutive-upsert-failures:25}")
    private int maxConsecutiveUpsertFailures;

    @Value("${app.sync.pos.read-timeout-seconds:90}")
    private int readTimeoutSeconds;

    private final ConcurrentMap<String, ReentrantLock> syncLocks = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager entityManager;

    private ZoneId appZoneId;
    private ZoneId poscakeSourceZoneId;

    @PostConstruct
    void initTimezone() {
        try {
            this.appZoneId = ZoneId.of(appTimezone);
        } catch (Exception ex) {
            log.warn("Invalid app.timezone='{}', fallback to Asia/Ho_Chi_Minh", appTimezone);
            this.appZoneId = ZoneId.of("Asia/Ho_Chi_Minh");
        }

        try {
            this.poscakeSourceZoneId = ZoneId.of(poscakeTimestampSourceTimezone);
        } catch (Exception ex) {
            log.warn("Invalid app.poscake.timestamp-source-timezone='{}', fallback to UTC", poscakeTimestampSourceTimezone);
            this.poscakeSourceZoneId = ZoneId.of("UTC");
        }
    }

    /**
     * Validate API key and return list of shops.
     * Throws IllegalArgumentException with clear Vietnamese message on invalid key.
     */
    public List<Map<String, Object>> fetchShops(String apiKey) {
        log.info("Validating Poscake API key...");
        JsonNode response = webClientBuilder.build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("pos.pages.fm")
                        .path("/api/v1/shops")
                        .queryParam("api_key", apiKey)
                        .build())
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new IllegalArgumentException(
                                        "API key không hợp lệ hoặc không có quyền truy cập Poscake. " +
                                        "Vào Poscake → Cài đặt → Third-party connection → Webhook/API để tạo key."))))
                .onStatus(status -> status.is4xxClientError() && status.value() != 401 && status.value() != 403,
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new IllegalArgumentException("Lỗi kết nối Poscake (HTTP " + res.statusCode().value() + "): " + body))))
                .onStatus(status -> status.is5xxServerError(),
                        res -> Mono.error(new RuntimeException("Máy chủ Poscake đang gặp sự cố. Vui lòng thử lại sau.")))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));

        return parseShopsResponse(response);
    }

    private List<Map<String, Object>> parseShopsResponse(JsonNode response) {
        List<Map<String, Object>> shops = new ArrayList<>();
        if (response == null) return shops;

        // Poscake returns {"shops": [...], "success": true}
        // Fallback: try "data" or root array
        JsonNode dataNode;
        if (response.has("shops") && response.get("shops").isArray()) {
            dataNode = response.get("shops");
        } else if (response.has("data") && response.get("data").isArray()) {
            dataNode = response.get("data");
        } else if (response.isArray()) {
            dataNode = response;
        } else {
            log.warn("Unexpected shops response format: {}", response);
            return shops;
        }

        for (JsonNode shop : dataNode) {
            Map<String, Object> s = new HashMap<>();
            s.put("id", shop.path("id").asText());
            s.put("name", shop.has("name") ? shop.get("name").asText() : "Shop " + shop.path("id").asText());
            s.put("currency", shop.path("currency").asText("VND"));
            s.put("pages", shop.has("pages") ? shop.get("pages").size() : 0);
            shops.add(s);
        }
        return shops;
    }

    /**
     * Save selected shops for a data source.
     */
    @Transactional
    public List<PosShop> selectShops(Long tenantId, Long dataSourceId, List<Map<String, String>> shops) {
        List<PosShop> saved = new ArrayList<>();
        for (Map<String, String> shopData : shops) {
            String extId = shopData.get("id");
            if (extId == null || extId.isBlank()) continue;
            PosShop shop = posShopRepository.findByTenantIdAndExternalShopId(tenantId, extId)
                    .orElseGet(() -> {
                        PosShop s = new PosShop();
                        s.setTenantId(tenantId);
                        s.setExternalShopId(extId);
                        return s;
                    });
            // CRITICAL: always update dataSourceId so sync queries find the right shops
            shop.setDataSourceId(dataSourceId);
            shop.setName(shopData.getOrDefault("name", ""));
            saved.add(posShopRepository.save(shop));
        }
        return saved;
    }

    /**
     * Sync orders from Pancake POS with watermark incremental sync + pagination.
     */
    public int syncOrders(Long tenantId, Long dataSourceId) {
        return syncOrders(tenantId, dataSourceId, false);
    }

    public int syncOrders(Long tenantId, Long dataSourceId, boolean forceFullSync) {
        String lockKey = tenantId + ":" + dataSourceId;
        ReentrantLock lock = syncLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.warn("Skip POS sync because another sync is already running for tenant={} datasource={}", tenantId, dataSourceId);
            return 0;
        }

        try {
            DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);

            String apiKey = dataSourceService.decryptSecret(ds);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("API key chưa được cấu hình");
            }

            List<PosShop> shops = posShopRepository.findByTenantIdAndDataSourceId(tenantId, dataSourceId);
            if (shops.isEmpty()) {
                log.warn("No shops configured for dataSource={}", dataSourceId);
                return 0;
            }

            SyncState syncState = syncStateRepository.findByTenantIdAndDataSourceIdAndEntity(
                    tenantId, dataSourceId, SyncState.Entity.ORDERS
            ).orElseGet(() -> {
                SyncState ss = new SyncState();
                ss.setTenantId(tenantId);
                ss.setDataSourceId(dataSourceId);
                ss.setEntity(SyncState.Entity.ORDERS);
                return ss;
            });

            LocalDateTime since = forceFullSync ? null : syncState.getWatermark();
            if (forceFullSync) {
                log.info("Force full POS sync for tenant={} datasource={} (ignore watermark)", tenantId, dataSourceId);
            }
            int totalSynced = 0;
            boolean hasError = false;

            for (PosShop shop : shops) {
                try {
                    int synced = syncShopOrdersWithPaging(apiKey, tenantId, shop, since, forceFullSync);
                    totalSynced += synced;
                    log.info("Synced {} orders for shop={}", synced, shop.getExternalShopId());
                } catch (Exception e) {
                    log.error("Error syncing orders for shop {}: {}", shop.getExternalShopId(), e.getMessage());
                    dataSourceService.markError(dataSourceId, "Lỗi sync đơn hàng: " + e.getMessage());
                    hasError = true;
                }
            }

            syncState.setWatermark(LocalDateTime.now());
            syncStateRepository.save(syncState);

            if (!hasError) {
                dataSourceService.markSuccess(dataSourceId);
            }
            return totalSynced;
        } finally {
            lock.unlock();
            if (!lock.isLocked() && !lock.hasQueuedThreads()) {
                syncLocks.remove(lockKey, lock);
            }
        }
    }

    private int syncShopOrdersWithPaging(String apiKey, Long tenantId, PosShop shop, LocalDateTime since, boolean forceFullSync) {
        int page = 1;
        int total = 0;
        boolean hasMore = true;
        int effectiveMaxPages = forceFullSync ? Math.max(maxPagesPerShopFull, maxPagesPerShop) : maxPagesPerShop;
        int effectiveMaxOrders = forceFullSync ? Math.max(maxOrdersPerShopFull, maxOrdersPerShop) : maxOrdersPerShop;
        int effectivePageSize = Math.max(10, pageSize);

        while (hasMore) {
            if (page > effectiveMaxPages) {
                log.info("Stop sync shop={} due to max page cap={} (forceFullSync={}). Remaining orders will be fetched in next scheduled sync via watermark.",
                        shop.getExternalShopId(), effectiveMaxPages, forceFullSync);
                break;
            }
            if (total >= effectiveMaxOrders) {
                log.info("Stop sync shop={} due to max order cap={} in one run (forceFullSync={}). Remaining orders will be fetched in next scheduled sync via watermark.",
                        shop.getExternalShopId(), effectiveMaxOrders, forceFullSync);
                break;
            }

            final int currentPage = page;
            final String sinceStr = since != null
                    ? since.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) : null;

                JsonNode response = fetchOrdersPageWithRetry(
                    apiKey,
                    shop.getExternalShopId(),
                    currentPage,
                    effectivePageSize,
                    sinceStr,
                    forceFullSync
                );

            if (response == null) break;

            JsonNode dataNode = response.has("data") ? response.get("data") : response;
            if (!dataNode.isArray() || dataNode.isEmpty()) { hasMore = false; break; }

            int consecutiveFailures = 0;
            int consecutiveConnectionErrors = 0;
            for (JsonNode orderNode : dataNode) {
                try {
                    upsertOrderWithRetry(tenantId, shop.getId(), orderNode);
                    total++;
                    consecutiveFailures = 0;
                    consecutiveConnectionErrors = 0;
                }
                catch (Exception e) {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    String rootMsg = root != null ? root.getMessage() : e.getMessage();
                    log.error("Failed to upsert order {} for shop {}. root={}",
                            orderNode.path("id").asText(), shop.getExternalShopId(), rootMsg, e);

                    safeClearPersistenceContext();
                    consecutiveFailures++;

                    // Connection errors get a much tighter abort threshold (5 vs 25)
                    // to prevent hanging for 25+ minutes on a dead Supabase connection.
                    if (isConnectionError(e)) {
                        consecutiveConnectionErrors++;
                        if (consecutiveConnectionErrors >= 5) {
                            log.error("Abort sync for shop {} after {} consecutive CONNECTION errors (DB likely unavailable)",
                                    shop.getExternalShopId(), consecutiveConnectionErrors);
                            hasMore = false;
                            break;
                        }
                    }

                    if (consecutiveFailures >= maxConsecutiveUpsertFailures) {
                        log.error("Abort sync for shop {} after {} consecutive upsert failures",
                                shop.getExternalShopId(), consecutiveFailures);
                        hasMore = false;
                        break;
                    }

                    // Hibernate session can be poisoned after a failed flush; stop current page to avoid error storms.
                    if (e instanceof AssertionFailure || root instanceof AssertionFailure ||
                            e instanceof DataIntegrityViolationException ||
                            (rootMsg != null && rootMsg.contains("null id in com.smitgate.connector.pos.Order"))) {
                        continue;
                    }
                }

                if (total >= effectiveMaxOrders) {
                    break;
                }
            }

            // Clear first-level cache after each page to prevent memory buildup
            // (each save() already committed within its own transaction).
            safeClearPersistenceContext();

            // Pagination compatibility:
            // 1) Prefer explicit total_pages when API provides it.
            // 2) Fallback to size-based paging when total_pages is absent.
            Integer totalPages = null;
            if (response.has("total_pages") && response.get("total_pages").canConvertToInt()) {
                totalPages = response.get("total_pages").asInt();
            } else if (response.has("meta") && response.get("meta").has("total_pages")
                    && response.get("meta").get("total_pages").canConvertToInt()) {
                totalPages = response.get("meta").get("total_pages").asInt();
            }

            if (totalPages != null && totalPages > 0) {
                hasMore = currentPage < totalPages;
            } else {
                hasMore = dataNode.size() >= effectivePageSize;
            }
            page++;
        }
        return total;
    }

    private JsonNode fetchOrdersPageWithRetry(
            String apiKey,
            String externalShopId,
            int page,
            int preferredPerPage,
            String sinceStr,
            boolean forceFullSync) {

        LinkedHashSet<Integer> perPageCandidates = new LinkedHashSet<>();
        perPageCandidates.add(Math.max(10, preferredPerPage));
        perPageCandidates.add(50);
        perPageCandidates.add(20);

        List<String> sinceCandidates = new ArrayList<>();
        sinceCandidates.add(sinceStr);
        if (sinceStr != null) {
            sinceCandidates.add(null);
        }

        RuntimeException lastError = null;

        for (String candidateSince : sinceCandidates) {
            for (Integer candidatePerPage : perPageCandidates) {
                int attempts = isRetryableMode(candidateSince, sinceStr, candidatePerPage, preferredPerPage)
                    ? 4
                    : 3;

                for (int attempt = 1; attempt <= attempts; attempt++) {
                    try {
                        return fetchOrdersPage(apiKey, externalShopId, page, candidatePerPage, candidateSince);
                    } catch (RuntimeException ex) {
                        lastError = ex;
                        if (!isRetryablePoscakeError(ex) || attempt == attempts) {
                            break;
                        }
                        long backoffMillis = 400L * attempt;
                        sleepQuietly(backoffMillis);
                    }
                }
            }
        }

        String context = "shop=" + externalShopId + ", page=" + page +
                ", forceFullSync=" + forceFullSync +
                ", since=" + (sinceStr != null ? sinceStr : "null") +
                ", perPage=" + preferredPerPage;
        throw new RuntimeException("Poscake API lỗi sau khi retry/fallback (" + context + ")", lastError);
    }

    private JsonNode fetchOrdersPage(
            String apiKey,
            String externalShopId,
            int page,
            int perPage,
            String sinceStr) {
        return webClientBuilder.build()
                .get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .scheme("https")
                            .host("pos.pages.fm")
                            .path("/api/v1/shops/" + externalShopId + "/orders")
                            .queryParam("api_key", apiKey)
                            .queryParam("page", page)
                            .queryParam("per_page", perPage);
                    if (sinceStr != null) {
                        builder = builder.queryParam("updated_at_min", sinceStr);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(status -> status.is4xxClientError(),
                        res -> res.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException("Poscake API lỗi: " + body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(Math.max(30, readTimeoutSeconds)));
    }

    private boolean isRetryableMode(String candidateSince, String preferredSince, int candidatePerPage, int preferredPerPage) {
        return !Objects.equals(candidateSince, preferredSince) || candidatePerPage != preferredPerPage;
    }

    private boolean isRetryablePoscakeError(Throwable ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        if (root instanceof java.net.SocketTimeoutException || root instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (ex instanceof WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            return code == 429 || code >= 500;
        }
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
        return message.contains("500") || message.contains("502") || message.contains("503") ||
                message.contains("504") || message.contains("timeout") || message.contains("temporarily");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Retry wrapper for upsertOrder — handles transient DB connection errors
     * (SocketTimeoutException, connection resets) that are common on Supabase free tier.
     * Clears the Hibernate session and waits before retrying so HikariCP can evict the broken connection.
     */
    private void upsertOrderWithRetry(Long tenantId, Long posShopId, JsonNode node) {
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                upsertOrder(tenantId, posShopId, node);
                return;
            } catch (Exception e) {
                if (attempt < maxAttempts && isConnectionError(e)) {
                    log.warn("Retrying order {} after DB connection error (attempt {}/{})",
                            node.path("id").asText(), attempt, maxAttempts);
                    safeClearPersistenceContext();
                    sleepQuietly(1000L * attempt);
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * Detects transient DB connection errors that are worth retrying.
     */
    private boolean isConnectionError(Exception e) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        if (root instanceof java.net.SocketTimeoutException) return true;
        if (root instanceof java.io.IOException) return true;
        if (e instanceof org.springframework.dao.DataAccessResourceFailureException) return true;
        if (e instanceof org.springframework.dao.TransientDataAccessException) return true;
        String msg = root != null ? root.getMessage() : null;
        return msg != null && (msg.contains("Connection reset")
                || msg.contains("Read timed out")
                || msg.contains("An I/O error occurred")
                || msg.contains("This connection has been closed"));
    }

    /**
     * Upsert a single order using ACTUAL Poscake POS API field names.
     *
     * Real Poscake field mapping (confirmed from API response):
     *   id             → externalOrderId
     *   inserted_at    → createdAtExternal  (NOT created_at)
     *   updated_at     → updatedAtExternal
     *   status_name    → status  (status is integer, status_name is readable string)
     *   total_price    → revenue (0 if unpaid; prepaid = amount already paid)
     *   shipping_address.phone_number → customerPhone  (nested!)
     *   shipping_address.full_name    → customer name
     *   p_utm_source   → utmSource   (prefix p_ not utm_)
     *   p_utm_medium   → utmMedium
     *   p_utm_campaign → utmCampaign
     *   p_utm_content  → utmContent
     *   p_utm_term     → utmTerm
     */
    private void upsertOrder(Long tenantId, Long posShopId, JsonNode node) {
        // Poscake order id can be integer; normalize to string
        String extId = node.path("id").asText(null);
        if (extId == null || extId.isBlank() || extId.equals("null")) return;

        Order order = orderRepository.findByTenantIdAndPosShopIdAndExternalOrderId(tenantId, posShopId, extId)
                .orElseGet(() -> {
                    Order o = new Order();
                    o.setTenantId(tenantId);
                    o.setPosShopId(posShopId);
                    o.setExternalOrderId(extId);
                    return o;
                });

        // Status: use status_name (human-readable) rather than integer status code
        if (node.has("status_name") && !node.get("status_name").isNull()) {
            order.setStatus(truncate(normalizeOrderStatus(node.get("status_name").asText()), 50));
        } else if (!node.path("status").isMissingNode()) {
            order.setStatus(truncate(normalizeOrderStatus(mapPoscakeStatus(node.get("status").asInt(-1))), 50));
        }

        // Business mapping from customer requirement:
        // - "Phụ thu" = doanh thu
        // - fallback to total_price/prepaid when phụ thu is absent
        BigDecimal revenue = firstNonZeroDecimal(node,
            "phu_thu", "phuThu", "phụ_thu", "phu thu", "surcharge", "extra_fee", "additional_fee",
                "total_price", "prepaid");
        order.setRevenue(revenue);

        // Shipping fee (Phí VC): Pancake uses "partner_fee" for delivery partner cost
        BigDecimal shippingFee = firstNonZeroDecimal(node,
                "partner_fee", "ship_fee", "bill_ship_fee", "shipping_fee", "shippingFee",
                "phi_vc", "phí_vc", "fee_ship", "phi_ship", "tien_ship",
                "delivery_fee", "shipping_cost");
        // Deep scan fallback: walk ALL fields looking for shipping-related keywords
        if (shippingFee.compareTo(BigDecimal.ZERO) == 0) {
            shippingFee = deepScanForShippingFee(node);
        }
        if (shippingFee.compareTo(BigDecimal.ZERO) != 0) {
            log.debug("Order {} shipping fee extracted: {}", extId, shippingFee);
        } else {
            log.info("Order {} shipping fee = 0. Top-level keys: {}", extId, iteratorToKeyList(node));
        }
        order.setShippingFee(shippingFee);

        // Customer phone & name: nested inside shipping_address
        JsonNode addr = node.path("shipping_address");
        if (!addr.isMissingNode()) {
            String phone = addr.path("phone_number").asText(null);
            if (phone != null && !phone.isBlank() && !phone.equals("null")) {
                order.setCustomerPhone(truncate(phone, 50));
            }
            String fullName = addr.path("full_name").asText(null);
            if (fullName != null && !fullName.isBlank() && !fullName.equals("null")) {
                order.setCustomerName(truncate(fullName, 255));
            }
        }
        // Fallback: direct phone fields
        if (order.getCustomerPhone() == null) {
            if (node.has("phone") && !node.get("phone").isNull()) {
                order.setCustomerPhone(truncate(node.get("phone").asText(), 50));
            }
        }
        // Fallback: direct name fields
        if (order.getCustomerName() == null) {
            for (String nameField : new String[]{"customer_name", "customerName", "name", "buyer_name", "receiver_name"}) {
                if (node.has(nameField) && !node.get(nameField).isNull()) {
                    String val = node.get(nameField).asText("").trim();
                    if (!val.isBlank()) {
                        order.setCustomerName(truncate(val, 255));
                        break;
                    }
                }
            }
        }

        // Timestamps: Poscake uses inserted_at (NOT created_at)
        String insertedAt = node.path("inserted_at").asText(null);
        String updatedAt = node.path("updated_at").asText(null);
        order.setCreatedAtExternal(parseDateTime(insertedAt != null ? insertedAt : node.path("created_at").asText(null)));
        order.setUpdatedAtExternal(parseDateTime(updatedAt));

        // UTM: Poscake uses p_utm_* prefix at root level
        setIfPresent(node, "p_utm_source",   order.getUtmSource(),   order::setUtmSource);
        setIfPresent(node, "p_utm_medium",   order.getUtmMedium(),   order::setUtmMedium);
        setIfPresent(node, "p_utm_campaign", order.getUtmCampaign(), order::setUtmCampaign);
        setIfPresent(node, "p_utm_content",  order.getUtmContent(),  order::setUtmContent);
        setIfPresent(node, "p_utm_term",     order.getUtmTerm(),     order::setUtmTerm);
        hydrateTrackingId(order, node);

        // Also check note field for UTM params (tracking snippet may embed them)
        String note = node.path("note").asText("");
        parseUtmFromNote(order, note);
        parseTrackingIdFromText(note, order.getClickId(), order::setClickId);

        // Final fallback: parse full payload text for tracking tokens when fields are nested/non-standard.
        parseTrackingIdFromText(node.toString(), order.getClickId(), order::setClickId);

        // Store raw JSON with smart truncation: always preserve valid JSON structure
        String raw = node.toString();
        if (raw.length() > maxRawJsonChars) {
            // Instead of slicing mid-field (produces unparseable JSON), truncate but close the object
            raw = raw.substring(0, maxRawJsonChars - 20) + "\"__truncated\":true}";
        }
        order.setRawJson(raw);
        orderRepository.save(order);
    }

    /** Map Poscake integer status codes to English lowercase slugs (must match VALID/LEAD_ORDER_STATUSES). */
    private String mapPoscakeStatus(int code) {
        return switch (code) {
            case 0 -> "new";
            case 1 -> "submitted";
            case 2 -> "confirmed";   // was "Đã xác nhận" — Vietnamese caused SQL mismatch
            case 3 -> "packaging";
            case 4 -> "waiting_shipping";
            case 5 -> "shipping";
            case 6 -> "delivered";
            case 7 -> "cancelled";
            case 8 -> "returned";
            case 9 -> "received_money";
            default -> String.valueOf(code);
        };
    }

    /** Set field only if destination is blank and source has a non-null value */
    private void setIfPresent(JsonNode node, String field, String current, Consumer<String> setter) {
        if (current != null && !current.isBlank()) return;
        if (node.has(field) && !node.get(field).isNull()) {
            String val = node.get(field).asText();
            if (!val.isBlank() && !val.equals("null")) setter.accept(truncateByField(field, val));
        }
    }

    private void setIfPresentByPath(JsonNode node, String[] path, String fieldHint,
                                    String current, Consumer<String> setter) {
        if (current != null && !current.isBlank()) return;
        JsonNode cursor = node;
        for (String part : path) {
            if (cursor == null || cursor.isMissingNode()) {
                return;
            }
            cursor = cursor.path(part);
        }
        if (cursor == null || cursor.isMissingNode() || cursor.isNull()) {
            return;
        }
        String val = cursor.asText();
        if (!val.isBlank() && !"null".equalsIgnoreCase(val)) {
            setter.accept(truncateByField(fieldHint, val));
        }
    }

    private void hydrateTrackingId(Order order, JsonNode node) {
        // Common root-level keys
        setIfPresent(node, "p_utm_id", order.getClickId(), order::setClickId);
        setIfPresent(node, "utm_id", order.getClickId(), order::setClickId);
        setIfPresent(node, "ad_id", order.getClickId(), order::setClickId);
        setIfPresent(node, "adId", order.getClickId(), order::setClickId);
        setIfPresent(node, "AD_ID", order.getClickId(), order::setClickId);
        setIfPresent(node, "click_id", order.getClickId(), order::setClickId);
        setIfPresent(node, "fbclid", order.getClickId(), order::setClickId);
        setIfPresent(node, "gclid", order.getClickId(), order::setClickId);
        setIfPresent(node, "ttclid", order.getClickId(), order::setClickId);

        // Common nested patterns observed in webhook/proxy payloads
        setIfPresentByPath(node, new String[] {"tracking", "p_utm_id"}, "p_utm_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"tracking", "ad_id"}, "ad_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"tracking", "click_id"}, "click_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"utm", "utm_id"}, "utm_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"metadata", "ad_id"}, "ad_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"meta_data", "ad_id"}, "ad_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"data", "ad_id"}, "ad_id", order.getClickId(), order::setClickId);
        setIfPresentByPath(node, new String[] {"attributes", "ad_id"}, "ad_id", order.getClickId(), order::setClickId);
    }

    /**
     * Parse UTM params from note text like:
     * "utm_source=facebook&utm_medium=cpc&utm_campaign=summer2024"
     * or JSON: {"utm_source":"facebook","utm_campaign":"summer2024"}
     */
    private void parseUtmFromNote(Order order, String note) {
        if (note == null || note.isBlank()) return;
        extractUtm(note, "utm_source", order.getUtmSource(), order::setUtmSource);
        extractUtm(note, "utm_medium", order.getUtmMedium(), order::setUtmMedium);
        extractUtm(note, "utm_campaign", order.getUtmCampaign(), order::setUtmCampaign);
        extractUtm(note, "utm_content", order.getUtmContent(), order::setUtmContent);
        extractUtm(note, "utm_term", order.getUtmTerm(), order::setUtmTerm);
    }

    private void extractUtm(String text, String param, String existing, Consumer<String> setter) {
        if (existing != null && !existing.isBlank()) return;
        // Match key=value in query string or JSON
        Matcher m = Pattern.compile("(?:^|[&?\"\\s])" + Pattern.quote(param) + "[=\":]+([^&\"\\s}]+)")
                .matcher(text);
        if (m.find()) setter.accept(truncateByField(param, m.group(1).trim()));
    }

    private void parseTrackingIdFromText(String text, String existing, Consumer<String> setter) {
        if (text == null || text.isBlank()) return;
        extractUtm(text, "p_utm_id", existing, setter);
        extractUtm(text, "utm_id", existing, setter);
        extractUtm(text, "ad_id", existing, setter);
        extractUtm(text, "adId", existing, setter);
        extractUtm(text, "AD_ID", existing, setter);
        extractUtm(text, "click_id", existing, setter);
        extractUtm(text, "fbclid", existing, setter);
        extractUtm(text, "gclid", existing, setter);
        extractUtm(text, "ttclid", existing, setter);
    }

    /**
     * Deep-scan ALL fields (root + 1 level nested) for anything that looks like a shipping fee.
     * Matches field names containing: ship, fee, phi, vc, van_chuyen, freight, delivery, giao, chuyen.
     * Skips fields that are clearly NOT shipping (e.g. total_price, phu_thu, prepaid, revenue).
     */
    private BigDecimal deepScanForShippingFee(JsonNode node) {
        java.util.regex.Pattern shipPattern = java.util.regex.Pattern.compile(
                "(?i)(ship|phi.*v|v.*c.*fee|van.?chuyen|freight|deliver|giao.*hang|phi_giao|tien.*ship)");
        java.util.regex.Pattern excludePattern = java.util.regex.Pattern.compile(
                "(?i)(total_price|phu_thu|prepaid|revenue|surcharge|discount|tax|status|phone|name|address|id$|_at$|_id$)");

        // Root level
        BigDecimal found = scanNodeForShipFee(node, shipPattern, excludePattern);
        if (found.compareTo(BigDecimal.ZERO) != 0) return found;

        // 1-level nested objects
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            JsonNode child = entry.getValue();
            if (child.isObject()) {
                found = scanNodeForShipFee(child, shipPattern, excludePattern);
                if (found.compareTo(BigDecimal.ZERO) != 0) {
                    log.info("Deep scan found shipping fee in nested object '{}': {}", entry.getKey(), found);
                    return found;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal scanNodeForShipFee(JsonNode node, java.util.regex.Pattern shipPattern,
                                          java.util.regex.Pattern excludePattern) {
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            if (!val.isValueNode()) continue;
            if (excludePattern.matcher(key).find()) continue;
            if (shipPattern.matcher(key).find()) {
                BigDecimal parsed = parseDecimalField(node, key);
                if (parsed.compareTo(BigDecimal.ZERO) != 0) {
                    log.info("Deep scan matched field '{}' = {}", key, parsed);
                    return parsed;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private String iteratorToKeyList(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return String.join(", ", keys);
    }

    private BigDecimal firstNonZeroDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal val = parseDecimalField(node, field);
            if (val.compareTo(BigDecimal.ZERO) != 0) {
                return val;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal parseDecimalField(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            String raw = node.get(field).asText("0").replace(",", "").trim();
            if (raw.isBlank()) {
                return BigDecimal.ZERO;
            }
            return parseCurrency(raw);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal parseCurrency(String raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replace("đ", "")
                .replace("vnd", "")
                .replaceAll("\\s+", "")
                .trim();

        if (normalized.isBlank()) {
            return BigDecimal.ZERO;
        }

        // If both separators exist, infer decimal separator by right-most symbol.
        int lastDot = normalized.lastIndexOf('.');
        int lastComma = normalized.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastDot > lastComma) {
                normalized = normalized.replace(",", "");
            } else {
                normalized = normalized.replace(".", "").replace(',', '.');
            }
        } else if (lastComma >= 0) {
            long commaCount = normalized.chars().filter(ch -> ch == ',').count();
            if (commaCount > 1) {
                normalized = normalized.replace(",", "");
            } else {
                int idx = normalized.indexOf(',');
                int decimals = normalized.length() - idx - 1;
                if (decimals == 3) {
                    normalized = normalized.replace(",", "");
                } else {
                    normalized = normalized.replace('.', ' ').replace(" ", "").replace(',', '.');
                }
            }
        } else {
            // Keep dots as decimals when present, but strip grouping chars like underscores.
            normalized = normalized.replace("_", "");
        }

        normalized = normalized.replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank() || normalized.equals("-") || normalized.equals(".")) {
            return BigDecimal.ZERO;
        }

        // In case thousands dots remain (e.g. 13.000.000), collapse to integer.
        long dotCount = normalized.chars().filter(ch -> ch == '.').count();
        if (dotCount > 1) {
            normalized = normalized.replace(".", "");
        } else if (dotCount == 1 && lastComma < 0) {
            int idx = normalized.indexOf('.');
            int decimals = normalized.length() - idx - 1;
            if (decimals == 3) {
                normalized = normalized.replace(".", "");
            }
        }

        return new BigDecimal(normalized);
    }

    /**
     * Normalize a raw status string to a consistent, SQL-matchable English form.
     *
     * CRITICAL FIX: do NOT convert to Vietnamese ("Đã xác nhận") here.
     * The DB column is queried with LOWER(o.status) IN :validStatuses where the list
     * contains English lowercase values. Storing Vietnamese would break status filters.
     *
     * Normalize any Poscake status_name value to a canonical English slug.
     * Canonical slugs must match mapPoscakeStatus() output and the STATUS_MAP on the frontend.
     * All DB records store these lowercase English slugs; the frontend maps them to Vietnamese labels.
     */
    private String normalizeOrderStatus(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }
        String trimmed = rawStatus.trim();
        if (trimmed.isBlank()) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);

        // Remove Vietnamese diacritics for matching
        String ascii = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return switch (ascii) {
            // new / mới
            case "new", "moi" -> "new";
            // pending / chờ xử lý
            case "pending", "cho xu ly", "cho_xu_ly" -> "pending";
            // submitted / đã tiếp nhận
            case "submitted", "da tiep nhan", "da_tiep_nhan" -> "submitted";
            // confirmed / đã xác nhận
            case "confirmed", "da xac nhan", "da_xac_nhan", "xac nhan" -> "confirmed";
            // packaging / đang đóng gói
            case "packaging", "dang dong goi", "dong goi" -> "packaging";
            // waiting_shipping / chờ vận chuyển
            case "waiting_shipping", "waiting shipping", "waitting", "waiting",
                 "cho van chuyen", "cho_van_chuyen", "cho giao hang" -> "waiting_shipping";
            // shipping / đang giao
            case "shipping", "dang giao", "dang giao hang", "dang van chuyen" -> "shipping";
            // shipped / đã giao cho ĐVVC
            case "shipped", "da giao cho dvvc", "da gui hang" -> "shipped";
            // delivered / đã giao
            case "delivered", "da giao", "da giao hang", "giao thanh cong" -> "delivered";
            // cancelled / đã hủy  (handle both "cancelled" and "canceled")
            case "cancelled", "canceled", "da huy", "da_huy", "huy" -> "cancelled";
            // returned / hoàn hàng
            case "returned", "hoan hang", "tra hang", "hoan tra" -> "returned";
            // received_money / đã thu tiền
            case "received_money", "received money", "payment_collected", "payment collected",
                 "da thu tien", "da_thu_tien" -> "received_money";
            default -> lower;
        };
    }

    private String truncateByField(String field, String value) {
        return switch (field) {
            case "p_utm_source", "utm_source", "p_utm_medium", "utm_medium", "p_utm_campaign", "utm_campaign",
                 "p_utm_content", "utm_content", "p_utm_term", "utm_term" -> truncate(value, 255);
            case "p_utm_id", "utm_id", "ad_id", "adId", "AD_ID", "click_id", "fbclid", "gclid", "ttclid" -> truncate(value, 500);
            default -> value;
        };
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        if (maxLen <= 0 || value.length() <= maxLen) return value;
        return value.substring(0, maxLen);
    }

    private void safeClearPersistenceContext() {
        try {
            if (entityManager != null && entityManager.isOpen()) {
                entityManager.clear();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();

        try {
            return Instant.parse(value)
                    .atZone(appZoneId)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }

        // If payload carries explicit offset (e.g. +07:00/Z), normalize to configured app timezone
        // so UI time matches business timezone instead of server JVM timezone.
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(appZoneId)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }

        String[] offsetFormats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd HH:mm:ssX"
        };
        for (String fmt : offsetFormats) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern(fmt))
                        .atZoneSameInstant(appZoneId)
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
            }
        }

        String[] localFormats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String fmt : localFormats) {
            try {
                LocalDateTime local = LocalDateTime.parse(value, DateTimeFormatter.ofPattern(fmt));
                return local.atZone(poscakeSourceZoneId)
                        .withZoneSameInstant(appZoneId)
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
            }
        }

        try {
            LocalDateTime local = LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
            return local.atZone(poscakeSourceZoneId)
                    .withZoneSameInstant(appZoneId)
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("Cannot parse datetime: {}", raw);
            return null;
        }
    }
}
