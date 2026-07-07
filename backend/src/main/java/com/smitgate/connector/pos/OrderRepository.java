package com.smitgate.connector.pos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    interface UnattributedOrderProjection {
        Long getId();
        String getClickId();
        String getUtmCampaign();
        String getUtmSource();
    }

    /** Lightweight projection excluding rawJson — used by attribution quality & details queries. */
    interface OrderLightProjection {
        Long getId();
        String getExternalOrderId();
        String getStatus();
        java.math.BigDecimal getRevenue();
        java.math.BigDecimal getShippingFee();
        String getCustomerPhone();
        String getUtmSource();
        String getUtmMedium();
        String getUtmCampaign();
        String getUtmContent();
        String getUtmTerm();
        String getClickId();
        java.time.LocalDateTime getCreatedAtExternal();
    }

    interface OrderListProjection {
                Long getId();
                String getExternalOrderId();
                Long getPosShopId();
                String getStatus();
                java.math.BigDecimal getRevenue();
                String getCustomerPhone();
                String getCustomerName();
                String getUtmSource();
                String getUtmMedium();
                String getUtmCampaign();
                String getUtmContent();
                String getUtmTerm();
                String getClickId();
                java.math.BigDecimal getShippingFee();
                java.time.LocalDateTime getCreatedAtExternal();
                java.time.LocalDateTime getCreatedAt();
                String getRawJsonSnippet();
        }

    Optional<Order> findByTenantIdAndPosShopIdAndExternalOrderId(
            Long tenantId, Long posShopId, String externalOrderId);

    @Query("SELECT o.id FROM Order o WHERE o.tenantId = :tenantId AND o.posShopId = :posShopId AND o.externalOrderId = :externalOrderId")
    Optional<Long> findIdByTenantIdAndPosShopIdAndExternalOrderId(
            @Param("tenantId") Long tenantId,
            @Param("posShopId") Long posShopId,
            @Param("externalOrderId") String externalOrderId);

    List<Order> findByTenantIdAndExternalOrderIdOrderByIdDesc(Long tenantId, String externalOrderId);

    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId AND o.externalOrderId LIKE CONCAT('%', :keyword, '%') ORDER BY o.id DESC")
    Page<Order> findByTenantIdAndExternalOrderIdLike(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);

    List<Order> findByTenantId(Long tenantId);

    @Query("SELECT o.id FROM Order o WHERE o.tenantId = :tenantId " +
           "AND (o.shippingFee IS NULL OR o.shippingFee = 0) " +
           "AND o.rawJson IS NOT NULL")
    Page<Long> findIdsWithZeroShippingFee(@Param("tenantId") Long tenantId, Pageable pageable);

        Page<Order> findByTenantId(Long tenantId, Pageable pageable);

    @Query(value = "SELECT o.id as id, o.externalOrderId as externalOrderId, o.posShopId as posShopId, " +
           "o.status as status, o.revenue as revenue, o.customerPhone as customerPhone, " +
           "o.customerName as customerName, " +
           "o.utmSource as utmSource, o.utmMedium as utmMedium, o.utmCampaign as utmCampaign, " +
           "o.utmContent as utmContent, o.utmTerm as utmTerm, o.clickId as clickId, " +
           "o.shippingFee as shippingFee, " +
           "o.createdAtExternal as createdAtExternal, o.createdAt as createdAt, " +
           "substring(coalesce(o.rawJson, ''), 1, :rawLimit) as rawJsonSnippet " +
           "FROM Order o WHERE o.tenantId = :tenantId",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId")
    Page<OrderListProjection> findOrderSummariesByTenantId(
            @Param("tenantId") Long tenantId,
            @Param("rawLimit") int rawLimit,
            Pageable pageable);

        Page<Order> findByTenantIdAndStatusIgnoreCase(Long tenantId, String status, Pageable pageable);

    @Query(value = "SELECT o.id as id, o.externalOrderId as externalOrderId, o.posShopId as posShopId, " +
           "o.status as status, o.revenue as revenue, o.customerPhone as customerPhone, " +
           "o.customerName as customerName, " +
           "o.utmSource as utmSource, o.utmMedium as utmMedium, o.utmCampaign as utmCampaign, " +
           "o.utmContent as utmContent, o.utmTerm as utmTerm, o.clickId as clickId, " +
           "o.shippingFee as shippingFee, " +
           "o.createdAtExternal as createdAtExternal, o.createdAt as createdAt, " +
           "substring(coalesce(o.rawJson, ''), 1, :rawLimit) as rawJsonSnippet " +
           "FROM Order o WHERE o.tenantId = :tenantId AND LOWER(o.status) = LOWER(:status)",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId AND LOWER(o.status) = LOWER(:status)")
    Page<OrderListProjection> findOrderSummariesByTenantIdAndStatusIgnoreCase(
            @Param("tenantId") Long tenantId,
            @Param("status") String status,
            @Param("rawLimit") int rawLimit,
            Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to")
    List<Order> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT o.id AS id, o.externalOrderId AS externalOrderId, o.status AS status, " +
           "o.revenue AS revenue, o.shippingFee AS shippingFee, o.customerPhone AS customerPhone, " +
           "o.utmSource AS utmSource, o.utmMedium AS utmMedium, o.utmCampaign AS utmCampaign, " +
           "o.utmContent AS utmContent, o.utmTerm AS utmTerm, o.clickId AS clickId, " +
           "o.createdAtExternal AS createdAtExternal " +
           "FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to")
    List<OrderLightProjection> findLightByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT o.id AS id, o.externalOrderId AS externalOrderId, o.status AS status, " +
           "o.revenue AS revenue, o.shippingFee AS shippingFee, o.customerPhone AS customerPhone, " +
           "o.utmSource AS utmSource, o.utmMedium AS utmMedium, o.utmCampaign AS utmCampaign, " +
           "o.utmContent AS utmContent, o.utmTerm AS utmTerm, o.clickId AS clickId, " +
           "o.createdAtExternal AS createdAtExternal " +
           "FROM Order o WHERE o.id IN :ids")
    List<OrderLightProjection> findLightByIds(@Param("ids") Iterable<Long> ids);

    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to")
    Page<Order> findByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query(value = "SELECT o.id as id, o.externalOrderId as externalOrderId, o.posShopId as posShopId, " +
            "o.status as status, o.revenue as revenue, o.customerPhone as customerPhone, " +
            "o.customerName as customerName, " +
            "o.utmSource as utmSource, o.utmMedium as utmMedium, o.utmCampaign as utmCampaign, " +
            "o.utmContent as utmContent, o.utmTerm as utmTerm, o.clickId as clickId, " +
            "o.shippingFee as shippingFee, " +
            "o.createdAtExternal as createdAtExternal, o.createdAt as createdAt, " +
            "substring(coalesce(o.rawJson, ''), 1, :rawLimit) as rawJsonSnippet " +
            "FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to",
            countQuery = "SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to")
    Page<OrderListProjection> findOrderSummariesByTenantIdAndDateRange(
             @Param("tenantId") Long tenantId,
             @Param("from") LocalDateTime from,
             @Param("to") LocalDateTime to,
             @Param("rawLimit") int rawLimit,
             Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to " +
           "AND LOWER(o.status) = LOWER(:status)")
    Page<Order> findByTenantIdAndDateRangeAndStatus(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") String status,
            Pageable pageable);

    @Query(value = "SELECT o.id as id, o.externalOrderId as externalOrderId, o.posShopId as posShopId, " +
            "o.status as status, o.revenue as revenue, o.customerPhone as customerPhone, " +
            "o.customerName as customerName, " +
            "o.utmSource as utmSource, o.utmMedium as utmMedium, o.utmCampaign as utmCampaign, " +
            "o.utmContent as utmContent, o.utmTerm as utmTerm, o.clickId as clickId, " +
            "o.shippingFee as shippingFee, " +
            "o.createdAtExternal as createdAtExternal, o.createdAt as createdAt, " +
            "substring(coalesce(o.rawJson, ''), 1, :rawLimit) as rawJsonSnippet " +
            "FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) = LOWER(:status)",
            countQuery = "SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) = LOWER(:status)")
    Page<OrderListProjection> findOrderSummariesByTenantIdAndDateRangeAndStatus(
             @Param("tenantId") Long tenantId,
             @Param("from") LocalDateTime from,
             @Param("to") LocalDateTime to,
             @Param("status") String status,
             @Param("rawLimit") int rawLimit,
             Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.tenantId = :tenantId " +
           "AND NOT EXISTS (SELECT 1 FROM OrderAttribution a WHERE a.tenantId = :tenantId AND a.orderId = o.id)")
    Page<Order> findUnattributedByTenantId(
            @Param("tenantId") Long tenantId,
            Pageable pageable);

    @Query("SELECT o.id as id, o.clickId as clickId, o.utmCampaign as utmCampaign, o.utmSource as utmSource " +
            "FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.id > :afterId " +
            "AND NOT EXISTS (SELECT 1 FROM OrderAttribution a WHERE a.tenantId = :tenantId AND a.orderId = o.id) " +
            "ORDER BY o.id ASC")
    List<UnattributedOrderProjection> findUnattributedBatch(
             @Param("tenantId") Long tenantId,
             @Param("afterId") Long afterId,
             Pageable pageable);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to")
    long countByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) IN :validStatuses")
    long countValidOrdersByTenantIdAndDateRange(
             @Param("tenantId") Long tenantId,
             @Param("from") LocalDateTime from,
             @Param("to") LocalDateTime to,
             @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT COUNT(DISTINCT o.customerPhone) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND o.customerPhone IS NOT NULL AND o.customerPhone <> '' " +
            "AND LOWER(o.status) IN :validStatuses")
    long countDistinctPhonesByTenantIdAndDateRange(
             @Param("tenantId") Long tenantId,
             @Param("from") LocalDateTime from,
             @Param("to") LocalDateTime to,
             @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT COALESCE(SUM(o.revenue), 0) FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to")
    java.math.BigDecimal sumRevenueByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(o.revenue), 0) FROM Order o WHERE o.tenantId = :tenantId")
    java.math.BigDecimal sumRevenueByTenantId(
            @Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(SUM(o.revenue), 0) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND LOWER(o.status) = LOWER(:status)")
    java.math.BigDecimal sumRevenueByTenantIdAndStatusIgnoreCase(
            @Param("tenantId") Long tenantId,
            @Param("status") String status);

    @Query("SELECT COALESCE(SUM(o.revenue), 0) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) = LOWER(:status)")
    java.math.BigDecimal sumRevenueByTenantIdAndDateRangeAndStatusIgnoreCase(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") String status);

    @Query("SELECT COALESCE(SUM(o.shippingFee), 0) FROM Order o WHERE o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to")
    java.math.BigDecimal sumShippingFeeByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(o.shippingFee), 0) FROM Order o WHERE o.tenantId = :tenantId")
    java.math.BigDecimal sumShippingFeeByTenantId(
            @Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(SUM(o.shippingFee), 0) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND LOWER(o.status) = LOWER(:status)")
    java.math.BigDecimal sumShippingFeeByTenantIdAndStatusIgnoreCase(
            @Param("tenantId") Long tenantId,
            @Param("status") String status);

    @Query("SELECT COALESCE(SUM(o.shippingFee), 0) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) = LOWER(:status)")
    java.math.BigDecimal sumShippingFeeByTenantIdAndDateRangeAndStatusIgnoreCase(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") String status);

    @Query("SELECT COALESCE(SUM(o.revenue), 0) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) IN :validStatuses")
    java.math.BigDecimal sumValidRevenueByTenantIdAndDateRange(
             @Param("tenantId") Long tenantId,
             @Param("from") LocalDateTime from,
             @Param("to") LocalDateTime to,
             @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT COALESCE(SUM(o.shippingFee), 0) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND LOWER(o.status) IN :validStatuses")
    java.math.BigDecimal sumValidShippingFeeByTenantIdAndDateRange(
             @Param("tenantId") Long tenantId,
             @Param("from") LocalDateTime from,
             @Param("to") LocalDateTime to,
             @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT o.clickId, COUNT(o), COALESCE(SUM(o.revenue), 0), COALESCE(SUM(o.shippingFee), 0), " +
            "SUM(CASE WHEN o.shippingFee > 0 THEN 1 ELSE 0 END) " +
            "FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND o.clickId IN :clickIds " +
            "AND LOWER(o.status) IN :validStatuses " +
            "GROUP BY o.clickId")
    List<Object[]> aggregateRevenueAndOrderCountByClickIds(
            @Param("tenantId") Long tenantId,
            @Param("clickIds") List<String> clickIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT o.clickId, COUNT(DISTINCT o.customerPhone) " +
            "FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "AND o.clickId IN :clickIds " +
            "AND o.customerPhone IS NOT NULL AND o.customerPhone <> '' " +
            "AND LOWER(o.status) IN :leadStatuses " +
            "GROUP BY o.clickId")
    List<Object[]> aggregateDistinctPhonesByClickIds(
            @Param("tenantId") Long tenantId,
            @Param("clickIds") List<String> clickIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("leadStatuses") List<String> leadStatuses);

    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.tenantId = :tenantId " +
            "AND o.createdAtExternal BETWEEN :from AND :to " +
            "GROUP BY o.status")
    List<Object[]> countByStatusAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.tenantId = :tenantId " +
            "GROUP BY o.status")
    List<Object[]> countByStatus(@Param("tenantId") Long tenantId);
}
