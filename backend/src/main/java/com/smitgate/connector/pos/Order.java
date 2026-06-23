package com.smitgate.connector.pos;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "pos_shop_id", nullable = false)
    private Long posShopId;

    @Column(name = "external_order_id", nullable = false)
    private String externalOrderId;

    @Column(name = "created_at_external")
    private LocalDateTime createdAtExternal;

    @Column(name = "updated_at_external")
    private LocalDateTime updatedAtExternal;

    @Column(length = 50)
    private String status;

    @Column(precision = 18, scale = 2)
    private BigDecimal revenue = BigDecimal.ZERO;

    @Column(name = "shipping_fee", precision = 18, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "customer_phone", length = 50)
    private String customerPhone;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "utm_medium")
    private String utmMedium;

    @Column(name = "utm_campaign")
    private String utmCampaign;

    @Column(name = "utm_content")
    private String utmContent;

    @Column(name = "utm_term")
    private String utmTerm;

    @Column(name = "click_id", length = 500)
    private String clickId;

    @Column(name = "raw_json", columnDefinition = "TEXT")
    private String rawJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
