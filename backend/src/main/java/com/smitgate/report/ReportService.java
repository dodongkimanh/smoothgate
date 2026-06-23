package com.smitgate.report;

import com.smitgate.attribution.OrderAttribution;
import com.smitgate.attribution.OrderAttributionRepository;
import com.smitgate.config.CacheInvalidationService;
import com.smitgate.config.CacheNames;
import com.smitgate.connector.ads.AdsMetricsDailyRepository;
import com.smitgate.connector.ads.Campaign;
import com.smitgate.connector.ads.CampaignRepository;
import com.smitgate.connector.pos.OrderRepository;
import com.smitgate.connector.pos.OrderStatusClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    // Use CacheNames constants — these must match what RedisCacheConfig configures.
    // Using hardcoded "report:overview" (with colon) was wrong: different from CacheNames.REPORT_OVERVIEW = "reportOverview".
    private static final String CACHE_REPORT_OVERVIEW = CacheNames.REPORT_OVERVIEW;
    private static final String CACHE_REPORT_CAMPAIGNS = CacheNames.REPORT_CAMPAIGNS;
    private static final String CACHE_REPORT_CAMPAIGN_DAILY = CacheNames.REPORT_CAMPAIGN_DAILY;
    private static final String CACHE_REPORT_CAMPAIGN_FUNNEL = CacheNames.REPORT_CAMPAIGN_FUNNEL;
    private static final String CACHE_REPORT_ATTRIBUTIONS = CacheNames.REPORT_ATTRIBUTIONS;
    private static final String CACHE_REPORT_ATTRIBUTION_QUALITY = CacheNames.REPORT_ATTRIBUTION_QUALITY;
    private static final String CACHE_REPORT_ACCOUNT_SPEND = CacheNames.REPORT_ACCOUNT_SPEND;

    /** Cooldown between on-demand ads refreshes per tenant (ms). */
    private static final long ADS_REFRESH_COOLDOWN_MS = 300_000;

    private static final List<String> VALID_ORDER_STATUSES = OrderStatusClassifier.validStatusesForAggregation();

    private static final List<String> LEAD_ORDER_STATUSES = OrderStatusClassifier.leadStatusesForAggregation();

    private final OrderRepository orderRepository;
    private final AdsMetricsDailyRepository metricsRepository;
    private final CampaignRepository campaignRepository;
    private final OrderAttributionRepository attributionRepository;
    private final CacheInvalidationService cacheInvalidationService;
    private final com.smitgate.connector.ads.AdAccountRepository adAccountRepository;

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_OVERVIEW,
            key = "#tenantId + ':' + #from.toString() + ':' + #to.toString()")
    public OverviewResponse getOverview(Long tenantId, LocalDate from, LocalDate to) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        BigDecimal totalSpend = metricsRepository.sumSpendByTenantIdAndDateRange(tenantId, from, to);
        long totalOrders = orderRepository.countValidOrdersByTenantIdAndDateRange(
                tenantId, fromDt, toDt, VALID_ORDER_STATUSES);

        BigDecimal totalRevenue = orderRepository.sumValidRevenueByTenantIdAndDateRange(
                tenantId, fromDt, toDt, VALID_ORDER_STATUSES);

        long newContacts = orderRepository.countDistinctPhonesByTenantIdAndDateRange(
                tenantId, fromDt, toDt, LEAD_ORDER_STATUSES);

        long totalClicks = metricsRepository.sumClicksByTenantIdAndDateRange(tenantId, from, to);
        long totalImpressions = metricsRepository.sumImpressionsByTenantIdAndDateRange(tenantId, from, to);
        long attributedOrders = attributionRepository.countAttributedByTenantIdAndDateRange(tenantId, fromDt, toDt);
        long messageContacts = metricsRepository.sumMessageContactsByTenantIdAndDateRange(tenantId, from, to);

        BigDecimal totalOrderProfit = orderRepository.sumValidShippingFeeByTenantIdAndDateRange(
                tenantId, fromDt, toDt, VALID_ORDER_STATUSES);

        BigDecimal roas = totalSpend.compareTo(BigDecimal.ZERO) > 0
                ? totalRevenue.divide(totalSpend, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal cpo = totalOrders > 0
                ? totalSpend.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new OverviewResponse(
                totalSpend, totalRevenue, totalOrders, totalOrders, newContacts, roas, cpo,
                totalClicks, totalImpressions, attributedOrders, messageContacts, totalOrderProfit
        );
    }

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_CAMPAIGNS,
            key = "#tenantId + ':' + #from.toString() + ':' + #to.toString() + ':' + (#platform == null ? 'ALL' : #platform)")
    public List<CampaignPerfResponse> getCampaignPerformance(Long tenantId, LocalDate from, LocalDate to, String platform) {
        List<Campaign> campaigns;
        if (platform != null && !platform.isBlank()) {
            campaigns = campaignRepository.findByTenantIdAndPlatform(
                    tenantId, Campaign.Platform.valueOf(platform));
        } else {
            campaigns = campaignRepository.findByTenantId(tenantId);
        }

        List<AdsMetricsDailyRepository.MetricsSummaryProjection> metricsData = metricsRepository
                .findMetricsSummaryByTenantIdAndDateBetween(tenantId, from, to);

        Map<Long, Map<LocalDate, BigDecimal>> spendByCampaignDate = new HashMap<>();
        Map<Long, BigDecimal> totalSpendByCampaign = new HashMap<>();
        Map<Long, Long> messageContactsByCampaign = new HashMap<>();
        for (AdsMetricsDailyRepository.MetricsSummaryProjection m : metricsData) {
            if (m.getCampaignId() == null) {
                continue;
            }
            spendByCampaignDate
                    .computeIfAbsent(m.getCampaignId(), k -> new HashMap<>())
                    .merge(m.getDate(), nonNull(m.getSpend()), BigDecimal::add);
            totalSpendByCampaign.merge(m.getCampaignId(), nonNull(m.getSpend()), BigDecimal::add);
            messageContactsByCampaign.merge(m.getCampaignId(),
                    m.getMessageContacts() != null ? m.getMessageContacts() : 0L, Long::sum);
        }

        List<Long> campaignIds = campaigns.stream().map(Campaign::getId).toList();

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        Map<Long, Integer> validOrdersByCampaign = new HashMap<>();
        Map<Long, BigDecimal> revenueByCampaign = new HashMap<>();
        Map<Long, BigDecimal> orderProfitByCampaign = new HashMap<>();
        Map<Long, Integer> contactsByCampaign = new HashMap<>();
        Map<Long, Map<LocalDate, Integer>> dailyOrdersByCampaign = new HashMap<>();
        Map<Long, Map<LocalDate, BigDecimal>> dailyRevenueByCampaign = new HashMap<>();

        if (!campaignIds.isEmpty()) {
            List<Object[]> validAggRows = attributionRepository.aggregateValidOrdersRevenueByCampaignAndDate(
                    tenantId, campaignIds, fromDt, toDt, VALID_ORDER_STATUSES);

            for (Object[] row : validAggRows) {
                Long campaignId = toLong(row[0]);
                LocalDate orderDate = toLocalDate(row[1]);
                int orders = toInt(row[2]);
                BigDecimal revenue = toBigDecimal(row[3]);
                BigDecimal shippingFee = toBigDecimal(row[4]);

                if (campaignId == null || orderDate == null) {
                    continue;
                }

                validOrdersByCampaign.merge(campaignId, orders, Integer::sum);
                revenueByCampaign.merge(campaignId, revenue, BigDecimal::add);
                orderProfitByCampaign.merge(campaignId, shippingFee, BigDecimal::add);

                dailyOrdersByCampaign
                        .computeIfAbsent(campaignId, k -> new HashMap<>())
                        .merge(orderDate, orders, Integer::sum);
                dailyRevenueByCampaign
                        .computeIfAbsent(campaignId, k -> new HashMap<>())
                        .merge(orderDate, revenue, BigDecimal::add);
            }

            List<Object[]> contactRows = attributionRepository.countDistinctPhonesByCampaignInDateRange(
                    tenantId, campaignIds, fromDt, toDt, LEAD_ORDER_STATUSES);
            for (Object[] row : contactRows) {
                Long campaignId = toLong(row[0]);
                int contactCount = toInt(row[1]);
                if (campaignId != null) {
                    contactsByCampaign.put(campaignId, contactCount);
                }
            }
        }

        List<CampaignPerfResponse> results = new ArrayList<>();
        for (Campaign campaign : campaigns) {
            Long campaignId = campaign.getId();
            BigDecimal totalSpend = totalSpendByCampaign.getOrDefault(campaignId, BigDecimal.ZERO);
            int validOrders = validOrdersByCampaign.getOrDefault(campaignId, 0);
            BigDecimal totalRevenue = revenueByCampaign.getOrDefault(campaignId, BigDecimal.ZERO);
            BigDecimal totalOrderProfit = orderProfitByCampaign.getOrDefault(campaignId, BigDecimal.ZERO);
            int newContacts = contactsByCampaign.getOrDefault(campaignId, 0);
            long msgContacts = messageContactsByCampaign.getOrDefault(campaignId, 0L);

            BigDecimal roas = totalSpend.compareTo(BigDecimal.ZERO) > 0
                    ? totalRevenue.divide(totalSpend, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal cpo = validOrders > 0
                    ? totalSpend.divide(BigDecimal.valueOf(validOrders), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Map<LocalDate, BigDecimal> spendByDate = spendByCampaignDate.getOrDefault(campaignId, Map.of());
            Map<LocalDate, Integer> ordersByDate = dailyOrdersByCampaign.getOrDefault(campaignId, Map.of());
            Map<LocalDate, BigDecimal> revenueByDate = dailyRevenueByCampaign.getOrDefault(campaignId, Map.of());

            List<CampaignPerfResponse.DailyData> dailyData = new ArrayList<>();
                        Set<LocalDate> observedDates = new TreeSet<>();
                        observedDates.addAll(spendByDate.keySet());
                        observedDates.addAll(ordersByDate.keySet());
                        observedDates.addAll(revenueByDate.keySet());

                        for (LocalDate cursor : observedDates) {
                BigDecimal spend = spendByDate.getOrDefault(cursor, BigDecimal.ZERO);
                int orders = ordersByDate.getOrDefault(cursor, 0);
                BigDecimal revenue = revenueByDate.getOrDefault(cursor, BigDecimal.ZERO);
                BigDecimal dailyRoas = spend.compareTo(BigDecimal.ZERO) > 0
                        ? revenue.divide(spend, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                dailyData.add(new CampaignPerfResponse.DailyData(
                        cursor.toString(), spend, orders, revenue, dailyRoas
                ));
            }

            results.add(new CampaignPerfResponse(
                    campaign.getId(), campaign.getExternalCampaignId(), campaign.getName(), campaign.getPlatform().name(),
                    campaign.getStatus(), totalSpend, validOrders, validOrders, newContacts, msgContacts, totalRevenue,
                    totalOrderProfit, roas, cpo, dailyData));
        }

        return results;
    }

        private Long toLong(Object value) {
                if (value == null) {
                        return null;
                }
                if (value instanceof Long l) {
                        return l;
                }
                if (value instanceof Number n) {
                        return n.longValue();
                }
                try {
                        return Long.parseLong(value.toString());
                } catch (Exception e) {
                        return null;
                }
        }

        private int toInt(Object value) {
                if (value == null) {
                        return 0;
                }
                if (value instanceof Number n) {
                        return n.intValue();
                }
                try {
                        return Integer.parseInt(value.toString());
                } catch (Exception e) {
                        return 0;
                }
        }

        private LocalDate toLocalDate(Object value) {
                if (value == null) {
                        return null;
                }
                if (value instanceof LocalDate d) {
                        return d;
                }
                if (value instanceof Date d) {
                        return d.toLocalDate();
                }
                if (value instanceof java.sql.Timestamp ts) {
                        return ts.toLocalDateTime().toLocalDate();
                }
                if (value instanceof LocalDateTime dt) {
                        return dt.toLocalDate();
                }
                try {
                        return LocalDate.parse(value.toString());
                } catch (Exception e) {
                        return null;
                }
        }

        private BigDecimal toBigDecimal(Object value) {
                if (value == null) {
                        return BigDecimal.ZERO;
                }
                if (value instanceof BigDecimal bd) {
                        return bd;
                }
                if (value instanceof Number n) {
                        return BigDecimal.valueOf(n.doubleValue());
                }
                try {
                        return new BigDecimal(value.toString());
                } catch (Exception e) {
                        return BigDecimal.ZERO;
                }
        }

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
            @Cacheable(cacheNames = CACHE_REPORT_CAMPAIGN_DAILY,
            key = "#tenantId + ':' + #campaignId + ':' + #from.toString() + ':' + #to.toString()")
    public List<CampaignPerfResponse.DailyData> getCampaignDaily(
            Long tenantId, Long campaignId, LocalDate from, LocalDate to) {
        return getCampaignPerformance(tenantId, from, to, null).stream()
                .filter(c -> campaignId.equals(c.getCampaignId()))
                .findFirst()
                .map(CampaignPerfResponse::getDaily)
                .orElse(List.of());
    }

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_CAMPAIGN_FUNNEL,
            key = "#tenantId + ':' + #campaignId + ':' + #from.toString() + ':' + #to.toString() + ':' + #limit")
    public CampaignFunnelResponse getCampaignFunnel(
            Long tenantId,
            Long campaignId,
            LocalDate from,
            LocalDate to,
            int limit) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .filter(c -> Objects.equals(c.getTenantId(), tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found for this tenant"));

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        long attributedOrders = attributionRepository.countAttributedOrdersByCampaignInDateRange(
                tenantId, campaignId, fromDt, toDt);

        if (attributedOrders == 0) {
            return new CampaignFunnelResponse(
                    campaignId,
                    campaign.getName(),
                    campaign.getPlatform().name(),
                    campaign.getStatus(),
                    0,
                    0,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    List.of()
            );
        }

        long validOrders = attributionRepository.countValidOrdersByCampaignInDateRange(
                tenantId, campaignId, fromDt, toDt, VALID_ORDER_STATUSES);
        BigDecimal totalRevenue = attributionRepository.sumValidRevenueByCampaignInDateRange(
                tenantId, campaignId, fromDt, toDt, VALID_ORDER_STATUSES);
        long uniquePhonesCount = attributionRepository.countDistinctLeadPhonesByCampaignInDateRange(
                tenantId, campaignId, fromDt, toDt, LEAD_ORDER_STATUSES);

        int safeRecentOrderLimit = Math.max(0, Math.min(limit, 200));
        List<CampaignFunnelResponse.MatchedOrder> recentOrders = new ArrayList<>();

        if (safeRecentOrderLimit > 0) {
            List<OrderAttribution> recentAttrs = attributionRepository
                    .findRecentByTenantIdAndCampaignIdAndOrderDateRange(
                            tenantId,
                            campaignId,
                            fromDt,
                            toDt,
                            PageRequest.of(0, safeRecentOrderLimit, Sort.by(Sort.Direction.DESC, "matchedAt")))
                    .getContent();

            List<Long> recentOrderIds = recentAttrs.stream()
                    .map(OrderAttribution::getOrderId)
                    .distinct()
                    .toList();

            Map<Long, OrderRepository.OrderLightProjection> orderById = recentOrderIds.isEmpty()
                    ? Map.of()
                    : orderRepository.findLightByIds(recentOrderIds).stream()
                            .collect(Collectors.toMap(OrderRepository.OrderLightProjection::getId, o -> o));

            for (OrderAttribution attr : recentAttrs) {
                OrderRepository.OrderLightProjection order = orderById.get(attr.getOrderId());
                if (order == null || order.getCreatedAtExternal() == null) {
                    continue;
                }
                boolean isValid = isValidOrderStatus(order.getStatus());
                recentOrders.add(new CampaignFunnelResponse.MatchedOrder(
                        order.getId(),
                        order.getExternalOrderId(),
                        order.getCreatedAtExternal().toString(),
                        order.getCustomerPhone() != null ? order.getCustomerPhone() : "",
                        order.getStatus() != null ? order.getStatus() : "",
                        isValid,
                        nonNull(order.getRevenue()),
                        order.getClickId() != null ? order.getClickId() : "",
                        attr.getMatchType() != null ? attr.getMatchType().name() : "UNKNOWN",
                        attr.getMatchedAt() != null ? attr.getMatchedAt().toString() : ""
                ));
            }
        }

        BigDecimal avgRevenuePerOrder = validOrders > 0
                ? totalRevenue.divide(BigDecimal.valueOf(validOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal conversionRate = attributedOrders > 0
                ? BigDecimal.valueOf(validOrders)
                .divide(BigDecimal.valueOf(attributedOrders), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return new CampaignFunnelResponse(
                campaignId,
                campaign.getName(),
                campaign.getPlatform().name(),
                campaign.getStatus(),
                attributedOrders,
                validOrders,
                (int) uniquePhonesCount,
                totalRevenue,
                avgRevenuePerOrder,
                conversionRate,
                recentOrders
        );
    }

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAttributionDetails(Long tenantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<OrderAttribution> attrs = attributionRepository
                .findByTenantIdOrderByMatchedAtDesc(
                        tenantId,
                        PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "matchedAt")))
                .getContent();
        if (attrs.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = attrs.stream().map(OrderAttribution::getOrderId).toList();
        Map<Long, OrderRepository.OrderLightProjection> orderById = orderRepository.findLightByIds(orderIds).stream()
                .collect(Collectors.toMap(OrderRepository.OrderLightProjection::getId, o -> o));

        List<Long> campaignIds = attrs.stream()
                .map(OrderAttribution::getCampaignId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Campaign> campaignById = campaignIds.isEmpty()
                ? new HashMap<>()
                : campaignRepository.findAllById(campaignIds).stream()
                    .collect(Collectors.toMap(Campaign::getId, c -> c));

        return attrs.stream()
                .sorted(Comparator.comparing(OrderAttribution::getMatchedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(safeLimit)
                .map(attr -> {
                    OrderRepository.OrderLightProjection order = orderById.get(attr.getOrderId());
                    Campaign campaign = attr.getCampaignId() != null ? campaignById.get(attr.getCampaignId()) : null;
                                        Map<String, Object> item = new HashMap<>();
                                        item.put("orderId", attr.getOrderId());
                                        item.put("externalOrderId", order != null && order.getExternalOrderId() != null ? order.getExternalOrderId() : "");
                                        item.put("orderRevenue", order != null && order.getRevenue() != null ? order.getRevenue() : java.math.BigDecimal.ZERO);
                                        item.put("orderCreatedAt", order != null && order.getCreatedAtExternal() != null ? order.getCreatedAtExternal().toString() : "");
                                        item.put("utmSource", order != null && order.getUtmSource() != null ? order.getUtmSource() : "");
                                        item.put("utmCampaign", order != null && order.getUtmCampaign() != null ? order.getUtmCampaign() : "");
                                        item.put("adId", order != null && order.getClickId() != null ? order.getClickId() : "");
                                        item.put("isValidOrder", order != null && isValidOrderStatus(order.getStatus()));
                                        item.put("attributionPlatform", attr.getPlatform().name());
                                        item.put("matchType", attr.getMatchType().name());
                                        item.put("campaignId", attr.getCampaignId() != null ? attr.getCampaignId() : 0L);
                                        item.put("campaignName", campaign != null && campaign.getName() != null ? campaign.getName() : "");
                                        item.put("campaignExternalId", campaign != null && campaign.getExternalCampaignId() != null ? campaign.getExternalCampaignId() : "");
                                        item.put("matchedAt", attr.getMatchedAt() != null ? attr.getMatchedAt().toString() : "");
                                        return item;
                })
                .toList();
    }

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_ATTRIBUTION_QUALITY,
            key = "#tenantId + ':' + #from.toString() + ':' + #to.toString() + ':' + #limit")
    public Map<String, Object> getAttributionQuality(Long tenantId, LocalDate from, LocalDate to, int limit) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);

        List<OrderRepository.OrderLightProjection> orders = orderRepository.findLightByTenantIdAndDateRange(tenantId, fromDt, toDt);
        if (orders.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("from", from.toString());
            empty.put("to", to.toString());
            empty.put("totalOrders", 0);
            empty.put("ordersWithTrackingKey", 0);
            empty.put("ordersWithoutTrackingKey", 0);
            empty.put("trackingCoverageRate", BigDecimal.ZERO);
            empty.put("attributedOrders", 0);
            empty.put("unknownOrders", 0);
            empty.put("unknownOrdersWithoutTracking", 0);
            empty.put("samplesMissingTracking", List.of());
            empty.put("advice", "Không có đơn hàng trong khoảng thời gian đã chọn");
            return empty;
        }

        Map<Long, OrderRepository.OrderLightProjection> orderById = orders.stream()
                .collect(Collectors.toMap(OrderRepository.OrderLightProjection::getId, o -> o));
        List<Long> orderIds = orders.stream().map(OrderRepository.OrderLightProjection::getId).toList();
        List<OrderAttribution> attrs = attributionRepository.findByTenantIdAndOrderIdIn(tenantId, orderIds);

        int totalOrders = orders.size();
        int withTracking = (int) orders.stream().filter(this::hasTrackingKeyLight).count();
        int withoutTracking = totalOrders - withTracking;

        Set<Long> attributedOrderIds = attrs.stream()
                .filter(a -> a.getCampaignId() != null && a.getMatchType() != OrderAttribution.MatchType.UNKNOWN)
                .map(OrderAttribution::getOrderId)
                .collect(Collectors.toSet());

        Set<Long> unknownOrderIds = attrs.stream()
                .filter(a -> a.getCampaignId() == null || a.getMatchType() == OrderAttribution.MatchType.UNKNOWN)
                .map(OrderAttribution::getOrderId)
                .collect(Collectors.toSet());

        List<Map<String, Object>> missingSamples = orders.stream()
                .filter(o -> !hasTrackingKeyLight(o))
                .sorted(Comparator.comparing(OrderRepository.OrderLightProjection::getCreatedAtExternal, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .map(o -> {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("orderId", o.getId());
                    sample.put("externalOrderId", o.getExternalOrderId() != null ? o.getExternalOrderId() : "");
                    sample.put("orderDate", o.getCreatedAtExternal() != null ? o.getCreatedAtExternal().toString() : "");
                    sample.put("status", o.getStatus() != null ? o.getStatus() : "");
                    sample.put("phone", o.getCustomerPhone() != null ? o.getCustomerPhone() : "");
                    sample.put("revenue", nonNull(o.getRevenue()));
                    sample.put("utmSource", o.getUtmSource() != null ? o.getUtmSource() : "");
                    sample.put("utmCampaign", o.getUtmCampaign() != null ? o.getUtmCampaign() : "");
                    sample.put("clickId", o.getClickId() != null ? o.getClickId() : "");
                    sample.put("attributionStatus", unknownOrderIds.contains(o.getId()) ? "UNKNOWN" : "NOT_ATTRIBUTED");
                    return sample;
                })
                .toList();

        long unknownWithoutTracking = unknownOrderIds.stream()
                .map(orderById::get)
                .filter(Objects::nonNull)
                .filter(o -> !hasTrackingKeyLight(o))
                .count();

        BigDecimal coverageRate = BigDecimal.valueOf(withTracking)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("totalOrders", totalOrders);
        result.put("ordersWithTrackingKey", withTracking);
        result.put("ordersWithoutTrackingKey", withoutTracking);
        result.put("trackingCoverageRate", coverageRate);
        result.put("attributedOrders", attributedOrderIds.size());
        result.put("unknownOrders", unknownOrderIds.size());
        result.put("unknownOrdersWithoutTracking", unknownWithoutTracking);
        result.put("samplesMissingTracking", missingSamples);
        result.put("advice",
                "Đơn thiếu AD_ID/p_utm_id sẽ không thể attribution deterministic. Cần bắt buộc đẩy tracking key từ Poscake.");

        return result;
    }

    @Retryable(retryFor = CannotCreateTransactionException.class, maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_REPORT_ACCOUNT_SPEND,
            key = "#tenantId + ':' + #from.toString() + ':' + #to.toString()")
    public List<AccountSpendResponse> getAccountSpend(Long tenantId, LocalDate from, LocalDate to) {
        List<AdsMetricsDailyRepository.AccountSpendProjection> spendData =
                metricsRepository.sumSpendGroupedByAdAccount(tenantId, from, to);

        Map<Long, com.smitgate.connector.ads.AdAccount> accountMap =
                adAccountRepository.findByTenantId(tenantId).stream()
                        .collect(Collectors.toMap(com.smitgate.connector.ads.AdAccount::getId, a -> a));

        return spendData.stream()
                .map(row -> {
                    com.smitgate.connector.ads.AdAccount account = accountMap.get(row.getAdAccountId());
                    return new AccountSpendResponse(
                            row.getAdAccountId(),
                            account != null ? account.getName() : "Account #" + row.getAdAccountId(),
                            account != null ? account.getPlatform().name() : "UNKNOWN",
                            nonNull(row.getTotalSpend())
                    );
                })
                .sorted(Comparator.comparing(AccountSpendResponse::getTotalSpend).reversed())
                .toList();
    }

    private boolean hasTrackingKeyLight(OrderRepository.OrderLightProjection order) {
        return order != null && order.getClickId() != null && !order.getClickId().isBlank();
    }

        private boolean isValidOrderStatus(String status) {
                return OrderStatusClassifier.isValidOrderStatus(status);
        }

        private BigDecimal nonNull(BigDecimal value) {
                return value != null ? value : BigDecimal.ZERO;
        }
}
