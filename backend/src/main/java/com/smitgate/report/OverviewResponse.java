package com.smitgate.report;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class OverviewResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;

    private BigDecimal totalSpend;
    private BigDecimal totalRevenue;
    private long totalOrders;
    private long validOrders;
    private long newContacts;
    private BigDecimal roas;
    private BigDecimal cpo;
    private long totalClicks;
    private long totalImpressions;
    private long attributedOrders;
    private long messageContacts;
    private BigDecimal totalOrderProfit;
}
