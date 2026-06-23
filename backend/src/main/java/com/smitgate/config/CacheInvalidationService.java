package com.smitgate.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheInvalidationService {

    private final CacheManager cacheManager;

    public void evictReportCaches() {
        clearCaches(List.of(
                CacheNames.REPORT_OVERVIEW,
                CacheNames.REPORT_CAMPAIGNS,
                CacheNames.REPORT_CAMPAIGN_DAILY,
                CacheNames.REPORT_CAMPAIGN_FUNNEL,
                CacheNames.REPORT_ATTRIBUTIONS,
                CacheNames.REPORT_ATTRIBUTION_QUALITY,
                CacheNames.REPORT_ACCOUNT_SPEND
        ));
    }

    public void evictDatasourceCaches() {
        clearCaches(List.of(
                CacheNames.DATASOURCE_BY_TENANT,
                CacheNames.DATASOURCE_BY_TENANT_TYPE
        ));
    }

    private void clearCaches(List<String> cacheNames) {
        for (String cacheName : cacheNames) {
            try {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            } catch (Exception ex) {
                log.warn("Failed to clear cache {}: {}", cacheName, ex.getMessage());
            }
        }
    }
}
