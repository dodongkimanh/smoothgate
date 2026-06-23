package com.smitgate.config;

import com.smitgate.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Admin endpoints for system-level configuration (Meta App credentials, etc.)
 * All endpoints require JWT auth (secured by SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class SystemSettingsController {

    private static final String KEY_META_APP_ID     = "meta.app_id";
    private static final String KEY_META_APP_SECRET = "meta.app_secret";
    @Value("${app.facebook.redirect-uri}")
    private String defaultMetaRedirectUri;

    private final SystemSettingRepository settingRepository;

    /** Returns current Meta app configuration (secret is masked). */
    @GetMapping("/meta")
    public ResponseEntity<ApiResponse<Map<String, String>>> getMetaSettings(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String appId = getValue(tenantId, KEY_META_APP_ID);
        String appSecret = getValue(tenantId, KEY_META_APP_SECRET);

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "appId", appId != null ? appId : "",
                "appSecretSet", appSecret != null && !appSecret.isBlank() ? "true" : "false",
                "redirectUri", defaultMetaRedirectUri
        )));
    }

    /**
     * Saves Meta App credentials.
     * Body: { "appId": "...", "appSecret": "...", "redirectUri": "..." }
     * appSecret is optional — if blank, the existing value is kept.
     */
    @PutMapping("/meta")
    public ResponseEntity<ApiResponse<Void>> saveMetaSettings(
            HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        String appId = body.get("appId");
        String appSecret = body.get("appSecret");
        if (appId != null && !appId.isBlank()) {
            upsert(tenantId, KEY_META_APP_ID, appId.trim());
        }
        // Only update secret if a new value was provided
        if (appSecret != null && !appSecret.isBlank()) {
            upsert(tenantId, KEY_META_APP_SECRET, appSecret.trim());
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** Clears tenant-level Meta settings so tenant must re-configure their own app credentials. */
    @DeleteMapping("/meta")
    public ResponseEntity<ApiResponse<Void>> clearMetaSettings(HttpServletRequest request) {
        Long tenantId = (Long) request.getAttribute("tenantId");
        settingRepository.deleteById(tenantSettingKey(tenantId, KEY_META_APP_ID));
        settingRepository.deleteById(tenantSettingKey(tenantId, KEY_META_APP_SECRET));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private String getValue(Long tenantId, String key) {
        String tenantKey = tenantSettingKey(tenantId, key);
        return settingRepository.findById(tenantKey)
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
}
