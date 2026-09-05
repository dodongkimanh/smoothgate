package com.smitgate.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CampaignDraftService {

    private final CampaignDraftRepository campaignDraftRepository;
    private final ObjectMapper objectMapper;

    public List<CampaignDraft> listByTenant(Long tenantId) {
        return campaignDraftRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    public CampaignDraft getByIdAndTenant(Long id, Long tenantId) {
        return campaignDraftRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chiến dịch"));
    }

    public CampaignDraft create(Long tenantId, String name, String objective, String status, Map<String, Object> payload) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên chiến dịch không được để trống");
        }
        CampaignDraft draft = new CampaignDraft();
        draft.setTenantId(tenantId);
        draft.setName(name);
        draft.setObjective(objective);
        draft.setStatus(status != null && !status.isBlank() ? status : "DRAFT");
        draft.setPayloadJson(toJson(payload));
        return campaignDraftRepository.save(draft);
    }

    public CampaignDraft update(Long tenantId, Long id, String name, String objective, String status, Map<String, Object> payload) {
        CampaignDraft draft = getByIdAndTenant(id, tenantId);
        if (name != null && !name.isBlank()) draft.setName(name);
        if (objective != null) draft.setObjective(objective);
        if (status != null && !status.isBlank()) draft.setStatus(status);
        if (payload != null) draft.setPayloadJson(toJson(payload));
        return campaignDraftRepository.save(draft);
    }

    public void delete(Long tenantId, Long id) {
        CampaignDraft draft = getByIdAndTenant(id, tenantId);
        campaignDraftRepository.delete(draft);
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Dữ liệu chiến dịch không hợp lệ");
        }
    }
}
