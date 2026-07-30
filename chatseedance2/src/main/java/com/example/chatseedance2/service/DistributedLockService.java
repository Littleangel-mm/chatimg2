package com.example.chatseedance2.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service("videoDistributedLockService")
public class DistributedLockService {

    private final JdbcTemplate jdbcTemplate;
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService leaseExecutor;

    public DistributedLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "video-lock-lease");
            thread.setDaemon(true);
            return thread;
        };
        this.leaseExecutor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public Optional<LockToken> tryAcquire(String lockKey, Duration ttl) {
        long ttlMs = Math.max(1000L, ttl.toMillis());
        String ownerId = instanceId + "-" + UUID.randomUUID();
        int updated = jdbcTemplate.update("""
                INSERT INTO distributed_locks (lock_key, owner_id, expires_at, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (lock_key) DO UPDATE
                SET owner_id = EXCLUDED.owner_id,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = CURRENT_TIMESTAMP
                WHERE distributed_locks.expires_at < CURRENT_TIMESTAMP
                """, lockKey, ownerId, ttlMs);
        return updated > 0 ? Optional.of(new LockToken(lockKey, ownerId)) : Optional.empty();
    }

    public LockLease keepAlive(LockToken token, Duration ttl) {
        long ttlMs = Math.max(3000L, ttl.toMillis());
        long intervalMs = Math.max(1000L, ttlMs / 3L);
        ScheduledFuture<?> future = leaseExecutor.scheduleWithFixedDelay(() -> renew(token, ttlMs),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return new LockLease(future);
    }

    public void release(LockToken token) {
        if (token == null) {
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM distributed_locks WHERE lock_key = ? AND owner_id = ?",
                    token.lockKey(), token.ownerId());
        } catch (Exception e) {
            log.warn("Release distributed lock failed: lockKey={}", token.lockKey(), e);
        }
    }

    private void renew(LockToken token, long ttlMs) {
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE distributed_locks
                    SET expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE lock_key = ? AND owner_id = ?
                    """, ttlMs, token.lockKey(), token.ownerId());
            if (updated == 0) {
                log.error("Distributed lock lease lost: lockKey={}", token.lockKey());
            }
        } catch (Exception e) {
            log.warn("Renew distributed lock failed: lockKey={}", token.lockKey(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        leaseExecutor.shutdownNow();
    }

    public record LockToken(String lockKey, String ownerId) {
    }

    public static final class LockLease implements AutoCloseable {
        private final ScheduledFuture<?> future;

        private LockLease(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public void close() {
            future.cancel(false);
        }
    }
}
