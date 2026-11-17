package com.smitgate.connector.ads;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaLeadEventServiceTest {

    private static CrmCustomerSource.PhoneLead lead(long id, String page, String psid, String phone) {
        return new CrmCustomerSource.PhoneLead(id, page, psid, phone, "Nguyễn Văn An", "EMPTY", Instant.now());
    }

    @Test
    void keepsOneRowPerCustomerWithAUsablePhone() {
        Map<String, CrmCustomerSource.PhoneLead> out = MetaLeadEventService.firstLeadPerCustomer(List.of(
                lead(1, "111", "900", "đã xin"),          // no usable phone
                lead(2, "111", "900", "0912 345 678"),    // first usable row for this customer
                lead(3, "111", "900", "0987654321"),      // same customer again
                lead(4, "222", "900", "0912345678"),      // same PSID on another page is another customer
                lead(5, "", "901", "0912345678"),         // no page
                lead(6, "111", "abc", "0912345678")));    // not a PSID
        assertEquals(2, out.size());
        assertEquals(2, out.get("111|900").rowId());
        assertEquals(4, out.get("222|900").rowId());
    }

    @Test
    void buildsAMessengerLeadEventForTheConversation() {
        MetaLeadEvent e = new MetaLeadEvent();
        e.setPageId("111");
        e.setPsid("900");
        e.setEventTime(LocalDateTime.of(2026, 11, 17, 20, 0));
        Map<String, CrmCustomerSource.PhoneLead> leads =
                MetaLeadEventService.firstLeadPerCustomer(List.of(lead(2, "111", "900", "0912345678")));

        Map<String, Object> event = MetaLeadEventService.toEvents(List.of(e), leads).get(0);
        assertEquals("LeadSubmitted", event.get("event_name"));
        assertEquals("smoothgate-lead-111-900", event.get("event_id"));
        assertEquals("business_messaging", event.get("action_source"));
        assertEquals("messenger", event.get("messaging_channel"));
        assertEquals(1794920400L, event.get("event_time"));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) event.get("user_data");
        assertEquals("900", user.get("page_scoped_user_id"));
        assertEquals(List.of(MetaPurchaseEventService.sha256("84912345678")), user.get("ph"));
        assertEquals(List.of(MetaPurchaseEventService.sha256("an")), user.get("fn"));
        assertFalse(user.containsKey("ge"));
    }

    @Test
    void fallsBackToTheStoredPhoneHashWhenTheCrmRowIsGone() {
        MetaLeadEvent e = new MetaLeadEvent();
        e.setPageId("111");
        e.setPsid("900");
        e.setPhoneHash("abc");
        e.setEventTime(LocalDateTime.of(2026, 11, 17, 20, 0));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) MetaLeadEventService.toEvents(List.of(e), Map.of())
                .get(0).get("user_data");
        assertEquals(List.of("abc"), user.get("ph"));
        assertTrue(user.containsKey("country"));
    }

    @Test
    void usesTheMessageTimeInsideMetasSevenDayWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 17, 21, 0);
        Instant messaged = LocalDateTime.of(2026, 11, 12, 7, 28).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        CrmCustomerSource.PhoneLead recent = new CrmCustomerSource.PhoneLead(1, "111", "900", "0912345678", null, null, messaged);
        assertEquals(LocalDateTime.of(2026, 11, 12, 7, 28), MetaLeadEventService.eventTime(recent, now));

        Instant tooOld = now.minusDays(8).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        CrmCustomerSource.PhoneLead old = new CrmCustomerSource.PhoneLead(1, "111", "900", "0912345678", null, null, tooOld);
        assertTrue(MetaLeadEventService.eventTime(old, now).isAfter(now.minusDays(7)));

        Instant future = now.plusHours(2).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        CrmCustomerSource.PhoneLead ahead = new CrmCustomerSource.PhoneLead(1, "111", "900", "0912345678", null, null, future);
        assertEquals(now, MetaLeadEventService.eventTime(ahead, now));
    }
}
