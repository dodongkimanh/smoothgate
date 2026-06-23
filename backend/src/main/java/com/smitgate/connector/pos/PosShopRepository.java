package com.smitgate.connector.pos;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PosShopRepository extends JpaRepository<PosShop, Long> {

    List<PosShop> findByTenantId(Long tenantId);

    Optional<PosShop> findByTenantIdAndExternalShopId(Long tenantId, String externalShopId);

    List<PosShop> findByTenantIdAndDataSourceId(Long tenantId, Long dataSourceId);
}
