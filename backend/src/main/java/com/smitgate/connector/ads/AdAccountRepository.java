package com.smitgate.connector.ads;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdAccountRepository extends JpaRepository<AdAccount, Long> {

    List<AdAccount> findByTenantId(Long tenantId);

    List<AdAccount> findByTenantIdAndDataSourceId(Long tenantId, Long dataSourceId);

    Optional<AdAccount> findByTenantIdAndPlatformAndExternalAccountId(
            Long tenantId, AdAccount.Platform platform, String externalAccountId);
}
