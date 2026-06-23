package com.smitgate.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SyncStateRepository extends JpaRepository<SyncState, Long> {

    Optional<SyncState> findByTenantIdAndDataSourceIdAndEntity(
            Long tenantId, Long dataSourceId, SyncState.Entity entity);
}
