package com.smitgate.datasource;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sync_state")
public class SyncState {

    public enum Entity { ORDERS, ADS_METRICS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "data_source_id", nullable = false)
    private Long dataSourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Entity entity;

    private LocalDateTime watermark;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
