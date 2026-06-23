package com.smitgate.connector.pos;

import com.smitgate.common.ApiResponse;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations/pancake")
@RequiredArgsConstructor
public class PancakeController {

    private final PancakeConnector pancakeConnector;
    private final DataSourceService dataSourceService;
    private final PosShopRepository posShopRepository;

    /**
     * Step 1: Validate API key, save DataSource (INACTIVE), return shops list for selection.
     * Frontend should then call /shops/select with chosen shops to activate the connection.
     */
    @PostMapping("/connect")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connect(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String apiKey = body.get("apiKey");
        String name = body.getOrDefault("name", "Pancake POS");

        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("API key không được để trống"));
        }

        // Validate API key by fetching shops (throws IllegalArgumentException on bad key)
        List<Map<String, Object>> shops = pancakeConnector.fetchShops(apiKey);

        if (shops.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("API key hợp lệ nhưng không tìm thấy shop nào. " +
                            "Kiểm tra lại tài khoản Poscake của bạn."));
        }

        // Create DataSource with INACTIVE status (activated after shop selection)
        DataSource ds = dataSourceService.create(
                tenantId, DataSource.Type.PANCAKE_POS, name, null, apiKey);

        Map<String, Object> result = new HashMap<>();
        result.put("dataSourceId", ds.getId());
        result.put("shops", shops);
        result.put("message", "API key hợp lệ! Chọn cửa hàng cần đồng bộ.");

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Step 2: Save selected shops and activate the DataSource.
     */
    @PostMapping("/shops/select")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> selectShops(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        Long dataSourceId = Long.valueOf(body.get("dataSourceId").toString());
        List<Map<String, String>> shops = (List<Map<String, String>>) body.get("shops");

        if (shops == null || shops.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Vui lòng chọn ít nhất một cửa hàng"));
        }

        List<PosShop> saved = pancakeConnector.selectShops(tenantId, dataSourceId, shops);
        dataSourceService.activate(tenantId, dataSourceId);

        Map<String, Object> result = new HashMap<>();
        result.put("connected", saved.size());
        result.put("message", "Kết nối " + saved.size() + " cửa hàng thành công!");

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Get active Poscake connections (shops) with status from DataSource.
     */
    @GetMapping("/shops/selected")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> selectedShops(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<DataSource> dataSources = dataSourceService.listByTenantAndType(tenantId, DataSource.Type.PANCAKE_POS);

        List<Map<String, Object>> result = new ArrayList<>();
        for (DataSource ds : dataSources) {
            List<PosShop> shops = posShopRepository.findByTenantIdAndDataSourceId(tenantId, ds.getId());
            for (PosShop shop : shops) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", ds.getId());
                item.put("shopId", shop.getId());
                item.put("shopName", shop.getName());
                item.put("externalShopId", shop.getExternalShopId());
                item.put("status", ds.getStatus().name());
                item.put("lastSyncAt", ds.getLastSuccessAt() != null ? ds.getLastSuccessAt().toString() : null);
                item.put("lastErrorMsg", ds.getLastErrorMsg());
                result.add(item);
            }
            // If DataSource exists but no shops yet, still show it
            if (shops.isEmpty()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", ds.getId());
                item.put("shopName", ds.getName());
                item.put("status", ds.getStatus().name());
                item.put("lastSyncAt", ds.getLastSuccessAt() != null ? ds.getLastSuccessAt().toString() : null);
                result.add(item);
            }
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncOrders(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        Long dataSourceId = Long.valueOf(String.valueOf(body.get("dataSourceId")));
        boolean forceFullSync = false;
        Object forceObj = body.get("forceFullSync");
        if (forceObj != null) {
            forceFullSync = Boolean.parseBoolean(String.valueOf(forceObj));
        }
        int count = pancakeConnector.syncOrders(tenantId, dataSourceId, forceFullSync);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("synced", count, "message", "Đồng bộ " + count + " đơn hàng thành công")));
    }

    @PostMapping("/sync/retry")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retrySyncOrders(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        Long dataSourceId = Long.valueOf(String.valueOf(body.get("dataSourceId")));
        boolean forceFullSync = false;
        Object forceObj = body.get("forceFullSync");
        if (forceObj != null) {
            forceFullSync = Boolean.parseBoolean(String.valueOf(forceObj));
        }

        final int maxAttempts = 3;
        List<Map<String, Object>> attempts = new ArrayList<>();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int synced = pancakeConnector.syncOrders(tenantId, dataSourceId, forceFullSync);
                DataSource ds = dataSourceService.getByIdAndTenant(tenantId, dataSourceId);
                boolean success = ds.getStatus() != DataSource.Status.ERROR;

                Map<String, Object> attemptInfo = new HashMap<>();
                attemptInfo.put("attempt", attempt);
                attemptInfo.put("synced", synced);
                attemptInfo.put("status", ds.getStatus().name());
                attemptInfo.put("lastErrorMsg", ds.getLastErrorMsg());
                attempts.add(attemptInfo);

                if (success) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("attempts", attempts);
                    result.put("attemptUsed", attempt);
                    result.put("synced", synced);
                    result.put("status", ds.getStatus().name());
                    result.put("message", "Kết nối đã phục hồi sau " + attempt + " lần thử");
                    return ResponseEntity.ok(ApiResponse.ok("Retry sync thành công", result));
                }
            } catch (Exception ex) {
                Map<String, Object> attemptInfo = new HashMap<>();
                attemptInfo.put("attempt", attempt);
                attemptInfo.put("synced", 0);
                attemptInfo.put("status", DataSource.Status.ERROR.name());
                attemptInfo.put("lastErrorMsg", rootMessage(ex));
                attempts.add(attemptInfo);
            }

            if (attempt < maxAttempts) {
                sleepQuietly(1200L * attempt);
            }
        }

        String lastError = attempts.isEmpty()
                ? "Không xác định"
                : String.valueOf(attempts.get(attempts.size() - 1).get("lastErrorMsg"));
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "Retry thất bại sau 3 lần. Lỗi cuối: " + lastError
        ));
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            return "Lỗi không xác định";
        }
        String normalized = message.replace("\n", " ").replace("\r", " ").trim();
        if (normalized.length() > 500) {
            return normalized.substring(0, 500);
        }
        return normalized;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

