package com.smitgate.connector.ads;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetaCustomerAudienceServiceTest {

    @Test
    void contactNormalizesPhoneNameGenderAndProvince() {
        CustomerContact c = CustomerContact.of("0912 345 678", "  Nguyễn Văn An ", "male",
                "khu 9, Thị trấn Thanh Hà, Huyện Thanh Hà, Tỉnh Hải Dương");

        assertThat(c).isEqualTo(new CustomerContact("84912345678", "an", "nguyễn", "m", "haiduong", null));
        assertThat(CustomerContact.of("12345", "An", null, null)).isNull();
    }

    @Test
    void provinceIsTheLastOneNamedInTheAddress() {
        assertThat(CustomerContact.provinceOf("Thôn 9 eangai huyện krongbuc đăklak")).isEqualTo("daklak");
        assertThat(CustomerContact.provinceOf("Xóm 9, Phường Thi Sơn, Thị xã Kim Bảng, Tỉnh Hà Nam")).isEqualTo("hanam");
        assertThat(CustomerContact.provinceOf("123 Lê Lợi, Quận 1, TP.HCM")).isEqualTo("hochiminh");
        assertThat(CustomerContact.provinceOf("gần chợ")).isNull();
    }

    @Test
    void singleWordNameIsFirstName() {
        assertThat(CustomerContact.splitName("Hùng!")).containsExactly("hùng", null);
        assertThat(CustomerContact.splitName("  ")).containsExactly(null, null);
    }

    @Test
    void mergeKeepsOnePerPhoneAndFillsMissingFields() {
        CustomerContact buyer = new CustomerContact("84912345678", "an", "nguyễn", null, "haiduong", null);
        CustomerContact lead = new CustomerContact("84912345678", "anh", "tran", "m", null, null);
        CustomerContact other = new CustomerContact("84987654321", "binh", null, null, null, null);

        Collection<CustomerContact> merged = MetaCustomerAudienceService.merge(List.of(buyer), List.of(lead, other));

        assertThat(merged).containsExactly(
                new CustomerContact("84912345678", "an", "nguyễn", "m", "haiduong", null), other);
    }

    @Test
    void rowsAreHashedInSchemaOrderWithEmptyForMissing() {
        List<List<String>> rows = MetaCustomerAudienceService.hashRows(
                List.of(new CustomerContact("84912345678", "an", null, null, null, null)));

        assertThat(MetaCustomerAudienceService.SCHEMA).containsExactly("PHONE", "FN", "LN", "GEN", "ST", "DOBY", "COUNTRY");
        assertThat(rows.get(0)).containsExactly(
                MetaPurchaseEventService.sha256("84912345678"), MetaPurchaseEventService.sha256("an"),
                "", "", "", "", MetaPurchaseEventService.sha256("vn"));
    }

    @Test
    void crmProfileGivesGenderAndPlausibleBirthYear() {
        assertThat(CustomerContact.profile("0912345678", "Nữ", 1972, 2026))
                .isEqualTo(new CustomerContact("84912345678", null, null, "f", null, "1972"));
        assertThat(CustomerContact.profile("0912345678", "Nam", 72, 2026).birthYear()).isNull();
        assertThat(CustomerContact.profile("0912345678", null, 2020, 2026)).isNull();
        assertThat(CustomerContact.profile("0912345678", "", null, 2026)).isNull();
    }

    @Test
    void enrichFillsBirthYearWithoutOverwritingKnownFields() {
        CustomerContact buyer = new CustomerContact("84912345678", "an", "nguyễn", "m", "haiduong", null);
        CustomerContact profile = new CustomerContact("84912345678", null, null, "f", null, "1975");

        Collection<CustomerContact> out = MetaCustomerAudienceService.enrich(List.of(buyer),
                java.util.Map.of("84912345678", profile));

        assertThat(out).containsExactly(new CustomerContact("84912345678", "an", "nguyễn", "m", "haiduong", "1975"));
    }

    @Test
    void psidsAreGroupedByPageAndCanBeLimitedToBuyers() {
        List<CrmCustomerSource.PagePsid> psids = List.of(
                new CrmCustomerSource.PagePsid("84911111111", "p1", "100"),
                new CrmCustomerSource.PagePsid("84911111111", "p1", "100"),
                new CrmCustomerSource.PagePsid("84911111111", "p2", "200"),
                new CrmCustomerSource.PagePsid("84922222222", "p1", "101"));

        assertThat(MetaCustomerAudienceService.psidsByPage(psids, null))
                .containsExactly(java.util.Map.entry("p1", List.of("100", "101")), java.util.Map.entry("p2", List.of("200")));
        assertThat(MetaCustomerAudienceService.psidsByPage(psids, java.util.Set.of("84922222222")))
                .containsExactly(java.util.Map.entry("p1", List.of("101")));
    }

    @Test
    void listStillBeingReplacedIsSkippedForUpToThreeDays() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 20, 23, 59);
        MetaCustomerAudience row = new MetaCustomerAudience();
        row.setLastSyncedAt(now.minusDays(1));
        row.setMetaStatus("operation 414 Đang thay thế đối tượng này. | delivery 200 ok");
        assertThat(MetaCustomerAudienceService.isStillReplacing(row, now)).isTrue();

        row.setLastSyncedAt(now.minusDays(4));
        assertThat(MetaCustomerAudienceService.isStillReplacing(row, now)).isFalse();

        row.setLastSyncedAt(now.minusDays(1));
        row.setMetaStatus("operation 200 Bình thường | delivery 200 ok");
        assertThat(MetaCustomerAudienceService.isStillReplacing(row, now)).isFalse();
    }

    @Test
    void diffCountsJoinedAndLeft() {
        assertThat(MetaCustomerAudienceService.diff(java.util.Set.of("a", "b", "c"), java.util.Set.of("b", "c", "d", "e")))
                .containsExactly(2, 1);
    }

    @Test
    void genderIsReadFromPancakePayload() {
        assertThat(CustomerContact.genderFromJson("{\"gender\":\"female\",\"name\":\"x\"}")).isEqualTo("female");
        assertThat(CustomerContact.genderFromJson("{\"gender\":null}")).isNull();
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
