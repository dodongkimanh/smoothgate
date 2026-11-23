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
    private final WorkerMemoryService memory;
    private final WorkerAnalysisService analysis;
    private final WorkerAdBoardService adBoard;

    /** "Phân tích ngay" runs in the background (Claude can take minutes); the page polls its state. */
    private final java.util.concurrent.ExecutorService runner = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final Map<Long, Map<String, Object>> runs = new java.util.concurrent.ConcurrentHashMap<>();

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

    @org.springframework.web.bind.annotation.DeleteMapping("/reports/{reportId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteReport(HttpServletRequest request,
                                                                         @org.springframework.web.bind.annotation.PathVariable long reportId) {
        if (!workers.hideReport(tenant(request), reportId)) {
            throw new IllegalArgumentException("Không tìm thấy báo cáo");
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", reportId)));
    }

    @GetMapping("/recommendations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> recommendations(
            HttpServletRequest request,
            @RequestParam(required = false) Long workerId,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(memory.listRecommendations(tenant(request), workerId, limit)));
    }

    /** Lead quality right now (CRM tags) for one worker's account. */
    @GetMapping("/{id}/lead-quality")
    public ResponseEntity<ApiResponse<Map<String, Object>>> leadQuality(HttpServletRequest request,
                                                                        @org.springframework.web.bind.annotation.PathVariable long id) {
        AdWorkerService.Worker w = workers.find(tenant(request), id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy worker"));
        return ResponseEntity.ok(ApiResponse.ok(analysis.liveQuality(w, null)));
    }

    /** The ads of this worker's account that spent today, Ads Manager columns plus per-period numbers and CRM leads. */
    @GetMapping("/{id}/ads-board")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adsBoard(HttpServletRequest request,
                                                                     @org.springframework.web.bind.annotation.PathVariable long id,
                                                                     @RequestParam(defaultValue = "false") boolean fresh) {
        AdWorkerService.Worker w = workers.find(tenant(request), id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy worker"));
        return ResponseEntity.ok(ApiResponse.ok(adBoard.board(w, fresh)));
    }

    /** CRM leads per assigned sale for this worker's account, per period (today … all time). */
    @GetMapping("/{id}/sales")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sales(HttpServletRequest request,
                                                                  @org.springframework.web.bind.annotation.PathVariable long id) {
        AdWorkerService.Worker w = workers.find(tenant(request), id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy worker"));
        return ResponseEntity.ok(ApiResponse.ok(adBoard.sales(w)));
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzeNow(HttpServletRequest request,
                                                                       @org.springframework.web.bind.annotation.PathVariable long id) {
        AdWorkerService.Worker w = workers.find(tenant(request), id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy worker"));
        Map<String, Object> current = runs.get(id);
        if (current != null && Boolean.TRUE.equals(current.get("running"))) {
            return ResponseEntity.ok(ApiResponse.ok(current));
        }
        String slot = "bấm tay " + java.time.LocalTime.now(AdWorkerService.VIETNAM_ZONE).withSecond(0).withNano(0);
        Map<String, Object> state = new java.util.concurrent.ConcurrentHashMap<>();
        state.put("running", true);
        state.put("startedAt", java.time.LocalDateTime.now(AdWorkerService.VIETNAM_ZONE).withNano(0).toString());
        runs.put(id, state);
        runner.submit(() -> {
            try {
                Map<String, Object> result = analysis.analyze(w, slot);
                if (!Boolean.TRUE.equals(result.get("ok"))) {
                    state.put("error", String.valueOf(result.getOrDefault("error", "Không phân tích được")));
                } else if (Boolean.TRUE.equals(result.get("skipped"))) {
                    state.put("error", String.valueOf(result.get("text")));
                }
            } catch (Exception e) {
                state.put("error", "Lỗi khi phân tích: " + e.getMessage());
            } finally {
                state.put("finishedAt", java.time.LocalDateTime.now(AdWorkerService.VIETNAM_ZONE).withNano(0).toString());
                state.put("running", false);
            }
        });
        return ResponseEntity.ok(ApiResponse.ok(state));
    }

    @GetMapping("/{id}/analyze")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analyzeState(@org.springframework.web.bind.annotation.PathVariable long id) {
        return ResponseEntity.ok(ApiResponse.ok(runs.getOrDefault(id, Map.of("running", false))));
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
