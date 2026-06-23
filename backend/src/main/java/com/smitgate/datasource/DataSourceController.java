package com.smitgate.datasource;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService dataSourceService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DataSource>>> list(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(dataSourceService.listByTenant(tenantId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DataSource>> create(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        DataSource.Type type = DataSource.Type.valueOf(body.get("type"));
        String name = body.getOrDefault("name", type.name());
        String configJson = body.get("configJson");
        String secret = body.get("secret");
        DataSource ds = dataSourceService.create(tenantId, type, name, configJson, secret);
        return ResponseEntity.ok(ApiResponse.ok(ds));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<DataSource>> activate(
            HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(dataSourceService.activate(tenantId, id)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<DataSource>> deactivate(
            HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(dataSourceService.deactivate(tenantId, id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSource>> softDelete(
            HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(dataSourceService.softDelete(tenantId, id)));
    }
}
