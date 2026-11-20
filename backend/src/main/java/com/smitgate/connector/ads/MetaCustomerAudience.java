package com.smitgate.connector.ads;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** A Meta custom audience Smoothgate keeps in sync for one ad account: a phone list or a lookalike of one. */
@Data
@Entity
@Table(name = "meta_customer_audiences")
public class MetaCustomerAudience {

    /**
     * Customer lists are uploaded from CRM/Pancake phones, or (PSID_*) from the Messenger page-scoped ids of the same
     * customers; lookalikes are created from them once.
     */
    public enum Kind {
        BUYERS, ALL_PHONES, LAL_BUYERS, LAL_ALL_PHONES, PSID_BUYERS, PSID_ALL_PHONES, LAL_PSID_BUYERS, PSID_SILENT;

        public boolean isLookalike() {
            return this == LAL_BUYERS || this == LAL_ALL_PHONES || this == LAL_PSID_BUYERS;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "ad_account_id", nullable = false)
    private Long adAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Kind kind;

    /** Meta custom audience id; null until created (or after Meta reports it deleted). */
    @Column(name = "external_audience_id")
    private String externalAudienceId;

    /** Last successful upload (lists) or creation (lookalikes). */
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** Phones sent in the last upload. */
    @Column(name = "last_count")
    private Integer lastCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** Matched people as Meta reports it (a range; -1 while Meta is still counting). */
    @Column(name = "meta_count_lower")
    private Long metaCountLower;

    @Column(name = "meta_count_upper")
    private Long metaCountUpper;

    /** Meta's operation and delivery status, e.g. "200 Normal | delivery 200 ...". */
    @Column(name = "meta_status", length = 500)
    private String metaStatus;

    @Column(name = "meta_checked_at")
    private LocalDateTime metaCheckedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
