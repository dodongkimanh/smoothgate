package com.smitgate.datasource;

import com.smitgate.common.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceService {

    private static final String CACHE_DATASOURCE_BY_TENANT = "datasource:by_tenant";
    private static final String CACHE_DATASOURCE_BY_TENANT_TYPE = "datasource:by_tenant_type";

    private final DataSourceRepository dataSourceRepository;
    private final EncryptionUtil encryptionUtil;
    private final CacheManager cacheManager;

    @Value("${app.poscake.fallback-api-key:}")
    private String poscakeFallbackApiKey;

    private static final String SOFT_DELETED_MARKER = "\"softDeleted\":true";

    @Cacheable(cacheNames = CACHE_DATASOURCE_BY_TENANT, key = "#tenantId")
    public List<DataSource> listByTenant(Long tenantId) {
        return dataSourceRepository.findByTenantId(tenantId).stream()
                .filter(ds -> !isSoftDeleted(ds))
                .collect(Collectors.toList());
    }

    @Cacheable(cacheNames = CACHE_DATASOURCE_BY_TENANT_TYPE, key = "#tenantId + ':' + #type.name()")
    public List<DataSource> listByTenantAndType(Long tenantId, DataSource.Type type) {
        return dataSourceRepository.findByTenantIdAndType(tenantId, type).stream()
                .filter(ds -> !isSoftDeleted(ds))
                .collect(Collectors.toList());
    }

        @Retryable(
            retryFor = {DataAccessResourceFailureException.class, CannotCreateTransactionException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 400, multiplier = 1.8))
        public DataSource getByIdAndTenant(Long tenantId, Long id) {
        return dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found"));
    }

    @Transactional
    public DataSource create(Long tenantId, DataSource.Type type, String name,
                             String configJson, String secret) {
        DataSource ds = new DataSource();
        ds.setTenantId(tenantId);
        ds.setType(type);
        ds.setName(name);
        ds.setConfigJson(configJson);
        if (secret != null) {
            ds.setSecretEncrypted(encryptionUtil.encrypt(secret));
        }
        ds.setStatus(DataSource.Status.INACTIVE);
        DataSource saved = dataSourceRepository.save(ds);
        evictDatasourceCaches();
        return saved;
    }

    @Transactional
    public DataSource activate(Long tenantId, Long id) {
        DataSource ds = dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found"));
        ds.setStatus(DataSource.Status.ACTIVE);
        DataSource saved = dataSourceRepository.save(ds);
        evictDatasourceCaches();
        return saved;
    }

    @Transactional
    public void updateSecret(Long tenantId, Long id, String newSecret) {
        DataSource ds = dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found"));
        ds.setSecretEncrypted(encryptionUtil.encrypt(newSecret));
        dataSourceRepository.save(ds);
        evictDatasourceCaches();
    }

    @Transactional
    public DataSource deactivate(Long tenantId, Long id) {
        DataSource ds = dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found"));
        ds.setStatus(DataSource.Status.INACTIVE);
        DataSource saved = dataSourceRepository.save(ds);
        evictDatasourceCaches();
        return saved;
    }

    @Transactional
    public DataSource softDelete(Long tenantId, Long id) {
        DataSource ds = dataSourceRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found"));

        if (isSoftDeleted(ds)) {
            throw new IllegalStateException("Kết nối đã được xóa trước đó");
        }
        if (ds.getStatus() == DataSource.Status.ACTIVE) {
            throw new IllegalStateException("Kết nối đang hoạt động. Vui lòng ngắt kết nối trước khi xóa");
        }

        ds.setStatus(DataSource.Status.INACTIVE);
        ds.setConfigJson("{\"softDeleted\":true,\"deletedAt\":\"" + LocalDateTime.now() + "\"}");
        ds.setSecretEncrypted(null);
        ds.setLastErrorMsg("Đã xóa mềm bởi người dùng");
        DataSource saved = dataSourceRepository.save(ds);
        evictDatasourceCaches();
        return saved;
    }

    public boolean isSoftDeleted(DataSource ds) {
        if (ds == null || ds.getConfigJson() == null) {
            return false;
        }
        return ds.getConfigJson().replaceAll("\\s+", "").contains(SOFT_DELETED_MARKER);
    }

    @Transactional
    public void markError(Long id, String errorMsg) {
        dataSourceRepository.findById(id).ifPresent(ds -> {
            ds.setStatus(DataSource.Status.ERROR);
            ds.setLastErrorAt(LocalDateTime.now());
            ds.setLastErrorMsg(errorMsg);
            dataSourceRepository.save(ds);
            evictDatasourceCaches();
        });
    }

    @Transactional
    public void markSuccess(Long id) {
        dataSourceRepository.findById(id).ifPresent(ds -> {
            ds.setLastSuccessAt(LocalDateTime.now());
            ds.setLastErrorMsg(null);
            if (ds.getStatus() == DataSource.Status.ERROR) {
                ds.setStatus(DataSource.Status.ACTIVE);
            }
            dataSourceRepository.save(ds);
            evictDatasourceCaches();
        });
    }

    private void evictDatasourceCaches() {
        clearCache(CACHE_DATASOURCE_BY_TENANT);
        clearCache(CACHE_DATASOURCE_BY_TENANT_TYPE);
    }

    private void clearCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    @Transactional
    public String decryptSecret(DataSource ds) {
        if (ds.getSecretEncrypted() == null) return null;
        try {
            return encryptionUtil.decrypt(ds.getSecretEncrypted());
        } catch (Exception e) {
            log.warn("Decryption failed for datasource {} (type={}) — attempting re-encrypt with fallback key (available={})",
                    ds.getId(), ds.getType(), poscakeFallbackApiKey != null && !poscakeFallbackApiKey.isBlank());
            if (ds.getType() == DataSource.Type.PANCAKE_POS && poscakeFallbackApiKey != null && !poscakeFallbackApiKey.isBlank()) {
                ds.setSecretEncrypted(encryptionUtil.encrypt(poscakeFallbackApiKey));
                ds.setLastErrorMsg(null);
                ds.setStatus(DataSource.Status.ACTIVE);
                dataSourceRepository.save(ds);
                log.info("Re-encrypted Poscake API key for datasource {} using fallback", ds.getId());
                return poscakeFallbackApiKey;
            }
            throw new RuntimeException("Decryption failed and no fallback available", e);
        }
    }
}
