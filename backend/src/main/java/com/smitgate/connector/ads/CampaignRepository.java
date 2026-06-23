package com.smitgate.connector.ads;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByTenantId(Long tenantId);

    List<Campaign> findByTenantIdAndPlatform(Long tenantId, Campaign.Platform platform);

    Optional<Campaign> findByTenantIdAndPlatformAndExternalCampaignId(
            Long tenantId, Campaign.Platform platform, String externalCampaignId);
}
