package com.example.chatimg2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DistributedLockService {

    private final JdbcTemplate jdbcTemplate;
    private final String instanceId;
    private final ScheduledExecutorService leaseExecutor;

    public DistributedLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceId = UUID.randomUUID().toString();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "distributed-lock-lease");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        this.leaseExecutor = executor;
    }

    public Optional<LockToken> tryAcquire(String lockKey, Duration ttl) {
        long ttlMs = Math.max(1000L, ttl.toMillis());
        String ownerId = instanceId + "-" + UUID.randomUUID();
        String sql = """
                INSERT INTO distributed_locks (lock_key, owner_id, expires_at, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (lock_key) DO UPDATE
                SET owner_id = EXCLUDED.owner_id,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = CURRENT_TIMESTAMP
                WHERE distributed_locks.expires_at < CURRENT_TIMESTAMP
                """;
        try {
            return jdbcTemplate.update(sql, lockKey, ownerId, ttlMs) > 0
                    ? Optional.of(new LockToken(lockKey, ownerId))
                    : Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Acquire distributed lock failed: " + lockKey, e);
        }
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

    public LockLease keepAlive(LockToken token, Duration ttl) {
        if (token == null) {
            return LockLease.inactive();
        }
        long ttlMs = Math.max(3000L, ttl.toMillis());
        long intervalMs = Math.max(1000L, ttlMs / 3L);
        ScheduledFuture<?> future = leaseExecutor.scheduleWithFixedDelay(() -> {
            try {
                int updated = jdbcTemplate.update("""
                                UPDATE distributed_locks
                                SET expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE lock_key = ? AND owner_id = ?
                                """,
                        ttlMs, token.lockKey(), token.ownerId());
                if (updated == 0) {
                    log.error("Distributed lock lease was lost: lockKey={}", token.lockKey());
                }
            } catch (Exception e) {
                log.warn("Renew distributed lock failed: lockKey={}", token.lockKey(), e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return new LockLease(future);
    }

    @PreDestroy
    void shutdownLeaseExecutor() {
        leaseExecutor.shutdownNow();
    }

    public record LockToken(String lockKey, String ownerId) {
    }

    public static final class LockLease implements AutoCloseable {
        private final ScheduledFuture<?> future;

        private LockLease(ScheduledFuture<?> future) {
            this.future = future;
        }

        private static LockLease inactive() {
            return new LockLease(null);
        }

        @Override
        public void close() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
