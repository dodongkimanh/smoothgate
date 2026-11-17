package com.smitgate.connector.ads;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MetaCustomerAudienceRepository extends JpaRepository<MetaCustomerAudience, Long> {

    List<MetaCustomerAudience> findByTenantId(Long tenantId);
}
