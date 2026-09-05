package com.smitgate.campaign;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampaignDraftRepository extends JpaRepository<CampaignDraft, Long> {

    List<CampaignDraft> findByTenantIdOrderByUpdatedAtDesc(Long tenantId);

    Optional<CampaignDraft> findByIdAndTenantId(Long id, Long tenantId);
}
