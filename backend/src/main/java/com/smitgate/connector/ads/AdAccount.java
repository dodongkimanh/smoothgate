package com.smitgate.connector.ads;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "ad_accounts")
public class AdAccount {

    public enum Platform { META, GOOGLE, TIKTOK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "data_source_id", nullable = false)
    private Long dataSourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(name = "external_account_id", nullable = false)
    private String externalAccountId;

    private String name;

    /** Entered manually: Meta's Graph API does not expose the payment threshold. */
    @Column(name = "billing_threshold")
    private BigDecimal billingThreshold;

    /** Entered manually: Meta's Graph API does not expose the next bill date. */
    @Column(name = "next_bill_date")
    private LocalDate nextBillDate;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
