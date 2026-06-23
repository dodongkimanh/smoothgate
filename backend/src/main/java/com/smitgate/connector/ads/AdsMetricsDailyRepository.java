package com.smitgate.connector.ads;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AdsMetricsDailyRepository extends JpaRepository<AdsMetricsDaily, Long> {

    /** Lightweight projection excluding rawJson — used by report queries. */
    interface MetricsSummaryProjection {
        Long getCampaignId();
        LocalDate getDate();
        java.math.BigDecimal getSpend();
        Long getMessageContacts();
    }

    Optional<AdsMetricsDaily> findByTenantIdAndPlatformAndAdAccountIdAndCampaignIdAndDate(
            Long tenantId, AdsMetricsDaily.Platform platform,
            Long adAccountId, Long campaignId, LocalDate date);

    List<AdsMetricsDaily> findByTenantIdAndDateBetween(
            Long tenantId, LocalDate from, LocalDate to);

    @Query("SELECT m.campaignId AS campaignId, m.date AS date, m.spend AS spend, m.messageContacts AS messageContacts " +
           "FROM AdsMetricsDaily m WHERE m.tenantId = :tenantId AND m.date BETWEEN :from AND :to")
    List<MetricsSummaryProjection> findMetricsSummaryByTenantIdAndDateBetween(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(m.spend), 0) FROM AdsMetricsDaily m " +
           "WHERE m.tenantId = :tenantId AND m.date BETWEEN :from AND :to")
    BigDecimal sumSpendByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(m.clicks), 0) FROM AdsMetricsDaily m " +
           "WHERE m.tenantId = :tenantId AND m.date BETWEEN :from AND :to")
    Long sumClicksByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(m.impressions), 0) FROM AdsMetricsDaily m " +
           "WHERE m.tenantId = :tenantId AND m.date BETWEEN :from AND :to")
    Long sumImpressionsByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(m.messageContacts), 0) FROM AdsMetricsDaily m " +
           "WHERE m.tenantId = :tenantId AND m.date BETWEEN :from AND :to")
    Long sumMessageContactsByTenantIdAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    interface AccountSpendProjection {
        Long getAdAccountId();
        BigDecimal getTotalSpend();
    }

    @Query("SELECT m.adAccountId AS adAccountId, COALESCE(SUM(m.spend), 0) AS totalSpend " +
           "FROM AdsMetricsDaily m WHERE m.tenantId = :tenantId AND m.date BETWEEN :from AND :to " +
           "GROUP BY m.adAccountId")
    List<AccountSpendProjection> sumSpendGroupedByAdAccount(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
