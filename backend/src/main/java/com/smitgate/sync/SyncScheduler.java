package com.smitgate.sync;

import com.smitgate.connector.ads.MetaAdsConnector;
import com.smitgate.connector.pos.PancakeConnector;
import com.smitgate.config.CacheInvalidationService;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import com.smitgate.attribution.AttributionEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final PancakeConnector pancakeConnector;
    private final MetaAdsConnector metaAdsConnector;
    private final AttributionEngine attributionEngine;
    private final CacheInvalidationService cacheInvalidationService;
    private final AtomicLong lastReportCacheEvictAt = new AtomicLong(0L);

    @Value("${app.sync.scheduler.orders-enabled:true}")
    private boolean syncOrdersEnabled;

    @Value("${app.sync.scheduler.ads-enabled:true}")
    private boolean syncAdsEnabled;

    @Value("${app.sync.scheduler.attribution-enabled:true}")
    private boolean syncAttributionEnabled;

    @Value("${app.sync.scheduler.report-cache-evict-min-interval-ms:300000}")
    private long reportCacheEvictMinIntervalMs;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<DataSource.Status> SYNCABLE_STATUSES =
            List.of(DataSource.Status.ACTIVE, DataSource.Status.ERROR);

    /** Sync POS orders every 15 minutes. */
    @Scheduled(
            fixedDelayString = "${app.sync.scheduler.orders-fixed-delay-ms:900000}",
            initialDelayString = "${app.sync.scheduler.orders-initial-delay-ms:60000}")
    public void syncOrders() {
        if (!syncOrdersEnabled) {
            return;
        }

        List<DataSource> sources;
        try {
            sources = dataSourceRepository
                    .findByTypeAndStatus(DataSource.Type.PANCAKE_POS, DataSource.Status.ACTIVE);
        } catch (DataAccessException ex) {
            log.warn("Skip POS scheduled sync due to DB connection issue: {}", ex.getMessage());
            return;
        }

        boolean hasDataChange = false;

        for (DataSource ds : sources) {
            try {
                int count = pancakeConnector.syncOrders(ds.getTenantId(), ds.getId());
                log.info("Synced {} orders for tenant {} datasource {}", count, ds.getTenantId(), ds.getId());
                if (count > 0) {
                    hasDataChange = true;
                }
            } catch (Exception e) {
                log.error("Order sync failed for datasource {}: {}", ds.getId(), e.getMessage());
                try {
                    dataSourceService.markError(ds.getId(), e.getMessage());
                } catch (Exception markEx) {
                    log.warn("Unable to persist order sync error state for datasource {}: {}", ds.getId(), markEx.getMessage());
                }
            }
        }

        if (hasDataChange) {
            evictReportCachesIfDue("orders");
        }
    }

    /**
     * Sync ads metrics every 30 minutes.
     * Meta Ads API refreshes data every ~15-30 min — syncing faster won't yield fresher data
     * and risks hitting rate limits. initialDelay=90s lets the app fully start first.
     */
    @Scheduled(
            fixedDelayString = "${app.sync.scheduler.ads-fixed-delay-ms:1800000}",
            initialDelayString = "${app.sync.scheduler.ads-initial-delay-ms:90000}")
    public void syncAdsMetrics() {
        if (!syncAdsEnabled) {
            return;
        }

        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        LocalDate yesterday = today.minusDays(1);

        List<DataSource> sources;
        try {
            sources = dataSourceRepository
                    .findByTypeAndStatusIn(DataSource.Type.META_ADS, SYNCABLE_STATUSES);
        } catch (DataAccessException ex) {
            log.warn("Skip Ads scheduled sync due to DB connection issue: {}", ex.getMessage());
            return;
        }

        boolean hasDataChange = false;

        for (DataSource ds : sources) {
            try {
                int count = metaAdsConnector.syncMetricsDaily(ds.getTenantId(), ds.getId(), yesterday, today);
                log.info("Synced {} metrics for tenant {} datasource {}", count, ds.getTenantId(), ds.getId());
                if (count > 0) {
                    hasDataChange = true;
                }
            } catch (Exception e) {
                log.error("Ads sync failed for datasource {}: {}", ds.getId(), e.getMessage());
                try {
                    dataSourceService.markError(ds.getId(), e.getMessage());
                } catch (Exception markEx) {
                    log.warn("Unable to persist ads sync error state for datasource {}: {}", ds.getId(), markEx.getMessage());
                }
            }
        }

        if (hasDataChange) {
            evictReportCachesIfDue("ads");
        }
    }

    /** Run attribution after order sync, every 20 minutes. */
    @Scheduled(
            fixedDelayString = "${app.sync.scheduler.attribution-fixed-delay-ms:1200000}",
            initialDelayString = "${app.sync.scheduler.attribution-initial-delay-ms:120000}")
    public void runAttribution() {
        if (!syncAttributionEnabled) {
            return;
        }

        List<Long> tenantIds;
        try {
            tenantIds = dataSourceRepository.findDistinctTenantIdsByStatus(DataSource.Status.ACTIVE);
        } catch (DataAccessException ex) {
            log.warn("Skip attribution scheduled sync due to DB connection issue: {}", ex.getMessage());
            return;
        }

        boolean hasDataChange = false;

        for (Long tenantId : tenantIds) {
            try {
                int count = attributionEngine.attributeUnmatched(tenantId);
                if (count > 0) {
                    log.info("Attributed {} orders for tenant {}", count, tenantId);
                    hasDataChange = true;
                }
            } catch (Exception e) {
                log.error("Attribution failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }

        if (hasDataChange) {
            evictReportCachesIfDue("attribution");
        }
    }

    private void evictReportCachesIfDue(String source) {
        long now = System.currentTimeMillis();
        long last = lastReportCacheEvictAt.get();
        if (now - last < Math.max(reportCacheEvictMinIntervalMs, 0L)) {
            log.debug("Skip report cache eviction from {} because cooldown is active ({} ms)",
                    source, reportCacheEvictMinIntervalMs);
            return;
        }
        if (lastReportCacheEvictAt.compareAndSet(last, now)) {
            cacheInvalidationService.evictReportCaches();
            log.debug("Evicted report caches (triggered by: {})", source);
        }
    }
}
