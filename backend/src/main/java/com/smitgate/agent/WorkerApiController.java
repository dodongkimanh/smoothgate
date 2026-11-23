package com.smitgate.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Called by the workers themselves (no user login): each request carries the worker's own key in X-Worker-Key,
 * which only gives access to the one ad account that worker watches.
 */
@Slf4j
@RestController
@RequestMapping("/api/worker")
@RequiredArgsConstructor
public class WorkerApiController {

    private final AdWorkerService workers;
    private final WorkerAnalysisService analysis;

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(@RequestHeader(value = "X-Worker-Key", required = false) String key) {
        return withWorker(key, w -> {
            try {
                return ResponseEntity.ok(workers.snapshot(w));
            } catch (Exception e) {
                log.warn("Worker {} snapshot failed: {}", w.name(), e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Không lấy được số liệu: " + e.getMessage()));
            }
        });
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestHeader(value = "X-Worker-Key", required = false) String key,
                                       @RequestBody(required = false) Map<String, Object> body) {
        return withWorker(key, w -> {
            Map<String, Object> b = body == null ? Map.of() : body;
            workers.touch(w, str(b.get("device")), str(b.get("status")));
            return ResponseEntity.ok(Map.of("ok", true));
        });
    }

    @PostMapping("/reports")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> reports(@RequestHeader(value = "X-Worker-Key", required = false) String key,
                                     @RequestBody Map<String, Object> body) {
        return withWorker(key, w -> {
            Object list = body.get("reports");
            if (!(list instanceof List<?> l)) {
                return ResponseEntity.badRequest().body(Map.of("error", "reports phải là danh sách"));
            }
            int added = workers.saveReports(w, (List<Map<String, Object>>) l);
            return ResponseEntity.ok(Map.of("ok", true, "added", added));
        });
    }

    /** 12h / 21h: the server builds the context from the worker's memory, asks Claude, and files the result. */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestHeader(value = "X-Worker-Key", required = false) String key,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return withWorker(key, w -> {
            try {
                return ResponseEntity.ok(analysis.analyze(w, str(body == null ? null : body.get("slot"))));
            } catch (Exception e) {
                log.warn("Worker {} analysis failed: {}", w.name(), e.getMessage());
                return ResponseEntity.ok(Map.of("ok", false, "error", "Lỗi khi phân tích: " + e.getMessage()));
            }
        });
    }

    /** 23:30: end-of-day summary against the targets, no AI. */
    @PostMapping("/daily-close")
    public ResponseEntity<?> dailyClose(@RequestHeader(value = "X-Worker-Key", required = false) String key) {
        return withWorker(key, w -> {
            try {
                return ResponseEntity.ok(analysis.dailyClose(w));
            } catch (Exception e) {
                log.warn("Worker {} daily close failed: {}", w.name(), e.getMessage());
                return ResponseEntity.ok(Map.of("ok", false, "error", "Lỗi khi chốt ngày: " + e.getMessage()));
            }
        });
    }

    private ResponseEntity<?> withWorker(String key, Function<AdWorkerService.Worker, ResponseEntity<?>> action) {
        Optional<AdWorkerService.Worker> w = workers.authenticate(key);
        if (w.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Khóa worker không hợp lệ"));
        }
        return action.apply(w.get());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
