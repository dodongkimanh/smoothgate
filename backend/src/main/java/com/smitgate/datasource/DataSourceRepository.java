package com.smitgate.datasource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    List<DataSource> findByTenantIdAndStatusNot(Long tenantId, DataSource.Status status);

    List<DataSource> findByTenantId(Long tenantId);

    List<DataSource> findByTenantIdAndType(Long tenantId, DataSource.Type type);

    List<DataSource> findByTenantIdAndTypeAndStatusNot(Long tenantId, DataSource.Type type, DataSource.Status status);

    List<DataSource> findByTenantIdAndStatus(Long tenantId, DataSource.Status status);

    List<DataSource> findByTypeAndStatus(DataSource.Type type, DataSource.Status status);

    @Query("SELECT d FROM DataSource d WHERE d.type = :type AND d.status IN :statuses")
    List<DataSource> findByTypeAndStatusIn(@Param("type") DataSource.Type type,
                                           @Param("statuses") List<DataSource.Status> statuses);

    @Query("SELECT DISTINCT d.tenantId FROM DataSource d WHERE d.status = :status")
    List<Long> findDistinctTenantIdsByStatus(@Param("status") DataSource.Status status);

    Optional<DataSource> findByIdAndTenantId(Long id, Long tenantId);
}
