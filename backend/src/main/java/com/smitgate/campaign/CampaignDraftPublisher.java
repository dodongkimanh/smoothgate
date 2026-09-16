package com.smitgate.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.connector.ads.AdAccount;
import com.smitgate.connector.ads.AdAccountRepository;
import com.smitgate.connector.ads.MetaAdsConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
@Slf4j
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

    // Maps the "Mục tiêu hiệu quả" dropdown label to Meta's optimization_goal enum for
    // message-destination ad sets. The two purchase-related goals need Meta's dedicated
    // messaging-purchase optimization goal, not the generic "Conversations" one.
    private static final Map<String, String> OPTIMIZATION_GOAL_MAP = Map.of(
            "Tối đa hóa số cuộc trò chuyện", "CONVERSATIONS",
            "Tối đa hóa số khách hàng tiềm năng qua tin nhắn", "LEAD_GENERATION",
            "Tối đa hóa số lượt mua qua tin nhắn", "MESSAGING_PURCHASE_CONVERSION",
            "Tối đa hóa giá trị của lượt mua qua tin nhắn", "MESSAGING_PURCHASE_CONVERSION"
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
                if (!pageId.matches("\\d+")) {
                    throw new IllegalArgumentException(
                            "Trang đã chọn chưa có ID Facebook hợp lệ (\"" + pageId + "\") — mở lại form, chọn lại Trang từ danh sách rồi lưu.");
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

                boolean advantageAudience = parseBool(group.get("advantageAudience"), true);
                String optimizationGoal = OPTIMIZATION_GOAL_MAP.getOrDefault(
                        String.valueOf(group.get("performanceGoal")), "CONVERSATIONS");
                String devicePlatformOption = String.valueOf(group.getOrDefault("devicePlatform", "Tất cả"));
                List<String> customAudienceIds = parseCustomAudienceIds(group.get("customAudienceIds"));
                String metaAdSetId = metaAdsConnector.createAdSet(
                        tenantId, adAccount.getDataSourceId(), adAccount.getExternalAccountId(), metaCampaignId,
                        String.valueOf(group.getOrDefault("name", draft.getName())),
                        adSetBudget, ageMin, ageMax, gendersOption, pageId, startTimeIso, advantageAudience,
                        optimizationGoal, devicePlatformOption, customAudienceIds);
                group.put("metaAdSetId", metaAdSetId);
                result.put("metaAdSetId", metaAdSetId);

                Map<String, Object> ad = (Map<String, Object>) group.computeIfAbsent("ad", k -> new LinkedHashMap<>());
                String existingPostId = trimToNull(String.valueOf(ad.getOrDefault("postId", "")));
                if (existingPostId == null) {
                    result.put("status", "PARTIAL");
                    result.put("warning", "Đã tạo nhóm quảng cáo nhưng chưa tạo quảng cáo vì thiếu ID bài viết có sẵn");
                } else {
                    String adName = String.valueOf(ad.getOrDefault("name", group.get("name")));
                    String creativeId = metaAdsConnector.createAdCreativeFromExistingPost(
                            tenantId, adAccount.getDataSourceId(), adAccount.getExternalAccountId(),
                            pageId, existingPostId, adName);
                    String metaAdId = metaAdsConnector.createAd(
                            tenantId, adAccount.getDataSourceId(), adAccount.getExternalAccountId(), metaAdSetId,
                            adName, creativeId);
                    ad.put("metaAdId", metaAdId);
                    result.put("metaAdId", metaAdId);
                    result.put("status", "SUCCESS");
                }
            } catch (Exception e) {
                log.error("Publish failed for ad group '{}' of draft {} (tenant {}): {}",
                        group.get("name"), draft.getId(), tenantId, e.getMessage());
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

    private boolean parseBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value == null) return fallback;
        String s = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return fallback;
    }

    /** Parses a comma-separated list of real Meta custom audience IDs, ignoring blanks. */
    private List<String> parseCustomAudienceIds(Object value) {
        if (value == null) return List.of();
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equals(raw)) return List.of();
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                if (!id.matches("\\d+")) {
                    throw new IllegalArgumentException("ID đối tượng tùy chỉnh không hợp lệ: \"" + id + "\" (phải là số)");
                }
                ids.add(id);
            }
        }
        return ids;
    }

    /** Converts a `datetime-local` value ("2026-09-16T16:50") to Meta's ISO8601+offset format, or null if blank/past. */
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * The `datetime-local` value from the browser is wall-clock time in the user's own timezone
     * (Vietnam), with no offset attached. Comparing it against LocalDateTime.now() would compare
     * against the SERVER's default timezone (UTC in this container) — up to 7 hours off. Anchor
     * both sides to Asia/Ho_Chi_Minh explicitly before comparing or formatting.
     */
    private String toMetaIsoTime(String datetimeLocal) {
        if (datetimeLocal == null || datetimeLocal.isBlank()) {
            log.info("toMetaIsoTime: input blank/null, no start_time will be sent (raw='{}')", datetimeLocal);
            return null;
        }
        try {
            LocalDateTime dt = LocalDateTime.parse(datetimeLocal);
            ZonedDateTime zoned = dt.atZone(VN_ZONE);
            ZonedDateTime now = ZonedDateTime.now(VN_ZONE);
            if (zoned.isBefore(now)) {
                log.info("toMetaIsoTime: raw='{}' parsed to {} which is BEFORE now={} -> dropping start_time",
                        datetimeLocal, zoned, now);
                return null;
            }
            // Meta's Graph API expects a colon in the UTC offset ("+07:00"), not "+0700" —
            // a bare "+0700" appears to be silently ignored rather than rejected with an error.
            String result = zoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
            log.info("toMetaIsoTime: raw='{}' -> sending start_time='{}'", datetimeLocal, result);
            return result;
        } catch (Exception e) {
            log.warn("toMetaIsoTime: failed to parse raw='{}': {}", datetimeLocal, e.toString());
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
