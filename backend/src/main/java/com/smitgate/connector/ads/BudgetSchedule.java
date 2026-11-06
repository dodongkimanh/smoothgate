package com.smitgate.connector.ads;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Raises (positive percent) or lowers (negative) a Meta ad set's / CBO campaign's daily budget once a day at a set time. */
@Data
@Entity
@Table(name = "ad_budget_schedules")
public class BudgetSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "data_source_id", nullable = false)
    private Long dataSourceId;

    /** Meta ad set id, or campaign id when the campaign owns the budget (CBO). */
    @Column(name = "object_id", nullable = false)
    private String objectId;

    /** Owning "act_..." account, resolved from Meta on save; used to scope staff visibility. */
    @Column(name = "ad_account_id")
    private String adAccountId;

    /** Positive = increase, negative = decrease. */
    @Column(name = "increase_percent", nullable = false)
    private BigDecimal increasePercent;

    /** "HH:mm", Vietnam time. */
    @Column(name = "run_time", nullable = false)
    private String runTime;

    /** Optional bound: a ceiling for increases, a floor for decreases. */
    @Column(name = "budget_limit")
    private Long budgetLimit;

    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_result")
    private String lastResult;

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
