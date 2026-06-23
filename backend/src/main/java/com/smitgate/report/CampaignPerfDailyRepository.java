package com.smitgate.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CampaignPerfDailyRepository extends JpaRepository<CampaignPerfDaily, Long> {

    List<CampaignPerfDaily> findByTenantIdAndDateBetween(Long tenantId, LocalDate from, LocalDate to);

    List<CampaignPerfDaily> findByTenantIdAndPlatformAndDateBetween(
            Long tenantId, CampaignPerfDaily.Platform platform, LocalDate from, LocalDate to);

    List<CampaignPerfDaily> findByTenantIdAndCampaignIdAndDateBetween(
            Long tenantId, Long campaignId, LocalDate from, LocalDate to);

    Optional<CampaignPerfDaily> findByTenantIdAndPlatformAndCampaignIdAndDate(
            Long tenantId, CampaignPerfDaily.Platform platform, Long campaignId, LocalDate date);
}
