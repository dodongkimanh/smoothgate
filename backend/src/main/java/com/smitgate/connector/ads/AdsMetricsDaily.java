package com.smitgate.connector.ads;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "ads_metrics_daily")
public class AdsMetricsDaily {

    public enum Platform { META, GOOGLE, TIKTOK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(name = "ad_account_id", nullable = false)
    private Long adAccountId;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 18, scale = 2)
    private BigDecimal spend = BigDecimal.ZERO;

    private Long impressions = 0L;

    private Long clicks = 0L;

    private Long reach = 0L;

    @Column(precision = 10, scale = 4)
    private BigDecimal ctr = BigDecimal.ZERO;

    @Column(precision = 18, scale = 6)
    private BigDecimal cpc = BigDecimal.ZERO;

    @Column(precision = 18, scale = 6)
    private BigDecimal cpm = BigDecimal.ZERO;

    @Column(name = "message_contacts")
    private Long messageContacts = 0L;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;
}
