package com.smitgate.report;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class CampaignPerfResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long campaignId;
    private String campaignExternalId;
    private String campaignName;
    private String platform;
    private String status;
    private BigDecimal totalSpend;
    private int totalOrders;
    private int validOrders;
    private int newContacts;
    private long messageContacts;
    private BigDecimal totalRevenue;
    private BigDecimal totalOrderProfit;
    private BigDecimal roas;
    private BigDecimal cpo;
    private List<DailyData> daily;

    @Data
    @AllArgsConstructor
    public static class DailyData implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String date;
        private BigDecimal spend;
        private int orders;
        private BigDecimal revenue;
        private BigDecimal roas;
    }
}
