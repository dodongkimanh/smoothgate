package com.smitgate.agent;

import com.smitgate.config.SystemSetting;
import com.smitgate.config.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Per-tenant configurable thresholds for the AI campaign monitoring agent. */
@Service
@RequiredArgsConstructor
public class AgentSettingsService {

    private static final String KEY_COST_PER_MESSAGE = "agent.threshold.cost_per_message";
    private static final String KEY_COST_PER_PHONE = "agent.threshold.cost_per_phone";
    private static final String KEY_COST_PER_ORDER = "agent.threshold.cost_per_order";
    private static final String KEY_LOSS_AFTER_ADS = "agent.threshold.loss_after_ads";
    private static final String KEY_ANALYSIS_WINDOW_DAYS = "agent.analysis_window_days";
    private static final String KEY_ENABLED = "agent.enabled";
    private static final String KEY_INTERVAL_MINUTES = "agent.interval_minutes";
    private static final String KEY_LAST_RUN_AT = "agent.last_run_at_epoch_ms";

    static final BigDecimal DEFAULT_COST_PER_MESSAGE = BigDecimal.valueOf(90_000);
    static final BigDecimal DEFAULT_COST_PER_PHONE = BigDecimal.valueOf(350_000);
    static final BigDecimal DEFAULT_COST_PER_ORDER = BigDecimal.valueOf(3_000_000);
    static final BigDecimal DEFAULT_LOSS_AFTER_ADS = BigDecimal.valueOf(5_000_000);
    static final int DEFAULT_ANALYSIS_WINDOW_DAYS = 3;
    static final boolean DEFAULT_ENABLED = true;
    static final int DEFAULT_INTERVAL_MINUTES = 60;

    private final SystemSettingRepository settingRepository;

    public AgentSettings getSettings(Long tenantId) {
        return new AgentSettings(
                getDecimal(tenantId, KEY_COST_PER_MESSAGE, DEFAULT_COST_PER_MESSAGE),
                getDecimal(tenantId, KEY_COST_PER_PHONE, DEFAULT_COST_PER_PHONE),
                getDecimal(tenantId, KEY_COST_PER_ORDER, DEFAULT_COST_PER_ORDER),
                getDecimal(tenantId, KEY_LOSS_AFTER_ADS, DEFAULT_LOSS_AFTER_ADS),
                getInt(tenantId, KEY_ANALYSIS_WINDOW_DAYS, DEFAULT_ANALYSIS_WINDOW_DAYS),
                getBoolean(tenantId, KEY_ENABLED, DEFAULT_ENABLED),
                getInt(tenantId, KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        );
    }

    public void saveSettings(Long tenantId, AgentSettings settings) {
        upsert(tenantId, KEY_COST_PER_MESSAGE, settings.costPerMessageThreshold().toPlainString());
        upsert(tenantId, KEY_COST_PER_PHONE, settings.costPerPhoneThreshold().toPlainString());
        upsert(tenantId, KEY_COST_PER_ORDER, settings.costPerOrderThreshold().toPlainString());
        upsert(tenantId, KEY_LOSS_AFTER_ADS, settings.lossAfterAdsThreshold().toPlainString());
        upsert(tenantId, KEY_ANALYSIS_WINDOW_DAYS, String.valueOf(settings.analysisWindowDays()));
        upsert(tenantId, KEY_ENABLED, String.valueOf(settings.enabled()));
        upsert(tenantId, KEY_INTERVAL_MINUTES, String.valueOf(settings.intervalMinutes()));
    }

    /** Epoch millis of the last analysis run for this tenant, or null if it has never run. */
    public Long getLastRunAt(Long tenantId) {
        String raw = getValue(tenantId, KEY_LAST_RUN_AT);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void markRunNow(Long tenantId) {
        upsert(tenantId, KEY_LAST_RUN_AT, String.valueOf(System.currentTimeMillis()));
    }

    private BigDecimal getDecimal(Long tenantId, String key, BigDecimal fallback) {
        String raw = getValue(tenantId, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int getInt(Long tenantId, String key, int fallback) {
        String raw = getValue(tenantId, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean getBoolean(Long tenantId, String key, boolean fallback) {
        String raw = getValue(tenantId, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private String getValue(Long tenantId, String key) {
        return settingRepository.findById(tenantSettingKey(tenantId, key))
                .map(SystemSetting::getValue)
                .orElse(null);
    }

    private void upsert(Long tenantId, String key, String value) {
        String tenantKey = tenantSettingKey(tenantId, key);
        SystemSetting s = settingRepository.findById(tenantKey).orElse(new SystemSetting(tenantKey, value));
        s.setValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(s);
    }

    private String tenantSettingKey(Long tenantId, String key) {
        if (tenantId == null) return key;
        return "tenant." + tenantId + "." + key;
    }

    public record AgentSettings(
            BigDecimal costPerMessageThreshold,
            BigDecimal costPerPhoneThreshold,
            BigDecimal costPerOrderThreshold,
            BigDecimal lossAfterAdsThreshold,
            int analysisWindowDays,
            boolean enabled,
            int intervalMinutes
    ) {
    }
}
