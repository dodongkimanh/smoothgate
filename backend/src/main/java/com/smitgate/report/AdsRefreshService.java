package com.smitgate.report;

import com.smitgate.config.CacheInvalidationService;
import com.smitgate.connector.ads.MetaAdsConnector;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async service for on-demand Meta Ads refresh.
 * Separated from ReportService so @Async proxy works correctly
 * (Spring AOP cannot proxy self-invocations within the same bean).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdsRefreshService {

    private static final long ADS_REFRESH_COOLDOWN_MS = 300_000;

    private final MetaAdsConnector metaAdsConnector;
    private final DataSourceRepository dataSourceRepository;
    private final CacheInvalidationService cacheInvalidationService;

    private final ConcurrentHashMap<Long, Long> lastAdsRefreshMs = new ConcurrentHashMap<>();

    /**
     * Fire-and-forget refresh of ads metrics from Meta API.
     * Runs on a separate thread so the calling controller returns immediately.
     */
    @Async
    public void refreshAdsMetricsAsync(Long tenantId, LocalDate from, LocalDate to) {
        if (to.isBefore(LocalDate.now())) return;

        long now = System.currentTimeMillis();
        Long last = lastAdsRefreshMs.get(tenantId);
        if (last != null && (now - last) < ADS_REFRESH_COOLDOWN_MS) return;

        lastAdsRefreshMs.put(tenantId, now);

        try {
            List<DataSource> sources = dataSourceRepository.findByTenantIdAndType(
                    tenantId, DataSource.Type.META_ADS);
            for (DataSource ds : sources) {
                if (ds.getStatus() == DataSource.Status.DELETED
                        || ds.getStatus() == DataSource.Status.INACTIVE) continue;
                metaAdsConnector.syncMetricsDaily(tenantId, ds.getId(), from, to);
            }
            cacheInvalidationService.evictReportCaches();
            log.info("[Async] On-demand ads refresh for tenant {} ({} ~ {})", tenantId, from, to);
        } catch (Exception e) {
            log.warn("[Async] On-demand ads refresh failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
