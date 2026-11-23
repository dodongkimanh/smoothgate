package com.smitgate.agent;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The Smoothgate "Worker" page: which workers are alive and what they reported. Admins only (default rule). */
@RestController
@RequestMapping("/api/workers")
@RequiredArgsConstructor
public class WorkerAdminController {

    private final AdWorkerService workers;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(workers.listWorkers(tenant(request))));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reports(
            HttpServletRequest request,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(workers.listReports(tenant(request), workerId, kind, limit)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(HttpServletRequest request,
                                                                   @RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        long adAccountId = Long.parseLong(String.valueOf(body.get("adAccountId")));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Cần đặt tên worker");
        }
        return ResponseEntity.ok(ApiResponse.ok(workers.createWorker(tenant(request), name, adAccountId)));
    }

    private static long tenant(HttpServletRequest request) {
        return (Long) request.getAttribute("tenantId");
    }
}
