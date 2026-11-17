package com.smitgate.connector.ads;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MetaLeadEventRepository extends JpaRepository<MetaLeadEvent, Long> {

    List<MetaLeadEvent> findByTenantIdAndPageIdIn(Long tenantId, java.util.Collection<String> pageIds);

    List<MetaLeadEvent> findByTenantIdAndSentAtIsNullAndEventTimeAfter(Long tenantId, LocalDateTime after);
}
