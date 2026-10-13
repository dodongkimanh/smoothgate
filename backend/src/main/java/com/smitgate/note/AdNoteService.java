package com.smitgate.note;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdNoteService {

    private final AdNoteRepository adNoteRepository;

    public List<AdNote> listNotes(Long tenantId, String adId) {
        return adNoteRepository.findByTenantIdAndAdIdOrderByCreatedAtAsc(tenantId, adId);
    }

    public AdNote addNote(Long tenantId, String adId, String content, String author) {
        AdNote note = new AdNote();
        note.setTenantId(tenantId);
        note.setAdId(adId);
        note.setContent(content);
        note.setAuthor(author);
        return adNoteRepository.save(note);
    }

    public Map<String, Long> countsForAds(Long tenantId, List<String> adIds) {
        Map<String, Long> counts = new HashMap<>();
        if (adIds == null || adIds.isEmpty()) {
            return counts;
        }
        List<String> distinctIds = adIds.stream().distinct().toList();
        adNoteRepository.countByTenantIdAndAdIdIn(tenantId, distinctIds)
                .forEach(row -> counts.put(row.getAdId(), row.getCnt()));
        return counts;
    }
}
