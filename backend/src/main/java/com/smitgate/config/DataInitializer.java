package com.smitgate.config;

import com.smitgate.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "data-initializer-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final SystemSettingRepository systemSettingRepository;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(String... args) {
        try {
            purgeLegacyGlobalMetaSettings();
            purgeStaleAuthCache();
        } catch (DataAccessException ex) {
            log.error("Skip DataInitializer due to database access issue during startup: {}", ex.getMessage());
        } catch (Exception ex) {
            log.error("Skip DataInitializer due to unexpected startup error", ex);
        }
    }

    private void purgeLegacyGlobalMetaSettings() {
        boolean removedAppId = false;
        boolean removedSecret = false;

        if (systemSettingRepository.existsById("meta.app_id")) {
            systemSettingRepository.deleteById("meta.app_id");
            removedAppId = true;
        }
        if (systemSettingRepository.existsById("meta.app_secret")) {
            systemSettingRepository.deleteById("meta.app_secret");
            removedSecret = true;
        }

        if (removedAppId || removedSecret) {
            log.warn("Purged legacy global Meta settings (meta.app_id/meta.app_secret). Tenant-scoped Meta settings are required.");
        }
    }

    private void purgeStaleAuthCache() {
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            Set<String> keys = stringRedisTemplate.keys("auth:user_details::*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("Purged {} stale auth:user_details cache entries from Redis", keys.size());
            }
        } catch (Exception ex) {
            log.warn("Could not purge stale auth cache from Redis (non-fatal): {}", ex.getMessage());
        }
    }
}
