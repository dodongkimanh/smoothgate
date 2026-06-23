package com.smitgate.attribution;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "order_attribution")
public class OrderAttribution {

    public enum Platform { META, GOOGLE, TIKTOK, UNKNOWN }
    public enum MatchType { CLICK_ID, UTM, UNKNOWN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    private Platform platform = Platform.UNKNOWN;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type")
    private MatchType matchType = MatchType.UNKNOWN;

    @Column(name = "matched_at")
    private LocalDateTime matchedAt;

    @PrePersist
    protected void onCreate() {
        matchedAt = LocalDateTime.now();
    }
}
