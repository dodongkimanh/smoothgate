package com.smitgate.report;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "campaign_perf_daily")
public class CampaignPerfDaily {

    public enum Platform { META, GOOGLE, TIKTOK, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    private Platform platform = Platform.UNKNOWN;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 18, scale = 2)
    private BigDecimal spend = BigDecimal.ZERO;

    @Column(name = "orders_count")
    private Integer ordersCount = 0;

    @Column(precision = 18, scale = 2)
    private BigDecimal revenue = BigDecimal.ZERO;

    @Column(precision = 18, scale = 6)
    private BigDecimal cpo = BigDecimal.ZERO;

    @Column(precision = 18, scale = 6)
    private BigDecimal roas = BigDecimal.ZERO;
}
