package com.smitgate.datasource;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "data_sources")
public class DataSource {

    public enum Type { PANCAKE_POS, META_ADS, GOOGLE_ADS, TIKTOK_ADS }
    public enum Status { ACTIVE, INACTIVE, ERROR, DELETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.INACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private String configJson;

    @JsonIgnore
    @Column(name = "secret_encrypted", columnDefinition = "TEXT")
    private String secretEncrypted;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Column(name = "last_error_msg", columnDefinition = "TEXT")
    private String lastErrorMsg;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
