package com.smitgate.note;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "ad_notes")
@Data
public class AdNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "ad_id", nullable = false)
    private String adId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private String author;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(VIETNAM_ZONE);
    }
}
