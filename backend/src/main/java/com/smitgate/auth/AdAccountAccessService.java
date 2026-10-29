package com.smitgate.auth;

import com.smitgate.connector.ads.AdAccount;
import com.smitgate.connector.ads.AdAccountRepository;
import com.smitgate.connector.ads.CampaignRepository;
import com.smitgate.connector.ads.MetaAdsConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Restricts STAFF users to the ad accounts assigned to them. Admins (ADMIN/ANALYST) are unrestricted:
 * every "allowed" lookup returns null for them, meaning "no filter".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdAccountAccessService {

    private static final String DENIED = "Bạn không có quyền truy cập tài khoản quảng cáo này";

    private final UserRepository userRepository;
    private final UserAdAccountRepository userAdAccountRepository;
    private final AdAccountRepository adAccountRepository;
    private final CampaignRepository campaignRepository;
    private final MetaAdsConnector metaAdsConnector;

    /** Meta object id (campaign/adset/ad) → owning "act_..." id; ownership never changes. */
    private final Map<String, String> objectAccountCache = new ConcurrentHashMap<>();

    public boolean isStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + User.Role.STAFF.name()).equals(a.getAuthority()));
    }

    /** Internal ad account ids the current user may see, or null when unrestricted. */
    public Set<Long> allowedAccountIds(Long tenantId) {
        if (!isStaff()) return null;
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email)
                .filter(u -> Objects.equals(u.getTenantId(), tenantId))
                .filter(u -> u.getStatus() == null || "ACTIVE".equals(u.getStatus()))
                .orElse(null);
        if (user == null) return Set.of();
        return userAdAccountRepository.findByUserId(user.getId()).stream()
                .map(UserAdAccount::getAdAccountId)
                .collect(Collectors.toSet());
    }

    public List<AdAccount> visibleAccounts(Long tenantId) {
        List<AdAccount> all = adAccountRepository.findByTenantId(tenantId);
        Set<Long> allowed = allowedAccountIds(tenantId);
        if (allowed == null) return all;
        return all.stream().filter(a -> allowed.contains(a.getId())).toList();
    }

    /** "act_..." ids the current user may see, or null when unrestricted. */
    public Set<String> allowedExternalIds(Long tenantId) {
        if (!isStaff()) return null;
        return visibleAccounts(tenantId).stream()
                .map(a -> normalize(a.getExternalAccountId()))
                .collect(Collectors.toSet());
    }

    public void requireAdAccount(Long tenantId, String externalAccountId) {
        Set<String> allowed = allowedExternalIds(tenantId);
        if (allowed != null && !allowed.contains(normalize(externalAccountId))) {
            throw new AccessDeniedException(DENIED);
        }
    }

    public void requireCampaign(Long tenantId, Long campaignId) {
        Set<Long> allowed = allowedAccountIds(tenantId);
        if (allowed == null) return;
        Long accountId = campaignRepository.findById(campaignId)
                .filter(c -> Objects.equals(c.getTenantId(), tenantId))
                .map(c -> c.getAdAccountId())
                .orElse(null);
        if (accountId == null || !allowed.contains(accountId)) {
            throw new AccessDeniedException(DENIED);
        }
    }

    /** For Meta campaign/adset/ad ids: looks up the owning ad account on Meta and checks it. */
    public void requireMetaObject(Long tenantId, String objectId) {
        if (!isStaff()) return;
        if (objectId == null || objectId.isBlank()) throw new AccessDeniedException(DENIED);
        List<AdAccount> visible = visibleAccounts(tenantId);
        Set<String> allowed = visible.stream().map(a -> normalize(a.getExternalAccountId())).collect(Collectors.toSet());

        String owner = objectAccountCache.get(objectId);
        if (owner == null) {
            Set<Long> dataSourceIds = visible.stream().map(AdAccount::getDataSourceId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (Long dataSourceId : dataSourceIds) {
                try {
                    owner = metaAdsConnector.getObjectAccountId(tenantId, dataSourceId, objectId);
                    if (owner != null) break;
                } catch (Exception e) {
                    log.debug("Owner lookup for {} via dataSource {} failed: {}", objectId, dataSourceId, e.getMessage());
                }
            }
            if (owner != null) {
                if (objectAccountCache.size() > 20_000) objectAccountCache.clear();
                objectAccountCache.put(objectId, owner);
            }
        }
        if (owner == null || !allowed.contains(owner)) {
            throw new AccessDeniedException(DENIED);
        }
    }

    private static String normalize(String externalAccountId) {
        if (externalAccountId == null) return "";
        String id = externalAccountId.trim();
        return id.startsWith("act_") ? id : "act_" + id;
    }
}
