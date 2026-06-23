package com.smitgate.attribution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderAttributionRepository extends JpaRepository<OrderAttribution, Long> {

    Optional<OrderAttribution> findByTenantIdAndOrderId(Long tenantId, Long orderId);

    boolean existsByTenantIdAndOrderId(Long tenantId, Long orderId);

    List<OrderAttribution> findByTenantIdAndOrderIdIn(Long tenantId, List<Long> orderIds);

    List<OrderAttribution> findByTenantIdAndCampaignIdIn(Long tenantId, List<Long> campaignIds);

    List<OrderAttribution> findByTenantIdAndCampaignIdOrderByMatchedAtDesc(Long tenantId, Long campaignId);

    @Query("SELECT a FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
          "WHERE a.tenantId = :tenantId AND a.campaignId = :campaignId " +
          "AND o.createdAtExternal BETWEEN :from AND :to " +
          "ORDER BY a.matchedAt DESC")
    Page<OrderAttribution> findRecentByTenantIdAndCampaignIdAndOrderDateRange(
           @Param("tenantId") Long tenantId,
           @Param("campaignId") Long campaignId,
           @Param("from") LocalDateTime from,
           @Param("to") LocalDateTime to,
           Pageable pageable);

    List<OrderAttribution> findByTenantIdOrderByMatchedAtDesc(Long tenantId);

    Page<OrderAttribution> findByTenantIdOrderByMatchedAtDesc(Long tenantId, Pageable pageable);

    List<OrderAttribution> findByTenantIdAndMatchType(Long tenantId, OrderAttribution.MatchType matchType);

    List<OrderAttribution> findByTenantIdAndCampaignIdIsNull(Long tenantId);

    @Query("SELECT a.campaignId, FUNCTION('date', o.createdAtExternal), COUNT(o), COALESCE(SUM(o.revenue), 0), COALESCE(SUM(o.shippingFee), 0) " +
           "FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
           "WHERE a.tenantId = :tenantId " +
           "AND a.campaignId IN :campaignIds " +
           "AND o.createdAtExternal BETWEEN :from AND :to " +
           "AND LOWER(o.status) IN :validStatuses " +
           "GROUP BY a.campaignId, FUNCTION('date', o.createdAtExternal)")
    List<Object[]> aggregateValidOrdersRevenueByCampaignAndDate(
            @Param("tenantId") Long tenantId,
            @Param("campaignIds") List<Long> campaignIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT a.campaignId, COUNT(DISTINCT o.customerPhone) " +
           "FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
           "WHERE a.tenantId = :tenantId " +
           "AND a.campaignId IN :campaignIds " +
           "AND o.createdAtExternal BETWEEN :from AND :to " +
           "AND o.customerPhone IS NOT NULL AND o.customerPhone <> '' " +
           "AND LOWER(o.status) IN :leadStatuses " +
           "GROUP BY a.campaignId")
    List<Object[]> countDistinctPhonesByCampaignInDateRange(
            @Param("tenantId") Long tenantId,
            @Param("campaignIds") List<Long> campaignIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("leadStatuses") List<String> leadStatuses);

    @Query("SELECT COUNT(DISTINCT a.orderId) FROM OrderAttribution a " +
           "WHERE a.tenantId = :tenantId AND a.matchType <> 'UNKNOWN' " +
           "AND EXISTS (SELECT 1 FROM Order o WHERE o.id = a.orderId AND o.tenantId = :tenantId " +
           "AND o.createdAtExternal BETWEEN :from AND :to)")
    long countAttributedByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT a.orderId) FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
          "WHERE a.tenantId = :tenantId AND a.campaignId = :campaignId " +
          "AND o.createdAtExternal BETWEEN :from AND :to")
    long countAttributedOrdersByCampaignInDateRange(
           @Param("tenantId") Long tenantId,
           @Param("campaignId") Long campaignId,
           @Param("from") LocalDateTime from,
           @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT a.orderId) FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
          "WHERE a.tenantId = :tenantId AND a.campaignId = :campaignId " +
          "AND o.createdAtExternal BETWEEN :from AND :to " +
          "AND LOWER(o.status) IN :validStatuses")
    long countValidOrdersByCampaignInDateRange(
           @Param("tenantId") Long tenantId,
           @Param("campaignId") Long campaignId,
           @Param("from") LocalDateTime from,
           @Param("to") LocalDateTime to,
           @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT COALESCE(SUM(o.revenue), 0) FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
          "WHERE a.tenantId = :tenantId AND a.campaignId = :campaignId " +
          "AND o.createdAtExternal BETWEEN :from AND :to " +
          "AND LOWER(o.status) IN :validStatuses")
    java.math.BigDecimal sumValidRevenueByCampaignInDateRange(
           @Param("tenantId") Long tenantId,
           @Param("campaignId") Long campaignId,
           @Param("from") LocalDateTime from,
           @Param("to") LocalDateTime to,
           @Param("validStatuses") List<String> validStatuses);

    @Query("SELECT COUNT(DISTINCT o.customerPhone) FROM OrderAttribution a JOIN Order o ON o.id = a.orderId " +
          "WHERE a.tenantId = :tenantId AND a.campaignId = :campaignId " +
          "AND o.createdAtExternal BETWEEN :from AND :to " +
          "AND o.customerPhone IS NOT NULL AND o.customerPhone <> '' " +
          "AND LOWER(o.status) IN :leadStatuses")
    long countDistinctLeadPhonesByCampaignInDateRange(
           @Param("tenantId") Long tenantId,
           @Param("campaignId") Long campaignId,
           @Param("from") LocalDateTime from,
           @Param("to") LocalDateTime to,
           @Param("leadStatuses") List<String> leadStatuses);
}
