package com.smitgate.connector.ads;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Interface for ads platform connectors (Meta, Google, TikTok).
 */
public interface AdsConnector {

    /** Get the OAuth authorization URL. */
    String getAuthorizationUrl(Long tenantId);

    /** Handle OAuth callback, store tokens, and return the dataSourceId. */
    Long handleCallback(Long tenantId, String code);

    /** List available ad accounts from the platform. */
    List<Map<String, Object>> listAdAccounts(Long tenantId, Long dataSourceId);

    /** Sync daily metrics for the given date range. */
    int syncMetricsDaily(Long tenantId, Long dataSourceId, LocalDate from, LocalDate to);
}
