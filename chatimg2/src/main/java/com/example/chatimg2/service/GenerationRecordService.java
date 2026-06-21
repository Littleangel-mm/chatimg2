package com.example.chatimg2.service;

import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.repository.GenerationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.Callable;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationRecordService {

    private final GenerationRecordRepository recordRepository;
    private final KeyService keyService;
    private final TaskCodeService taskCodeService;
    private final TransactionTemplate transactionTemplate;

    private static final int CREDITS_PER_GENERATION = 20;
    private static final int DB_SAVE_MAX_ATTEMPTS = 6;
    private static final long DB_SAVE_RETRY_DELAY_MS = 3000L;

    public GenerationRecord createPendingRecord(Integer keyId, String prompt,
                                                String generationType, String sourceImagePath) {
        try {
            return runDbOperationWithRetry(() -> {
                GenerationRecord saved = transactionTemplate.execute(status -> {
                    if (!keyService.deductCredits(keyId, CREDITS_PER_GENERATION)) {
                        throw new RuntimeException("积分扣除失败");
                    }
                    GenerationRecord pending = new GenerationRecord();
                    pending.setKeyId(keyId);
                    pending.setTaskCode(taskCodeService.generate());
                    pending.setPrompt(prompt);
                    pending.setGenerationType(generationType);
                    pending.setSourceImagePath(sourceImagePath);
                    pending.setCreditsCost(CREDITS_PER_GENERATION);
                    pending.setStatus("processing");
                    return recordRepository.save(pending);
                });
                if (saved == null) {
                    throw new RuntimeException("创建生成记录失败");
                }
                return saved;
            });
        } catch (RuntimeException e) {
            throw new RuntimeException("积分扣除失败，请稍后重试", e);
        }
    }

    public void markCompleted(Integer recordId, String imagePath) {
        runDbOperationWithRetry(() -> {
            GenerationRecord saved = transactionTemplate.execute(status -> {
                GenerationRecord record = recordRepository.findById(recordId)
                        .orElseThrow(() -> new RuntimeException("记录不存在"));
                record.setImagePath(imagePath);
                record.setLocalPath(null);
                record.setStatus("completed");
                record.setErrorMessage(null);
                return recordRepository.save(record);
            });
            if (saved == null) {
                throw new RuntimeException("更新生成记录失败");
            }
            return saved;
        });
    }

    public void markFailed(Integer recordId, Integer keyId, String errorMessage) {
        try {
            runDbOperationWithRetry(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    recordRepository.findById(recordId).ifPresent(record -> {
                        record.setStatus("failed");
                        record.setErrorMessage(truncateError(errorMessage));
                        recordRepository.save(record);
                    });
                    keyService.refundCredits(keyId, CREDITS_PER_GENERATION);
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Failed to mark record {} as failed and refund key {}", recordId, keyId, e);
        }
    }

    private <T> T runDbOperationWithRetry(Callable<T> action) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= DB_SAVE_MAX_ATTEMPTS; attempt++) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt == DB_SAVE_MAX_ATTEMPTS || !isDatabaseConnectionError(e)) {
                    throw e;
                }
                log.warn("Database connection failed, retry {}/{}", attempt, DB_SAVE_MAX_ATTEMPTS, e);
                sleepBeforeDbRetry();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        throw lastError;
    }

    private void sleepBeforeDbRetry() {
        try {
            Thread.sleep(DB_SAVE_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("数据库操作被中断", e);
        }
    }

    private boolean isDatabaseConnectionError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CannotCreateTransactionException) {
                return true;
            }
            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if ("08006".equals(sqlState) || "08003".equals(sqlState) || "57P01".equals(sqlState)) {
                    return true;
                }
            }
            if (current instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (current instanceof DataAccessException || current instanceof TransactionException) {
                String message = current.getMessage();
                if (message != null && (message.contains("I/O error")
                        || message.contains("SQLState: 08006")
                        || message.contains("Connection")
                        || message.contains("connection")
                        || message.contains("backend")
                        || message.contains("rollback")
                        || message.contains("closed")
                        || message.contains("reset"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncateError(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
