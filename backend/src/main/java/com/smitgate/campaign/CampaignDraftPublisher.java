package com.smitgate.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.connector.ads.AdAccount;
import com.smitgate.connector.ads.AdAccountRepository;
import com.smitgate.connector.ads.MetaAdsConnector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes a campaign draft as a real (PAUSED) campaign/ad-set/ad on Meta, reusing the
 * tenant's already-connected Meta Ads OAuth token. Everything is created PAUSED — nothing
 * spends until a human activates it in Ads Manager.
 */
@Service
@RequiredArgsConstructor
public class CampaignDraftPublisher {

    private static final Map<String, String> OBJECTIVE_MAP = Map.of(
            "Nhận diện thương hiệu", "OUTCOME_AWARENESS",
            "Lưu lượng truy cập", "OUTCOME_TRAFFIC",
            "Lượt tương tác", "OUTCOME_ENGAGEMENT",
            "Khách hàng tiềm năng", "OUTCOME_LEADS",
            "Lượt cài đặt ứng dụng", "OUTCOME_APP_PROMOTION",
            "Doanh số bán hàng", "OUTCOME_SALES"
    );

    private final CampaignDraftService campaignDraftService;
    private final CampaignDraftRepository campaignDraftRepository;
    private final AdAccountRepository adAccountRepository;
    private final MetaAdsConnector metaAdsConnector;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public Map<String, Object> publish(Long tenantId, Long draftId) {
        CampaignDraft draft = campaignDraftService.getByIdAndTenant(draftId, tenantId);
        Map<String, Object> payload = parsePayload(draft.getPayloadJson());

        Map<String, Object> campaign = (Map<String, Object>) payload.computeIfAbsent("campaign", k -> new LinkedHashMap<>());
        String adAccountRowIdRaw = String.valueOf(campaign.getOrDefault("adAccountId", "")).trim();
        if (adAccountRowIdRaw.isBlank()) {
            throw new IllegalArgumentException("Chưa chọn Tài khoản QC cho chiến dịch này");
        }
        Long adAccountRowId;
        try {
            adAccountRowId = Long.valueOf(adAccountRowIdRaw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Tài khoản QC đã chọn không hợp lệ");
        }
        AdAccount adAccount = adAccountRepository.findByIdAndTenantId(adAccountRowId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản QC đã chọn — có thể đã bị gỡ kết nối"));

        List<Map<String, Object>> adGroups = resolveAdGroups(payload);
        if (adGroups.isEmpty()) {
            throw new IllegalArgumentException("Chiến dịch chưa có nhóm quảng cáo nào");
        }

        String metaObjective = OBJECTIVE_MAP.getOrDefault(draft.getObjective(), "OUTCOME_ENGAGEMENT");
        boolean campaignOwnsBudget = "Ngân sách chiến dịch".equals(campaign.get("budgetLevel"));

        Long campaignBudget = null;
        if (campaignOwnsBudget) {
            campaignBudget = parseLong(adGroups.get(0).get("dailyBudget"));
            if (campaignBudget == null || campaignBudget <= 0) {
                throw new IllegalArgumentException("Ngân sách chiến dịch (lấy từ nhóm quảng cáo đầu tiên) chưa hợp lệ");
            }
        }

        String metaCampaignId;
        try {
            metaCampaignId = metaAdsConnector.createCampaign(
                    tenantId, adAccount.getDataSourceId(), adAccount.getExternalAccountId(),
                    draft.getName(), metaObjective, campaignBudget);
        } catch (Exception e) {
            throw new RuntimeException("Không tạo được chiến dịch trên Meta: " + e.getMessage());
        }
        campaign.put("metaCampaignId", metaCampaignId);
        campaign.put("metaAdAccountExternalId", adAccount.getExternalAccountId());

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> group : adGroups) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", group.get("name"));
            try {
                String pageId = trimToNull(String.valueOf(group.getOrDefault("pageId", "")));
                if (pageId == null) {
                    throw new IllegalArgumentException("Chưa chọn Trang (cần Facebook Page ID) cho nhóm này");
                }
                int ageMin = parseInt(group.get("minAge"), 18);
                Integer ageMax = parseIntOrNull(group.get("maxAge"));
                String gendersOption = String.valueOf(group.getOrDefault("gender", "Tất cả"));

                Long adSetBudget = null;
                if (!campaignOwnsBudget) {
                    adSetBudget = parseLong(group.get("dailyBudget"));
                    if (adSetBudget == null || adSetBudget <= 0) {
                        throw new IllegalArgumentException("Ngân sách hàng ngày chưa hợp lệ");
                    }
                }

                String startTimeIso = toMetaIsoTime(String.valueOf(group.getOrDefault("startDate", "")));

                String metaAdSetId = metaAdsConnector.createAdSet(
                        tenantId, adAccount.getDataSourceId(), adAccount.getExternalAccountId(), metaCampaignId,
                        String.valueOf(group.getOrDefault("name", draft.getName())),
                        adSetBudget, ageMin, ageMax, gendersOption, pageId, startTimeIso);
                group.put("metaAdSetId", metaAdSetId);
                result.put("metaAdSetId", metaAdSetId);

                Map<String, Object> ad = (Map<String, Object>) group.computeIfAbsent("ad", k -> new LinkedHashMap<>());
                String existingAdId = trimToNull(String.valueOf(ad.getOrDefault("adId", "")));
                if (existingAdId == null) {
                    result.put("status", "PARTIAL");
                    result.put("warning", "Đã tạo nhóm quảng cáo nhưng chưa tạo quảng cáo vì thiếu ID quảng cáo mẫu để lấy nội dung");
                } else {
                    String creativeId = metaAdsConnector.fetchAdCreativeId(tenantId, adAccount.getDataSourceId(), existingAdId);
                    String metaAdId = metaAdsConnector.createAd(
                            tenantId, adAccount.getDataSourceId(), adAccount.getExternalAccountId(), metaAdSetId,
                            String.valueOf(ad.getOrDefault("name", group.get("name"))), creativeId);
                    ad.put("metaAdId", metaAdId);
                    result.put("metaAdId", metaAdId);
                    result.put("status", "SUCCESS");
                }
            } catch (Exception e) {
                result.put("status", "FAILED");
                result.put("error", e.getMessage());
            }
            results.add(result);
        }

        payload.put("adGroups", adGroups);
        draft.setPayloadJson(writeJson(payload));
        campaignDraftRepository.save(draft);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metaCampaignId", metaCampaignId);
        response.put("adAccountExternalId", adAccount.getExternalAccountId());
        response.put("results", results);
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resolveAdGroups(Map<String, Object> payload) {
        Object raw = payload.get("adGroups");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) raw;
        }
        // Backward-compat: older drafts stored a single adGroup/ad pair instead of an array.
        Object singleGroup = payload.get("adGroup");
        if (singleGroup instanceof Map<?, ?> groupMap) {
            Map<String, Object> group = new LinkedHashMap<>((Map<String, Object>) groupMap);
            Object ad = payload.get("ad");
            group.put("ad", ad instanceof Map<?, ?> adMap ? new LinkedHashMap<>((Map<String, Object>) adMap) : new LinkedHashMap<>());
            List<Map<String, Object>> wrapped = new ArrayList<>();
            wrapped.add(group);
            return wrapped;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Dữ liệu chiến dịch không hợp lệ, không thể đăng lên Meta");
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equals(trimmed) ? null : trimmed;
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private int parseInt(Object value, int fallback) {
        Integer parsed = parseIntOrNull(value);
        return parsed != null ? parsed : fallback;
    }

    private Integer parseIntOrNull(Object value) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts a `datetime-local` value ("2026-09-16T16:50") to Meta's ISO8601+offset format, or null if blank/past. */
    private String toMetaIsoTime(String datetimeLocal) {
        if (datetimeLocal == null || datetimeLocal.isBlank()) return null;
        try {
            LocalDateTime dt = LocalDateTime.parse(datetimeLocal);
            if (dt.isBefore(LocalDateTime.now())) return null;
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "+0700";
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không thể lưu lại dữ liệu chiến dịch sau khi đăng");
        }
    }
}
