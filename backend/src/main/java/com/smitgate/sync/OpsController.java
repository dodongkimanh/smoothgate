package com.smitgate.sync;

import com.smitgate.common.ApiResponse;
import com.smitgate.connector.ads.AdsMetricsDailyRepository;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceService;
import com.smitgate.datasource.SyncState;
import com.smitgate.datasource.SyncStateRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final DataSourceService dataSourceService;
    private final SyncStateRepository syncStateRepository;
    private final SyncScheduler syncScheduler;
    private final AdsMetricsDailyRepository metricsRepository;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/sync-jobs")
    public ResponseEntity<ApiResponse<List<SyncState>>> syncJobs(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<DataSource> sources = dataSourceService.listByTenant(tenantId);
        List<SyncState> states = sources.stream()
                .flatMap(ds -> {
                    List<SyncState> dsSyncStates = new ArrayList<>();
                    syncStateRepository.findByTenantIdAndDataSourceIdAndEntity(tenantId, ds.getId(), SyncState.Entity.ORDERS)
                            .ifPresent(dsSyncStates::add);
                    syncStateRepository.findByTenantIdAndDataSourceIdAndEntity(tenantId, ds.getId(), SyncState.Entity.ADS_METRICS)
                            .ifPresent(dsSyncStates::add);
                    return dsSyncStates.stream();
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(states));
    }

    @GetMapping("/sync-debug")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncDebug(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<DataSource> sources = dataSourceService.listByTenant(tenantId);

        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        long adsMetricsToday = metricsRepository.sumImpressionsByTenantIdAndDateRange(tenantId, today, today);

        List<Map<String, Object>> dsInfoList = new ArrayList<>();
        for (DataSource ds : sources) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", ds.getId());
            info.put("type", ds.getType());
            info.put("status", ds.getStatus());
            info.put("lastSuccessAt", ds.getLastSuccessAt());
            info.put("lastErrorAt", ds.getLastErrorAt());
            info.put("lastErrorMsg", ds.getLastErrorMsg());
            dsInfoList.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("serverTimeVN", LocalDate.now(VIETNAM_ZONE) + " " +
                java.time.LocalTime.now(VIETNAM_ZONE).withNano(0));
        result.put("serverTimeUTC", LocalDate.now(ZoneId.of("UTC")) + " " +
                java.time.LocalTime.now(ZoneId.of("UTC")).withNano(0));
        result.put("adsImpressionsToday", adsMetricsToday);
        result.put("dataSources", dsInfoList);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/sync-jobs/run")
    public ResponseEntity<ApiResponse<String>> triggerSync(
            @RequestParam String type) {
        switch (type.toUpperCase()) {
            case "ORDERS" -> syncScheduler.syncOrders();
            case "ADS" -> syncScheduler.syncAdsMetrics();
            case "ATTRIBUTION" -> syncScheduler.runAttribution();
            default -> throw new IllegalArgumentException("Unknown sync type: " + type);
        }
        return ResponseEntity.ok(ApiResponse.ok("Sync triggered: " + type));
    }
}
