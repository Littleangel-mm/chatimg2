package com.example.chatimg2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class DistributedLockService {

    private final JdbcTemplate jdbcTemplate;
    private final String instanceId;

    public DistributedLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceId = UUID.randomUUID().toString();
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

    public record LockToken(String lockKey, String ownerId) {
    }
}
