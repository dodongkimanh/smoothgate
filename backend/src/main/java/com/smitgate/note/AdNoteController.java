package com.smitgate.note;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ad-notes")
@RequiredArgsConstructor
public class AdNoteController {

    private final AdNoteService adNoteService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            HttpServletRequest request, @RequestParam String adId) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<Map<String, Object>> notes = adNoteService.listNotes(tenantId, adId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(notes));
    }

    @GetMapping("/counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> counts(
            HttpServletRequest request, @RequestParam String adIds) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        List<String> ids = Arrays.stream(adIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(adNoteService.countsForAds(tenantId, ids)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String adId = (String) body.get("adId");
        String content = (String) body.get("content");
        if (adId == null || adId.isBlank() || content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("adId và content là bắt buộc"));
        }
        String author = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : null;
        AdNote note = adNoteService.addNote(tenantId, adId, content, author);
        return ResponseEntity.ok(ApiResponse.ok(toView(note)));
    }

    private Map<String, Object> toView(AdNote note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", note.getId());
        view.put("adId", note.getAdId());
        view.put("content", note.getContent());
        view.put("author", note.getAuthor());
        view.put("createdAt", note.getCreatedAt());
        return view;
    }
}
