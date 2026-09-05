package com.smitgate.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
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
@RequestMapping("/api/campaign-drafts")
@RequiredArgsConstructor
public class CampaignDraftController {

    private final CampaignDraftService campaignDraftService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<Map<String, Object>> drafts = campaignDraftService.listByTenant(tenantId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(drafts));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        return ResponseEntity.ok(ApiResponse.ok(toView(campaignDraftService.getByIdAndTenant(id, tenantId))));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        CampaignDraft draft = campaignDraftService.create(
                tenantId,
                (String) body.get("name"),
                (String) body.get("objective"),
                (String) body.get("status"),
                (Map<String, Object>) body.get("payload"));
        return ResponseEntity.ok(ApiResponse.ok(toView(draft)));
    }

    @PutMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        CampaignDraft draft = campaignDraftService.update(
                tenantId,
                id,
                (String) body.get("name"),
                (String) body.get("objective"),
                (String) body.get("status"),
                (Map<String, Object>) body.get("payload"));
        return ResponseEntity.ok(ApiResponse.ok(toView(draft)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(HttpServletRequest request, @PathVariable Long id) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        campaignDraftService.delete(tenantId, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private Map<String, Object> toView(CampaignDraft draft) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", draft.getId());
        view.put("name", draft.getName());
        view.put("objective", draft.getObjective());
        view.put("status", draft.getStatus());
        view.put("payload", parsePayload(draft.getPayloadJson()));
        view.put("createdAt", draft.getCreatedAt());
        view.put("updatedAt", draft.getUpdatedAt());
        return view;
    }

    private Object parsePayload(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
