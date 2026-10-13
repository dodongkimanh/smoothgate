package com.smitgate.note;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdSavedViewRepository extends JpaRepository<AdSavedView, Long> {

    List<AdSavedView> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    Optional<AdSavedView> findByIdAndTenantId(Long id, Long tenantId);
}
