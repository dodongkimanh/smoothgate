package com.smitgate.note;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ad-saved-views")
@RequiredArgsConstructor
public class AdSavedViewController {

    private final AdSavedViewService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<Map<String, Object>> views = service.list(tenantId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(views));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String name = (String) body.get("name");
        List<String> adKeys = (List<String>) body.getOrDefault("adKeys", List.of());
        if (name == null || name.isBlank() || adKeys.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("name và adKeys là bắt buộc"));
        }
        AdSavedView view = service.create(tenantId, name.trim(), adKeys);
        return ResponseEntity.ok(ApiResponse.ok(toView(view)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        service.delete(tenantId, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private Map<String, Object> toView(AdSavedView view) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", view.getId());
        result.put("name", view.getName());
        result.put("adKeys", service.parseAdKeys(view));
        result.put("createdAt", view.getCreatedAt());
        return result;
    }
}
