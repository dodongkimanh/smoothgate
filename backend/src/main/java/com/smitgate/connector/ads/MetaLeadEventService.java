package com.smitgate.connector.ads;

import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports Messenger customers who left a phone number (found in the company CRM) to Meta as Lead events,
 * so ad sets can optimize for people who give a phone instead of people who merely open a chat.
 *
 * <p>One event per customer and Page (event_id = page + PSID), sent once. The event time is when the
 * customer messaged (CRM row time), so Meta credits the ad that started the conversation rather than an ad
 * shown to them later. Meta only accepts events up to 7 days old, so only leads from the last
 * 7 days are considered and a failed send is retried until it gets too old.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaLeadEventService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int LOOKBACK_DAYS = 7;
    private static final Duration MAX_EVENT_AGE = Duration.ofDays(7).minusHours(1);
    /** Meta accepts up to 1000 events per request. */
    private static final int BATCH = 500;

    private final CrmCustomerSource crmCustomerSource;
    private final MetaLeadEventRepository leadEventRepository;
    private final MetaPurchaseEventService eventSender;
    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;

    @Value("${app.meta-capi.leads-enabled:true}")
    private boolean enabled;

    /** End of the last scheduled run since startup (Vietnam time); null until the first run finishes. */
    private volatile LocalDateTime lastRunAt;

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    @Scheduled(
            fixedDelayString = "${app.meta-capi.fixed-delay-ms:1800000}",
            initialDelayString = "${app.meta-capi.leads-initial-delay-ms:420000}")
    public void sendPendingLeads() {
        if (!enabled || !crmCustomerSource.isConfigured()) {
            return;
        }
        Map<Long, DataSource> sourceByTenant = new LinkedHashMap<>();
        try {
            for (DataSource ds : dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE)) {
                sourceByTenant.putIfAbsent(ds.getTenantId(), ds);
            }
        } catch (Exception e) {
            log.warn("Meta lead events skipped: {}", e.getMessage());
            return;
        }
        sourceByTenant.forEach((tenantId, ds) -> {
            try {
                sendForTenant(tenantId, ds);
            } catch (Exception e) {
                log.error("Meta lead events failed for tenant {}: {}", tenantId, e.getMessage());
            }
        });
        lastRunAt = LocalDateTime.now(VIETNAM_ZONE);
    }

    void sendForTenant(Long tenantId, DataSource ds) {
        LocalDateTime now = LocalDateTime.now(VIETNAM_ZONE);
        Map<String, CrmCustomerSource.PhoneLead> leads = firstLeadPerCustomer(crmCustomerSource.phoneLeads(LOOKBACK_DAYS));

        // Record customers seen with a phone for the first time.
        Set<String> pages = new HashSet<>();
        leads.values().forEach(l -> pages.add(l.pageId()));
        Set<String> known = new HashSet<>();
        if (!pages.isEmpty()) {
            for (MetaLeadEvent e : leadEventRepository.findByTenantIdAndPageIdIn(tenantId, pages)) {
                known.add(key(e.getPageId(), e.getPsid()));
            }
        }
        List<MetaLeadEvent> fresh = new ArrayList<>();
        leads.forEach((k, lead) -> {
            if (known.contains(k)) return;
            MetaLeadEvent e = new MetaLeadEvent();
            e.setTenantId(tenantId);
            e.setPageId(lead.pageId());
            e.setPsid(lead.psid());
            e.setCrmRowId(lead.rowId());
            e.setPhoneHash(MetaPurchaseEventService.sha256(MetaPurchaseEventService.normalizePhone(lead.phone())));
            e.setAdId(adIdOf(lead.adId()));
            e.setEventTime(eventTime(lead, now));
            fresh.add(e);
        });
        if (!fresh.isEmpty()) {
            leadEventRepository.saveAll(fresh);
        }

        List<MetaLeadEvent> pending = leadEventRepository
                .findByTenantIdAndSentAtIsNullAndEventTimeAfter(tenantId, now.minus(MAX_EVENT_AGE));
        if (pending.isEmpty()) {
            return;
        }
        Map<String, List<MetaLeadEvent>> byPage = new LinkedHashMap<>();
        pending.forEach(e -> byPage.computeIfAbsent(e.getPageId(), p -> new ArrayList<>()).add(e));

        String userToken = dataSourceService.decryptSecret(ds);
        byPage.forEach((pageId, events) -> {
            for (int from = 0; from < events.size(); from += BATCH) {
                List<MetaLeadEvent> batch = events.subList(from, Math.min(events.size(), from + BATCH));
                try {
                    int received = eventSender.sendToPage(userToken, pageId, toEvents(batch, leads));
                    LocalDateTime sentAt = LocalDateTime.now(VIETNAM_ZONE);
                    batch.forEach(e -> {
                        e.setSentAt(sentAt);
                        e.setLastError(null);
                    });
                    log.info("Meta lead events: page {} sent {} accepted {}", pageId, batch.size(), received);
                } catch (Exception ex) {
                    String msg = truncate(ex.getMessage());
                    batch.forEach(e -> e.setLastError(msg));
                    log.warn("Meta lead events: page {} failed for {} customers: {}", pageId, batch.size(), msg);
                }
                leadEventRepository.saveAll(batch);
            }
        });
    }

    /** When the customer messaged, kept inside Meta's 7-day window and never in the future. */
    static LocalDateTime eventTime(CrmCustomerSource.PhoneLead lead, LocalDateTime now) {
        if (lead.createdAt() == null) return now;
        LocalDateTime t = LocalDateTime.ofInstant(lead.createdAt(), VIETNAM_ZONE);
        LocalDateTime oldest = now.minus(MAX_EVENT_AGE).plusMinutes(30);
        if (t.isAfter(now)) return now;
        return t.isBefore(oldest) ? oldest : t;
    }

    /** One CRM row per customer: the first one with a usable phone. */
    static Map<String, CrmCustomerSource.PhoneLead> firstLeadPerCustomer(List<CrmCustomerSource.PhoneLead> rows) {
        Map<String, CrmCustomerSource.PhoneLead> out = new LinkedHashMap<>();
        for (CrmCustomerSource.PhoneLead row : rows) {
            if (row.pageId() == null || row.pageId().isBlank() || row.psid() == null || row.psid().isBlank()) continue;
            if (!row.pageId().matches("\\d+") || !row.psid().matches("\\d+")) continue;
            if (MetaPurchaseEventService.normalizePhone(row.phone()) == null) continue;
            out.putIfAbsent(key(row.pageId(), row.psid()), row);
        }
        return out;
    }

    static List<Map<String, Object>> toEvents(List<MetaLeadEvent> batch, Map<String, CrmCustomerSource.PhoneLead> leads) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (MetaLeadEvent e : batch) {
            CrmCustomerSource.PhoneLead lead = leads.get(key(e.getPageId(), e.getPsid()));
            Map<String, Object> userData;
            if (lead != null) {
                userData = MetaPurchaseEventService.userData(e.getPageId(), e.getPsid(), lead.phone(), lead.name(), null);
            } else {
                userData = new LinkedHashMap<>();
                userData.put("page_id", e.getPageId());
                userData.put("page_scoped_user_id", e.getPsid());
                if (e.getPhoneHash() != null) userData.put("ph", List.of(e.getPhoneHash()));
                userData.put("country", List.of(MetaPurchaseEventService.sha256("vn")));
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event_name", "LeadSubmitted");
            event.put("event_time", e.getEventTime().atZone(VIETNAM_ZONE).toEpochSecond());
            event.put("event_id", "smoothgate-lead-" + e.getPageId() + "-" + e.getPsid());
            event.put("action_source", "business_messaging");
            event.put("messaging_channel", "messenger");
            event.put("user_data", userData);
            events.add(event);
        }
        return events;
    }

    private static String key(String pageId, String psid) {
        return pageId + "|" + psid;
    }

    private static String adIdOf(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return v.matches("\\d{6,64}") ? v : null;
    }

    private static String truncate(String value) {
        if (value == null) return "Lỗi không rõ";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
