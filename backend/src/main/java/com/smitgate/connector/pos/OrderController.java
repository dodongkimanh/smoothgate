package com.smitgate.connector.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderRepository orderRepository;
    private final PosShopRepository posShopRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.orders.list.raw-snippet-chars:12000}")
    private int rawSnippetChars;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listOrders(
            HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Long tenantId = (Long) request.getAttribute("tenantId");
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        int safeRawSnippetChars = Math.max(0, Math.min(rawSnippetChars, 12000));
        PageRequest pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));

        String normalizedStatus = status != null ? status.trim() : null;
        boolean hasStatus = normalizedStatus != null
                && !normalizedStatus.isBlank()
                && !normalizedStatus.equalsIgnoreCase("ALL");

        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;

        Page<OrderRepository.OrderListProjection> orderPage;
        if (fromDateTime != null && toDateTime != null && hasStatus) {
            orderPage = orderRepository.findOrderSummariesByTenantIdAndDateRangeAndStatus(
                    tenantId,
                    fromDateTime,
                    toDateTime,
                    normalizedStatus,
                    safeRawSnippetChars,
                    pageable);
        } else if (fromDateTime != null && toDateTime != null) {
            orderPage = orderRepository.findOrderSummariesByTenantIdAndDateRange(
                    tenantId,
                    fromDateTime,
                    toDateTime,
                    safeRawSnippetChars,
                    pageable);
        } else if (hasStatus) {
            orderPage = orderRepository.findOrderSummariesByTenantIdAndStatusIgnoreCase(
                    tenantId,
                    normalizedStatus,
                    safeRawSnippetChars,
                    pageable);
        } else {
            orderPage = orderRepository.findOrderSummariesByTenantId(tenantId, safeRawSnippetChars, pageable);
        }

        List<OrderRepository.OrderListProjection> orders = orderPage.getContent();

        Map<Long, String> shopNames = posShopRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(PosShop::getId, PosShop::getName, (a, b) -> a));

        List<Map<String, Object>> items = orders.stream()
                .map(o -> toMap(o, shopNames))
                .toList();

        BigDecimal summaryRevenue;
        if (fromDateTime != null && toDateTime != null && hasStatus) {
            summaryRevenue = orderRepository.sumRevenueByTenantIdAndDateRangeAndStatusIgnoreCase(
                    tenantId,
                    fromDateTime,
                    toDateTime,
                    normalizedStatus);
        } else if (fromDateTime != null && toDateTime != null) {
            summaryRevenue = orderRepository.sumRevenueByTenantIdAndDateRange(tenantId, fromDateTime, toDateTime);
        } else if (hasStatus) {
            summaryRevenue = orderRepository.sumRevenueByTenantIdAndStatusIgnoreCase(tenantId, normalizedStatus);
        } else {
            summaryRevenue = orderRepository.sumRevenueByTenantId(tenantId);
        }
        if (summaryRevenue == null) {
            summaryRevenue = BigDecimal.ZERO;
        }

        BigDecimal summaryShippingFee;
        if (fromDateTime != null && toDateTime != null && hasStatus) {
            summaryShippingFee = orderRepository.sumShippingFeeByTenantIdAndDateRangeAndStatusIgnoreCase(
                    tenantId, fromDateTime, toDateTime, normalizedStatus);
        } else if (fromDateTime != null && toDateTime != null) {
            summaryShippingFee = orderRepository.sumShippingFeeByTenantIdAndDateRange(tenantId, fromDateTime, toDateTime);
        } else if (hasStatus) {
            summaryShippingFee = orderRepository.sumShippingFeeByTenantIdAndStatusIgnoreCase(tenantId, normalizedStatus);
        } else {
            summaryShippingFee = orderRepository.sumShippingFeeByTenantId(tenantId);
        }
        if (summaryShippingFee == null) {
            summaryShippingFee = BigDecimal.ZERO;
        }

        // Status counts across ALL orders matching the date range (not just current page)
        List<Object[]> statusRows;
        if (fromDateTime != null && toDateTime != null) {
            statusRows = orderRepository.countByStatusAndDateRange(tenantId, fromDateTime, toDateTime);
        } else {
            statusRows = orderRepository.countByStatus(tenantId);
        }
        Map<String, Long> statusCounts = new HashMap<>();
        long totalAll = 0;
        for (Object[] row : statusRows) {
            String s = row[0] != null ? row[0].toString() : "unknown";
            long cnt = ((Number) row[1]).longValue();
            statusCounts.put(s, cnt);
            totalAll += cnt;
        }
        statusCounts.put("ALL", totalAll);

        Map<String, Object> payload = new HashMap<>();
        payload.put("items", items);
        payload.put("page", safePage);
        payload.put("size", safeSize);
        payload.put("totalElements", orderPage.getTotalElements());
        payload.put("totalPages", orderPage.getTotalPages());
        payload.put("hasNext", orderPage.hasNext());
        payload.put("hasPrevious", orderPage.hasPrevious());
        payload.put("summaryRevenue", summaryRevenue);
        payload.put("summaryOrderProfit", summaryShippingFee);
        payload.put("statusCounts", statusCounts);

        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    @GetMapping("/by-external/{externalOrderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrderByExternalId(
            HttpServletRequest request,
            @PathVariable String externalOrderId,
            @RequestParam(defaultValue = "false") boolean contains,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = (Long) request.getAttribute("tenantId");
        Map<Long, String> shopNames = posShopRepository.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(PosShop::getId, PosShop::getName, (a, b) -> a));

        int safeSize = Math.max(1, Math.min(size, 100));
        List<Map<String, Object>> items;
        if (contains) {
            PageRequest pageable = PageRequest.of(0, safeSize, Sort.by(Sort.Direction.DESC, "id"));
            items = orderRepository.findByTenantIdAndExternalOrderIdLike(tenantId, externalOrderId, pageable)
                    .getContent()
                    .stream()
                    .map(o -> toMap(o, shopNames))
                    .toList();
        } else {
            items = orderRepository.findByTenantIdAndExternalOrderIdOrderByIdDesc(tenantId, externalOrderId)
                    .stream()
                    .limit(safeSize)
                    .map(o -> toMap(o, shopNames))
                    .toList();
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("externalOrderId", externalOrderId);
        payload.put("contains", contains);
        payload.put("count", items.size());
        payload.put("items", items);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

    /**
     * Debug endpoint: inspect the raw Pancake JSON stored for an order.
     * Works even when raw JSON is truncated.
     */
    @GetMapping("/debug-raw/{externalOrderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> debugRawOrder(
            HttpServletRequest request,
            @PathVariable String externalOrderId) {

        Long tenantId = (Long) request.getAttribute("tenantId");
        List<Order> orders = orderRepository.findByTenantIdAndExternalOrderIdOrderByIdDesc(tenantId, externalOrderId);
        if (orders.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("error", "Order not found: " + externalOrderId)));
        }

        Order order = orders.get(0);
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.getExternalOrderId());
        result.put("dbShippingFee", order.getShippingFee());
        result.put("dbRevenue", order.getRevenue());
        result.put("dbStatus", order.getStatus());
        result.put("rawJsonLength", order.getRawJson() != null ? order.getRawJson().length() : 0);

        String rawJson = order.getRawJson();
        if (rawJson != null && !rawJson.isBlank()) {
            // Even if JSON is truncated, scan the raw string for shipping-related fields
            Map<String, String> shippingRelatedFields = new HashMap<>();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"([^\"]*(?:ship|phi|vc|freight|deliver|giao|chuyen|van_chuyen)[^\"]*?)\"\\s*:\\s*([^,}\\]]{1,30})",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(rawJson);
            while (m.find()) {
                shippingRelatedFields.put(m.group(1), m.group(2).trim());
            }
            result.put("shippingRelatedFieldsInRawJson", shippingRelatedFields);

            // Also scan for ALL numeric fields > 0 that might be shipping fee
            Map<String, String> allNumericFields = new HashMap<>();
            java.util.regex.Pattern numP = java.util.regex.Pattern.compile(
                    "\"([^\"]+)\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
            java.util.regex.Matcher numM = numP.matcher(rawJson);
            while (numM.find()) {
                String val = numM.group(2);
                try {
                    double d = Double.parseDouble(val);
                    if (d > 0) {
                        allNumericFields.put(numM.group(1), val);
                    }
                } catch (Exception ignored) {}
            }
            result.put("allNonZeroNumericFields", allNumericFields);

            // Try full JSON parse (may fail if truncated)
            try {
                JsonNode node = objectMapper.readTree(rawJson);
                BigDecimal extracted = firstNonZeroDecimal(node,
                        "partner_fee", "ship_fee", "bill_ship_fee", "shipping_fee", "shippingFee",
                        "phi_vc", "phí_vc", "fee_ship", "phi_ship", "tien_ship",
                        "delivery_fee", "shipping_cost");
                result.put("extractedShippingFeeFromKnownFields", extracted);

                BigDecimal deepScan = deepScanForShippingFee(node);
                result.put("extractedShippingFeeFromDeepScan", deepScan);
            } catch (Exception e) {
                result.put("rawJsonParseError", "JSON truncated at " + rawJson.length() + " chars (cannot parse)");
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Repair endpoint: batch-update the shipping_fee DB column for orders where it is 0
     * by re-parsing rawJson to extract partner_fee.
     * Processes in small batches to avoid OOM on low-memory Render instances.
     */
    @PostMapping("/repair-shipping-fee")
    public ResponseEntity<ApiResponse<Map<String, Object>>> repairShippingFee(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        int batchSize = 20;
        int totalScanned = 0;
        int totalUpdated = 0;

        while (true) {
            Page<Long> idPage = orderRepository.findIdsWithZeroShippingFee(
                    tenantId, PageRequest.of(0, batchSize));
            List<Long> ids = idPage.getContent();
            if (ids.isEmpty()) break;

            totalScanned += ids.size();
            for (Long id : ids) {
                orderRepository.findById(id).ifPresent(order -> {
                    if (order.getRawJson() != null && !order.getRawJson().isBlank()) {
                        ParsedOrderRaw parsed = parseOrderRawFields(order.getRawJson());
                        if (parsed.shippingFee.compareTo(BigDecimal.ZERO) > 0) {
                            order.setShippingFee(parsed.shippingFee);
                        } else {
                            order.setShippingFee(BigDecimal.valueOf(-1));
                        }
                        orderRepository.save(order);
                    }
                });
            }
            totalUpdated += ids.size();

            if (!idPage.hasNext()) break;
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "totalScanned", totalScanned,
                "totalUpdated", totalUpdated
        )));
    }

    private Map<String, Object> toMap(OrderRepository.OrderListProjection o, Map<Long, String> shopNames) {
        String adId = o.getClickId() != null ? o.getClickId() : "";
        ParsedOrderRaw parsedRaw = parseOrderRawFields(o.getRawJsonSnippet());
        String customerName = (o.getCustomerName() != null && !o.getCustomerName().isBlank())
                ? o.getCustomerName()
                : parsedRaw.customerName;
        String customerPhone = (o.getCustomerPhone() != null && !o.getCustomerPhone().isBlank())
                ? o.getCustomerPhone()
                : parsedRaw.customerPhone;
        BigDecimal shippingFee = o.getShippingFee() != null && o.getShippingFee().compareTo(BigDecimal.ZERO) != 0
                ? o.getShippingFee() : parsedRaw.shippingFee;
        boolean isValidOrder = isValidStatus(o.getStatus());
        BigDecimal surcharge = o.getRevenue() != null ? o.getRevenue() : BigDecimal.ZERO;
        BigDecimal orderProfit = shippingFee;

        return Map.ofEntries(
                Map.entry("id", o.getId()),
                Map.entry("orderId", o.getExternalOrderId()),
                Map.entry("shopName", shopNames.getOrDefault(o.getPosShopId(), "Unknown")),
                Map.entry("customerName", customerName),
                Map.entry("status", o.getStatus() != null ? o.getStatus() : "unknown"),
                Map.entry("revenue", o.getRevenue()),
                Map.entry("customerPhone", customerPhone),
                Map.entry("utmSource", o.getUtmSource() != null ? o.getUtmSource() : ""),
                Map.entry("utmMedium", o.getUtmMedium() != null ? o.getUtmMedium() : ""),
                Map.entry("utmCampaign", o.getUtmCampaign() != null ? o.getUtmCampaign() : ""),
                Map.entry("utmContent", o.getUtmContent() != null ? o.getUtmContent() : ""),
                Map.entry("utmTerm", o.getUtmTerm() != null ? o.getUtmTerm() : ""),
                Map.entry("adId", adId),
                Map.entry("shippingFee", shippingFee),
                Map.entry("orderProfit", orderProfit),
                Map.entry("surcharge", surcharge),
                Map.entry("isValidOrder", isValidOrder),
                Map.entry("matchedCampaign", ""),
                Map.entry("poscakeInsertedAtRaw", parsedRaw.insertedAtRaw),
                Map.entry("poscakeUpdatedAtRaw", parsedRaw.updatedAtRaw),
                Map.entry("orderTime", o.getCreatedAtExternal() != null ? o.getCreatedAtExternal().toString() : ""),
                Map.entry("createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : "")
        );
    }

    private Map<String, Object> toMap(Order o, Map<Long, String> shopNames) {
        String adId = o.getClickId() != null ? o.getClickId() : "";
        ParsedOrderRaw parsedRaw = parseOrderRawFields(o.getRawJson());
        String customerName = (o.getCustomerName() != null && !o.getCustomerName().isBlank())
                ? o.getCustomerName()
                : parsedRaw.customerName;
        String customerPhone = (o.getCustomerPhone() != null && !o.getCustomerPhone().isBlank())
                ? o.getCustomerPhone()
                : parsedRaw.customerPhone;
        BigDecimal shippingFee = o.getShippingFee() != null && o.getShippingFee().compareTo(BigDecimal.ZERO) != 0
                ? o.getShippingFee() : parsedRaw.shippingFee;
        boolean isValidOrder = isValidStatus(o.getStatus());
        BigDecimal surcharge = o.getRevenue() != null ? o.getRevenue() : BigDecimal.ZERO;
        BigDecimal orderProfit = shippingFee;

        return Map.ofEntries(
                Map.entry("id", o.getId()),
                Map.entry("orderId", o.getExternalOrderId()),
                Map.entry("shopName", shopNames.getOrDefault(o.getPosShopId(), "Unknown")),
                Map.entry("customerName", customerName),
                Map.entry("status", o.getStatus() != null ? o.getStatus() : "unknown"),
                Map.entry("revenue", o.getRevenue()),
                Map.entry("customerPhone", customerPhone),
                Map.entry("utmSource", o.getUtmSource() != null ? o.getUtmSource() : ""),
                Map.entry("utmMedium", o.getUtmMedium() != null ? o.getUtmMedium() : ""),
                Map.entry("utmCampaign", o.getUtmCampaign() != null ? o.getUtmCampaign() : ""),
                Map.entry("utmContent", o.getUtmContent() != null ? o.getUtmContent() : ""),
                Map.entry("utmTerm", o.getUtmTerm() != null ? o.getUtmTerm() : ""),
                Map.entry("adId", adId),
                Map.entry("shippingFee", shippingFee),
                Map.entry("orderProfit", orderProfit),
                Map.entry("surcharge", surcharge),
                Map.entry("isValidOrder", isValidOrder),
                Map.entry("matchedCampaign", ""),
                Map.entry("poscakeInsertedAtRaw", parsedRaw.insertedAtRaw),
                Map.entry("poscakeUpdatedAtRaw", parsedRaw.updatedAtRaw),
                Map.entry("orderTime", o.getCreatedAtExternal() != null ? o.getCreatedAtExternal().toString() : ""),
                Map.entry("createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : "")
        );
    }

    private ParsedOrderRaw parseOrderRawFields(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new ParsedOrderRaw("", "", BigDecimal.ZERO, "", "");
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            String customerName = extractCustomerName(node);
            String customerPhone = extractCustomerPhone(node);
            BigDecimal shippingFee = firstNonZeroDecimal(node,
                    "partner_fee", "ship_fee", "bill_ship_fee", "shipping_fee", "shippingFee",
                    "phi_vc", "phí_vc", "fee_ship", "phi_ship", "tien_ship",
                    "delivery_fee", "shipping_cost");
            // Deep scan fallback: walk ALL fields for shipping-related keywords
            if (shippingFee.compareTo(BigDecimal.ZERO) == 0) {
                shippingFee = deepScanForShippingFee(node);
            }
            String insertedAtRaw = textValue(node, "inserted_at", "created_at", "createdAt");
            String updatedAtRaw = textValue(node, "updated_at", "updatedAt");
            return new ParsedOrderRaw(customerName, customerPhone, shippingFee, insertedAtRaw, updatedAtRaw);
        } catch (Exception e) {
            return new ParsedOrderRaw("", "", BigDecimal.ZERO, "", "");
        }
    }

    /**
     * Deep-scan ALL fields (root + 1 level nested) for anything that looks like a shipping fee.
     */
    private BigDecimal deepScanForShippingFee(JsonNode node) {
        java.util.regex.Pattern shipPattern = java.util.regex.Pattern.compile(
                "(?i)(ship|phi.*v|v.*c.*fee|van.?chuyen|freight|deliver|giao.*hang|phi_giao|tien.*ship)");
        java.util.regex.Pattern excludePattern = java.util.regex.Pattern.compile(
                "(?i)(total_price|phu_thu|prepaid|revenue|surcharge|discount|tax|status|phone|name|address|id$|_at$|_id$)");

        BigDecimal found = scanNodeForShipFee(node, shipPattern, excludePattern);
        if (found.compareTo(BigDecimal.ZERO) != 0) return found;

        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            JsonNode child = entry.getValue();
            if (child.isObject()) {
                found = scanNodeForShipFee(child, shipPattern, excludePattern);
                if (found.compareTo(BigDecimal.ZERO) != 0) return found;
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
                if (parsed.compareTo(BigDecimal.ZERO) != 0) return parsed;
            }
        }
        return BigDecimal.ZERO;
    }

    private boolean isValidStatus(String status) {
        return OrderStatusClassifier.isValidOrderStatus(status);
    }

    private String extractCustomerName(JsonNode node) {
        JsonNode shipping = node.path("shipping_address");
        if (!shipping.isMissingNode()) {
            String fullName = textValue(shipping, "full_name", "fullName", "name");
            if (!fullName.isBlank()) {
                return fullName;
            }
        }

        JsonNode customer = node.path("customer");
        if (!customer.isMissingNode()) {
            String customerName = textValue(customer, "full_name", "fullName", "name");
            if (!customerName.isBlank()) {
                return customerName;
            }
        }

        JsonNode buyer = node.path("buyer");
        if (!buyer.isMissingNode()) {
            String buyerName = textValue(buyer, "full_name", "fullName", "name", "buyer_name");
            if (!buyerName.isBlank()) {
                return buyerName;
            }
        }

        JsonNode contact = node.path("contact");
        if (!contact.isMissingNode()) {
            String contactName = textValue(contact, "full_name", "fullName", "name");
            if (!contactName.isBlank()) {
                return contactName;
            }
        }

        return textValue(node, "customer_name", "customerName", "name", "buyer_name", "receiver_name");
    }

    private String extractCustomerPhone(JsonNode node) {
        JsonNode shipping = node.path("shipping_address");
        if (!shipping.isMissingNode()) {
            String phone = textValue(shipping, "phone_number", "phone", "mobile", "tel");
            if (!phone.isBlank()) {
                return phone;
            }
        }

        JsonNode customer = node.path("customer");
        if (!customer.isMissingNode()) {
            String phone = textValue(customer, "phone", "phone_number", "mobile", "tel");
            if (!phone.isBlank()) {
                return phone;
            }
        }

        JsonNode buyer = node.path("buyer");
        if (!buyer.isMissingNode()) {
            String phone = textValue(buyer, "phone", "phone_number", "mobile", "tel");
            if (!phone.isBlank()) {
                return phone;
            }
        }

        return textValue(node, "customer_phone", "phone", "phone_number", "mobile", "tel");
    }

    private record ParsedOrderRaw(String customerName, String customerPhone, BigDecimal shippingFee,
                                  String insertedAtRaw, String updatedAtRaw) {
    }

    private String textValue(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field) && !node.get(field).isNull()) {
                String val = node.get(field).asText("").trim();
                if (!val.isBlank() && !"null".equalsIgnoreCase(val)) {
                    return val;
                }
            }
        }
        return "";
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
            String raw = node.get(field).asText("0");
            return parseCurrency(raw);
        } catch (Exception e) {
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
                    normalized = normalized.replace(".", "").replace(',', '.');
                }
            }
        }

        normalized = normalized.replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank() || normalized.equals("-") || normalized.equals(".")) {
            return BigDecimal.ZERO;
        }

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
}
