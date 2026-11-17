package com.smitgate.connector.ads;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** A Messenger customer who left a phone number; reported to Meta once as a Lead event. */
@Data
@Entity
@Table(name = "meta_lead_events")
public class MetaLeadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "page_id", nullable = false, length = 64)
    private String pageId;

    /** Page-scoped id of the customer on Messenger. */
    @Column(nullable = false, length = 64)
    private String psid;

    /** Row in the CRM customer table the phone was found on. */
    @Column(name = "crm_row_id")
    private Long crmRowId;

    /** SHA-256 of the normalized phone; the phone itself stays in the CRM. */
    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    /** The ad the conversation came from, as recorded in the CRM (for reports only). */
    @Column(name = "ad_id", length = 64)
    private String adId;

    /** When the customer messaged the Page (Vietnam time), within Meta's 7-day window. */
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

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
