package com.smitgate.report;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class CampaignFunnelResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long campaignId;
    private String campaignName;
    private String platform;
    private String status;
    private long attributedOrders;
    private long validOrders;
    private long uniquePhones;
    private BigDecimal totalRevenue;
    private BigDecimal avgRevenuePerOrder;
    private BigDecimal conversionRate;
    private List<MatchedOrder> recentOrders;

    @Data
    @AllArgsConstructor
    public static class MatchedOrder implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private Long orderId;
        private String externalOrderId;
        private String orderDate;
        private String customerPhone;
        private String status;
        private boolean validOrder;
        private BigDecimal revenue;
        private String adId;
        private String matchType;
        private String matchedAt;
    }
}
