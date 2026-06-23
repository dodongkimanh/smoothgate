package com.smitgate.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class AccountSpendResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long adAccountId;
    private String adAccountName;
    private String platform;
    private BigDecimal totalSpend;
}
