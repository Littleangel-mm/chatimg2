package com.example.chatimg2.service;

import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationRecoveryService {

    private final DistributedLockService distributedLockService;
    private final GenerationRecordService recordService;
    private final GenerationRecordRepository recordRepository;

    @Value("${app.generation.recovery.enabled:false}")
    private boolean enabled;

    @Value("${app.generation.recovery.stale-after-ms:900000}")
    private long staleAfterMs;

    @Value("${app.generation.recovery.lock-ttl-ms:60000}")
    private long lockTtlMs;

    private static final String RECOVERY_LOCK = "generation:recovery";
    private static final String STALE_TASK_MESSAGE =
            "\u751f\u6210\u4efb\u52a1\u4e2d\u65ad\uff0c\u5df2\u9000\u56de\u79ef\u5206\uff0c\u8bf7\u91cd\u65b0\u751f\u6210";

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverStaleProcessingTasks("startup");
    }

    @Scheduled(
            fixedDelayString = "${app.generation.recovery.interval-ms:60000}",
            initialDelayString = "${app.generation.recovery.initial-delay-ms:60000}"
    )
    public void recoverOnSchedule() {
        recoverStaleProcessingTasks("scheduled");
    }

    private void recoverStaleProcessingTasks(String trigger) {
        if (!enabled || staleAfterMs <= 0) {
            return;
        }
        boolean locked;
        try {
            locked = distributedLockService.acquire(RECOVERY_LOCK, Duration.ofMillis(lockTtlMs));
        } catch (Exception e) {
            log.warn("Skip stale generation recovery because recovery lock failed", e);
            return;
        }
        if (!locked) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(staleAfterMs));
            int recovered = recoverRecords(cutoff);
            if (recovered > 0) {
                log.warn("Recovered stale generation tasks: count={}, trigger={}, cutoff={}", recovered, trigger, cutoff);
            }
        } finally {
            distributedLockService.release(RECOVERY_LOCK);
        }
    }

    private int recoverRecords(LocalDateTime cutoff) {
        int recovered = 0;
        for (GenerationRecord record : recordRepository
                .findTop50ByStatusAndImagePathIsNullAndCreatedAtBeforeOrderByCreatedAtAsc("processing", cutoff)) {
            String recordLock = "generation:record:" + record.getId();
            boolean locked;
            try {
                locked = distributedLockService.acquire(recordLock, Duration.ofMillis(lockTtlMs));
            } catch (Exception e) {
                log.warn("Skip stale generation record because record lock failed: recordId={}", record.getId(), e);
                continue;
            }
            if (!locked) {
                continue;
            }
            try {
                if (record.getUserId() != null) {
                    recordService.markFailedForUser(record.getId(), record.getUserId(), STALE_TASK_MESSAGE);
                } else {
                    recordService.markFailed(record.getId(), record.getKeyId(), STALE_TASK_MESSAGE);
                }
                recovered++;
            } finally {
                distributedLockService.release(recordLock);
            }
        }
        return recovered;
    }
}
