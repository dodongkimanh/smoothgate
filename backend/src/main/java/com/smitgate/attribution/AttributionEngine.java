package com.smitgate.attribution;

import com.smitgate.connector.ads.AdAccount;
import com.smitgate.connector.ads.AdAccountRepository;
import com.smitgate.connector.ads.Campaign;
import com.smitgate.connector.ads.CampaignRepository;
import com.smitgate.connector.ads.MetaAdsConnector;
import com.smitgate.connector.pos.Order;
import com.smitgate.connector.pos.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * UTM-based last-touch attribution engine (Phase 1).
 * Matches orders to campaigns by utm_campaign → campaign.name or external_campaign_id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttributionEngine {

    private final OrderRepository orderRepository;
    private final CampaignRepository campaignRepository;
    private final OrderAttributionRepository attributionRepository;
    private final AdAccountRepository adAccountRepository;
    private final MetaAdsConnector metaAdsConnector;

    @Value("${app.sync.attribution.batch-size:100}")
    private int batchSize;

    public int attributeUnmatched(Long tenantId) {
        List<Campaign> campaigns = campaignRepository.findByTenantId(tenantId);
        if (campaigns.isEmpty()) {
            log.debug("No campaigns for tenant {}, attribution may be UNKNOWN", tenantId);
        }

        Map<String, Campaign> byName = new HashMap<>();
        Map<String, Campaign> byExternalId = new HashMap<>();
        for (Campaign c : campaigns) {
            if (c.getName() != null && !c.getName().isBlank()) {
                byName.put(normalize(c.getName()), c);
            }
            if (c.getExternalCampaignId() != null && !c.getExternalCampaignId().isBlank()) {
                byExternalId.put(normalize(c.getExternalCampaignId()), c);
            }
        }
        Map<String, Campaign> byAdId = buildMetaAdIdLookup(tenantId, byName, byExternalId);

        int changed = 0;
        long lastSeenOrderId = 0L;
        int safeBatchSize = Math.max(20, Math.min(batchSize, 300));

        while (true) {
            List<OrderRepository.UnattributedOrderProjection> ordersBatch = orderRepository.findUnattributedBatch(
                    tenantId,
                    lastSeenOrderId,
                    PageRequest.of(0, safeBatchSize)
            );
            if (ordersBatch.isEmpty()) {
                break;
            }

            List<OrderAttribution> batch = new ArrayList<>(ordersBatch.size());
            for (OrderRepository.UnattributedOrderProjection order : ordersBatch) {
                OrderAttribution attr = buildAttribution(tenantId, order, byName, byExternalId, byAdId);
                batch.add(attr);
                if (order.getId() != null) {
                    lastSeenOrderId = order.getId();
                }
            }

            if (!batch.isEmpty()) {
                attributionRepository.saveAll(batch);
                changed += batch.size();
            }
        }

        // Reprocess unresolved rows (UNKNOWN or campaignId=null) to reduce noise in production reporting.
        List<OrderAttribution> unresolvedAttrs = new ArrayList<>();
        unresolvedAttrs.addAll(attributionRepository.findByTenantIdAndMatchType(
                tenantId, OrderAttribution.MatchType.UNKNOWN));
        unresolvedAttrs.addAll(attributionRepository.findByTenantIdAndCampaignIdIsNull(tenantId));

        Map<Long, OrderAttribution> dedup = new HashMap<>();
        for (OrderAttribution attr : unresolvedAttrs) {
            dedup.putIfAbsent(attr.getOrderId(), attr);
        }

        if (!dedup.isEmpty()) {
            Set<Long> orderIds = dedup.values().stream().map(OrderAttribution::getOrderId).collect(Collectors.toSet());
            Map<Long, OrderRepository.OrderLightProjection> orderById = orderRepository.findLightByIds(orderIds).stream()
                    .collect(Collectors.toMap(OrderRepository.OrderLightProjection::getId, o -> o));

            List<OrderAttribution> toUpgrade = new ArrayList<>();
            for (OrderAttribution attr : dedup.values()) {
                OrderRepository.OrderLightProjection order = orderById.get(attr.getOrderId());
                if (order == null) {
                    continue;
                }
                AttributionDecision decision = resolveDecision(
                        order.getClickId(),
                        order.getUtmCampaign(),
                        order.getUtmSource(),
                        byName,
                        byExternalId,
                        byAdId);
                if (decision.matchType == OrderAttribution.MatchType.UNKNOWN || decision.campaignId == null) {
                    continue;
                }

                attr.setMatchType(decision.matchType);
                attr.setPlatform(decision.platform);
                attr.setCampaignId(decision.campaignId);
                toUpgrade.add(attr);
            }

            if (!toUpgrade.isEmpty()) {
                attributionRepository.saveAll(toUpgrade);
                changed += toUpgrade.size();
            }
        }

        return changed;
    }

    private Campaign findCampaignByUtm(Map<String, Campaign> byName,
                                       Map<String, Campaign> byExternalId,
                                       String utmCampaign) {
        String normalized = normalize(utmCampaign);
        if (normalized.isBlank()) {
            return null;
        }
        Campaign byNameMatch = byName.get(normalized);
        if (byNameMatch != null) {
            return byNameMatch;
        }
        return byExternalId.get(normalized);
    }

    private Campaign findCampaignByClickId(Map<String, Campaign> byExternalId, String clickId) {
        String normalized = normalize(clickId);
        if (normalized.isBlank()) {
            return null;
        }
        Campaign exact = byExternalId.get(normalized);
        if (exact != null) {
            return exact;
        }
        // Fallback for noisy payloads that include extra prefixes/suffixes around campaign id.
        for (Map.Entry<String, Campaign> entry : byExternalId.entrySet()) {
            if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
                return entry.getValue();
            }
        }

        // In production some orders store Meta ad_id/adset_id in clickId instead of campaign_id.
        // Meta IDs are often very similar, so use a conservative fuzzy match as a last resort.
        if (isLikelyMetaNumericId(normalized)) {
            Campaign bestCampaign = null;
            int bestDistance = Integer.MAX_VALUE;

            for (Map.Entry<String, Campaign> entry : byExternalId.entrySet()) {
                String candidate = entry.getKey();
                if (!isLikelyMetaNumericId(candidate) || candidate.length() != normalized.length()) {
                    continue;
                }
                if (commonPrefixLength(normalized, candidate) < 8) {
                    continue;
                }
                if (commonSuffixLength(normalized, candidate) < 3) {
                    continue;
                }

                int distance = hammingDistance(normalized, candidate);
                if (distance <= 2 && distance < bestDistance) {
                    bestDistance = distance;
                    bestCampaign = entry.getValue();
                }
            }

            if (bestCampaign != null) {
                return bestCampaign;
            }
        }

        return null;
    }

    private boolean isLikelyMetaNumericId(String value) {
        if (value == null || value.length() < 12) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private int commonSuffixLength(String a, String b) {
        int i = a.length() - 1;
        int j = b.length() - 1;
        int count = 0;
        while (i >= 0 && j >= 0 && a.charAt(i) == b.charAt(j)) {
            count++;
            i--;
            j--;
        }
        return count;
    }

    private int hammingDistance(String a, String b) {
        if (a.length() != b.length()) {
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                distance++;
            }
        }
        return distance;
    }

    private Map<String, Campaign> buildMetaAdIdLookup(Long tenantId,
                                                       Map<String, Campaign> byName,
                                                       Map<String, Campaign> byExternalId) {
        Map<String, Campaign> byAdId = new HashMap<>();
        List<AdAccount> metaAccounts = adAccountRepository.findByTenantId(tenantId).stream()
                .filter(a -> a.getPlatform() == AdAccount.Platform.META)
                .filter(a -> a.getDataSourceId() != null)
                .toList();

        for (AdAccount account : metaAccounts) {
            try {
                Map<String, String> campaignNames = new HashMap<>();
                List<Map<String, Object>> campaigns = metaAdsConnector.listCampaignHierarchy(
                        tenantId, account.getDataSourceId(), account.getExternalAccountId());
                for (Map<String, Object> row : campaigns) {
                    String extCampaignId = Objects.toString(row.get("id"), "").trim();
                    if (extCampaignId.isBlank()) {
                        continue;
                    }
                    String campaignName = Objects.toString(row.get("name"), "").trim();
                    campaignNames.put(extCampaignId, campaignName);
                }

                List<Map<String, Object>> ads = metaAdsConnector.listAdHierarchy(
                        tenantId, account.getDataSourceId(), account.getExternalAccountId(), null, null);

                for (Map<String, Object> row : ads) {
                    String adId = Objects.toString(row.get("id"), "").trim();
                    String extCampaignId = Objects.toString(row.get("campaignId"), "").trim();
                    if (adId.isBlank() || extCampaignId.isBlank()) {
                        continue;
                    }

                    Campaign campaign = byExternalId.get(normalize(extCampaignId));
                    if (campaign == null) {
                        campaign = upsertMetaCampaign(
                                tenantId,
                                account,
                                extCampaignId,
                                campaignNames.getOrDefault(extCampaignId, "Meta Campaign " + extCampaignId)
                        );
                        byExternalId.put(normalize(extCampaignId), campaign);
                        if (campaign.getName() != null && !campaign.getName().isBlank()) {
                            byName.putIfAbsent(normalize(campaign.getName()), campaign);
                        }
                    }

                    byAdId.put(normalize(adId), campaign);
                }
            } catch (Exception e) {
                log.warn("Skip Meta ad lookup for account {} (tenant {}): {}",
                        account.getExternalAccountId(), tenantId, e.getMessage());
            }
        }

        return byAdId;
    }

    private Campaign upsertMetaCampaign(Long tenantId,
                                        AdAccount account,
                                        String extCampaignId,
                                        String campaignName) {
        Campaign existing = campaignRepository.findByTenantIdAndPlatformAndExternalCampaignId(
            tenantId, Campaign.Platform.META, extCampaignId).orElse(null);
        if (existing != null) {
            return existing;
        }

        Campaign campaign = new Campaign();
        campaign.setTenantId(tenantId);
        campaign.setAdAccountId(account.getId());
        campaign.setPlatform(Campaign.Platform.META);
        campaign.setExternalCampaignId(extCampaignId);
        campaign.setName(campaignName == null || campaignName.isBlank() ? "Meta Campaign " + extCampaignId : campaignName);
        campaign.setStatus("UNKNOWN");

        try {
            return campaignRepository.save(campaign);
        } catch (Exception ex) {
            Campaign fallback = campaignRepository.findByTenantIdAndPlatformAndExternalCampaignId(
                    tenantId, Campaign.Platform.META, extCampaignId).orElse(null);
            if (fallback != null) {
                return fallback;
            }
            throw ex;
        }
    }

    private OrderAttribution buildAttribution(Long tenantId,
                                              OrderRepository.UnattributedOrderProjection order,
                                              Map<String, Campaign> byName,
                                              Map<String, Campaign> byExternalId,
                                              Map<String, Campaign> byAdId) {
        AttributionDecision decision = resolveDecision(order.getClickId(), order.getUtmCampaign(), order.getUtmSource(), byName, byExternalId, byAdId);
        OrderAttribution attr = new OrderAttribution();
        attr.setTenantId(tenantId);
        attr.setOrderId(order.getId());
        attr.setPlatform(decision.platform);
        attr.setCampaignId(decision.campaignId);
        attr.setMatchType(decision.matchType);
        return attr;
    }

    private OrderAttribution buildAttribution(Long tenantId,
                                              Order order,
                                              Map<String, Campaign> byName,
                                              Map<String, Campaign> byExternalId,
                                              Map<String, Campaign> byAdId) {
        AttributionDecision decision = resolveDecision(order.getClickId(), order.getUtmCampaign(), order.getUtmSource(), byName, byExternalId, byAdId);
        OrderAttribution attr = new OrderAttribution();
        attr.setTenantId(tenantId);
        attr.setOrderId(order.getId());
        attr.setPlatform(decision.platform);
        attr.setCampaignId(decision.campaignId);
        attr.setMatchType(decision.matchType);
        return attr;
    }

    private AttributionDecision resolveDecision(String clickId,
                                                String utmCampaign,
                                                String utmSource,
                                                Map<String, Campaign> byName,
                                                Map<String, Campaign> byExternalId,
                                                Map<String, Campaign> byAdId) {
        Campaign byClick = findCampaignByClickId(byExternalId, clickId);
        if (byClick != null) {
            return new AttributionDecision(byClick.getId(), toPlatform(byClick.getPlatform()), OrderAttribution.MatchType.CLICK_ID);
        }

        Campaign byAd = byAdId.get(normalize(clickId));
        if (byAd != null) {
            return new AttributionDecision(byAd.getId(), toPlatform(byAd.getPlatform()), OrderAttribution.MatchType.CLICK_ID);
        }

        Campaign byUtm = findCampaignByUtm(byName, byExternalId, utmCampaign);
        if (byUtm != null) {
            return new AttributionDecision(byUtm.getId(), toPlatform(byUtm.getPlatform()), OrderAttribution.MatchType.UTM);
        }

        if (utmCampaign != null && !utmCampaign.isBlank()) {
            return new AttributionDecision(null, guessplatformFromUtm(utmSource), OrderAttribution.MatchType.UTM);
        }

        return new AttributionDecision(null, guessplatformFromUtm(utmSource), OrderAttribution.MatchType.UNKNOWN);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase()
                .replace("act_", "")
                .replace("-", "")
                .replace("_", "")
                .replaceAll("\\s+", "");
    }

    private OrderAttribution.Platform toPlatform(Campaign.Platform p) {
        return switch (p) {
            case META -> OrderAttribution.Platform.META;
            case GOOGLE -> OrderAttribution.Platform.GOOGLE;
            case TIKTOK -> OrderAttribution.Platform.TIKTOK;
        };
    }

    private OrderAttribution.Platform guessplatformFromUtm(String utmSource) {
        if (utmSource == null) return OrderAttribution.Platform.UNKNOWN;
        String src = utmSource.toLowerCase();
        if (src.contains("facebook") || src.contains("fb") || src.contains("meta")) {
            return OrderAttribution.Platform.META;
        }
        if (src.contains("google")) return OrderAttribution.Platform.GOOGLE;
        if (src.contains("tiktok")) return OrderAttribution.Platform.TIKTOK;
        return OrderAttribution.Platform.UNKNOWN;
    }

    private record AttributionDecision(Long campaignId,
                                       OrderAttribution.Platform platform,
                                       OrderAttribution.MatchType matchType) {
    }
}
