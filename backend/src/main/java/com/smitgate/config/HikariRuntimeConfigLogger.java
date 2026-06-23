package com.smitgate.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class HikariRuntimeConfigLogger implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return;
        }

        String jdbcUrl = hikari.getJdbcUrl();
        String jdbcHint = sanitizeJdbcHint(jdbcUrl);

        log.info("Hikari runtime config: jdbc={}, maxPoolSize={}, minIdle={}, connectionTimeoutMs={}, idleTimeoutMs={}, maxLifetimeMs={}, keepaliveMs={}, leakDetectionMs={}, validationTimeoutMs={}, txIsolation={}, testQuery={}",
                jdbcHint,
                hikari.getMaximumPoolSize(),
                hikari.getMinimumIdle(),
                hikari.getConnectionTimeout(),
                hikari.getIdleTimeout(),
                hikari.getMaxLifetime(),
                hikari.getKeepaliveTime(),
                hikari.getLeakDetectionThreshold(),
                hikari.getValidationTimeout(),
                hikari.getTransactionIsolation(),
                hikari.getConnectionTestQuery() != null ? hikari.getConnectionTestQuery() : "JDBC4 isValid()");
    }

    private String sanitizeJdbcHint(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "<empty>";
        }
        int q = jdbcUrl.indexOf('?');
        return q > 0 ? jdbcUrl.substring(0, q) : jdbcUrl;
    }
}
