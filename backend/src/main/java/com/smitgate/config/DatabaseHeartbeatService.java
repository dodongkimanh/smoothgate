package com.smitgate.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the HikariCP connection pool alive and monitors pool health.
 *
 * <p>Runs {@code SELECT 1} periodically, which:
 * <ul>
 *   <li>Validates the connection lifecycle through HikariCP → PgBouncer → Supabase.</li>
 *   <li>Keeps the HikariCP minimum-idle connections warm.</li>
 *   <li>Logs pool stats on every beat for diagnosing connection exhaustion.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.db", name = "heartbeat-enabled", havingValue = "true", matchIfMissing = false)
public class DatabaseHeartbeatService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Value("${app.db.heartbeat-evict-after-failures:2}")
    private int evictAfterFailures;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * Ping the database every 2 minutes.
     * Initial delay 60s — gives the pool time to initialise after startup.
     */
    @Scheduled(
            fixedDelayString = "${app.db.heartbeat-interval-ms:45000}",
            initialDelayString = "${app.db.heartbeat-initial-delay-ms:30000}")
    public void heartbeat() {
        String poolStats = getPoolStats();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            int recoveredFrom = consecutiveFailures.getAndSet(0);
            if (recoveredFrom > 0) {
                log.info("[DB Heartbeat] Recovered after {} consecutive failures. {}", recoveredFrom, poolStats);
            } else {
                log.debug("[DB Heartbeat] OK. {}", poolStats);
            }
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            Throwable root = findRootCause(e);
            log.warn("[DB Heartbeat] Ping failed (consecutive={}). root={}: {}. {}",
                    failures, root.getClass().getSimpleName(), root.getMessage(), poolStats);

            // After N consecutive failures, force-evict and probe to trigger pool rebuild.
            // so HikariCP creates fresh ones on next borrow.
            if (failures >= Math.max(evictAfterFailures, 1)) {
                log.warn("[DB Heartbeat] {} consecutive failures — soft-evicting idle connections", failures);
                softEvictConnections();
                probeFreshConnection();
            }
        }
    }

    private String getPoolStats() {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    return String.format("pool[total=%d, active=%d, idle=%d, waiting=%d]",
                            pool.getTotalConnections(),
                            pool.getActiveConnections(),
                            pool.getIdleConnections(),
                            pool.getThreadsAwaitingConnection());
                }
            }
        } catch (Exception ignored) {}
        return "pool[stats unavailable]";
    }

    /**
     * Soft-evict all connections: marks them for retirement on next return to pool.
     * Does NOT interrupt active connections — only affects connections when they become idle.
     */
    private void softEvictConnections() {
        try {
            if (dataSource instanceof HikariDataSource hikari) {
                HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    pool.softEvictConnections();
                    log.info("[DB Heartbeat] Soft-evict issued. Pool will create fresh connections on next borrow.");
                }
            }
        } catch (Exception e) {
            log.warn("[DB Heartbeat] Failed to soft-evict: {}", e.getMessage());
        }
    }

    /**
     * Probes a fresh connection immediately after eviction to accelerate recovery.
     */
    private void probeFreshConnection() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                if (connection.isValid(3)) {
                    log.info("[DB Heartbeat] Fresh connection probe success on attempt {}. {}", attempt, getPoolStats());
                    return;
                }
                log.warn("[DB Heartbeat] Fresh connection probe returned invalid connection on attempt {}", attempt);
            } catch (SQLException ex) {
                log.warn("[DB Heartbeat] Fresh connection probe failed on attempt {}: {}", attempt, ex.getMessage());
            }
        }
    }

    /**
     * Trigger immediate soft-evict when app detects transient connection-acquire failures.
     */
    public void recoverFromAcquireFailure(String reason) {
        log.warn("[DB Recovery] Triggered by: {}. {}", reason, getPoolStats());
        softEvictConnections();
        probeFreshConnection();
    }

    private Throwable findRootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
