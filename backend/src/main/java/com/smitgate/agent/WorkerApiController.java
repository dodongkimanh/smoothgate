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

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestHeader(value = "X-Worker-Key", required = false) String key,
                                     @RequestBody Map<String, Object> body) {
        return withWorker(key, w -> ResponseEntity.ok(
                workers.analyze(w, str(body.get("context")), str(body.get("slot")))));
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
