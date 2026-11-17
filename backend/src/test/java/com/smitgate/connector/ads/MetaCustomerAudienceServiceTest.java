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

        assertThat(c).isEqualTo(new CustomerContact("84912345678", "an", "nguyễn", "m", "haiduong"));
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
        CustomerContact buyer = new CustomerContact("84912345678", "an", "nguyễn", null, "haiduong");
        CustomerContact lead = new CustomerContact("84912345678", "anh", "tran", "m", null);
        CustomerContact other = new CustomerContact("84987654321", "binh", null, null, null);

        Collection<CustomerContact> merged = MetaCustomerAudienceService.merge(List.of(buyer), List.of(lead, other));

        assertThat(merged).containsExactly(
                new CustomerContact("84912345678", "an", "nguyễn", "m", "haiduong"), other);
    }

    @Test
    void rowsAreHashedInSchemaOrderWithEmptyForMissing() {
        List<List<String>> rows = MetaCustomerAudienceService.hashRows(
                List.of(new CustomerContact("84912345678", "an", null, null, null)));

        assertThat(MetaCustomerAudienceService.SCHEMA).containsExactly("PHONE", "FN", "LN", "GEN", "ST", "COUNTRY");
        assertThat(rows.get(0)).containsExactly(
                MetaPurchaseEventService.sha256("84912345678"), MetaPurchaseEventService.sha256("an"),
                "", "", "", MetaPurchaseEventService.sha256("vn"));
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
