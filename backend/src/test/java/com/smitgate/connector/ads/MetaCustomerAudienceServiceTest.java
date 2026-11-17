package com.smitgate.connector.ads;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetaCustomerAudienceServiceTest {

    @Test
    void hashPhonesNormalizesDeduplicatesAndDropsInvalid() {
        List<String> hashes = MetaCustomerAudienceService.hashPhones(
                List.of("0912 345 678", "+84912345678", "84912345678", "12345", "0987654321"));

        assertThat(hashes).containsExactly(
                MetaPurchaseEventService.sha256("84912345678"),
                MetaPurchaseEventService.sha256("84987654321"));
        assertThat(hashes.get(0)).hasSize(64);
    }

    @Test
    void batchesSplitAtMetaLimit() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < MetaCustomerAudienceService.BATCH_SIZE * 2 + 5; i++) items.add("h" + i);

        List<List<String>> batches = MetaCustomerAudienceService.batches(items);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(MetaCustomerAudienceService.BATCH_SIZE);
        assertThat(batches.get(2)).containsExactly("h20000", "h20001", "h20002", "h20003", "h20004");
    }
}
