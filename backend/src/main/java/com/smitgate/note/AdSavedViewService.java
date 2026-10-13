package com.smitgate.note;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdSavedViewService {

    private final AdSavedViewRepository repository;
    private final ObjectMapper objectMapper;

    public List<AdSavedView> list(Long tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public AdSavedView create(Long tenantId, String name, List<String> adKeys) {
        AdSavedView view = new AdSavedView();
        view.setTenantId(tenantId);
        view.setName(name);
        view.setAdKeysJson(toJson(adKeys));
        return repository.save(view);
    }

    public void delete(Long tenantId, Long id) {
        repository.findByIdAndTenantId(id, tenantId)
                .ifPresent(repository::delete);
    }

    public List<String> parseAdKeys(AdSavedView view) {
        try {
            return objectMapper.readValue(view.getAdKeysJson(), objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<String> adKeys) {
        try {
            return objectMapper.writeValueAsString(adKeys);
        } catch (Exception e) {
            return "[]";
        }
    }
}
