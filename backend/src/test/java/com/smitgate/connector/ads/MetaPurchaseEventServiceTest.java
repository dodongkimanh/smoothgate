package com.smitgate.connector.ads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetaPurchaseEventServiceTest {

    @Test
    void normalizesVietnamesePhonesToE164Digits() {
        assertEquals("84912345678", MetaPurchaseEventService.normalizePhone("0912 345 678"));
        assertEquals("84912345678", MetaPurchaseEventService.normalizePhone("+84 912.345.678"));
        assertEquals("84912345678", MetaPurchaseEventService.normalizePhone("84912345678"));
        assertNull(MetaPurchaseEventService.normalizePhone("12345"));
        assertNull(MetaPurchaseEventService.normalizePhone(null));
    }

    @Test
    void hashesWithLowercaseHexSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MetaPurchaseEventService.sha256("abc"));
    }
}
