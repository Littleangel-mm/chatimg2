package com.example.chatimg2.service;

import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.GenerationRecordRepository;
import com.example.chatimg2.repository.UserAccountRepository;
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
    private final UserAccountRepository userRepository;
    private final KeyService keyService;
    private final TaskCodeService taskCodeService;
    private final TransactionTemplate transactionTemplate;

    private static final int CREDITS_PER_GENERATION = 20;
    private static final int DB_SAVE_MAX_ATTEMPTS = 6;
    private static final long DB_SAVE_RETRY_DELAY_MS = 3000L;

    public com.example.chatimg2.entity.GenerationRecord createPendingRecord(Integer keyId, String prompt,
                                                                            String generationType, String sourceImagePath) {
        try {
            return runDbOperationWithRetry(() -> {
                com.example.chatimg2.entity.GenerationRecord saved = transactionTemplate.execute(status -> {
                    if (!keyService.deductCredits(keyId, CREDITS_PER_GENERATION)) {
                        throw new RuntimeException("积分扣除失败");
                    }
                    com.example.chatimg2.entity.GenerationRecord pending = new com.example.chatimg2.entity.GenerationRecord();
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

    public com.example.chatimg2.entity.GenerationRecord createPendingRecordForUser(UserAccount user, String prompt,
                                                                                    String generationType, String sourceImagePath) {
        try {
            return runDbOperationWithRetry(() -> {
                com.example.chatimg2.entity.GenerationRecord saved = transactionTemplate.execute(status -> {
                    UserAccount lockedUser = userRepository.findById(user.getId())
                            .orElseThrow(() -> new RuntimeException("用户不存在"));
                    if (lockedUser.getRemainingCredits() < CREDITS_PER_GENERATION) {
                        throw new RuntimeException("积分不足，剩余 " + lockedUser.getRemainingCredits());
                    }
                    lockedUser.setUsedCredits(lockedUser.getUsedCredits() + CREDITS_PER_GENERATION);
                    userRepository.save(lockedUser);
                    Integer chargedKeyId = keyService.syncUserUsageToBoundKeys(lockedUser)
                            .orElse(null);

                    com.example.chatimg2.entity.GenerationRecord pending = new com.example.chatimg2.entity.GenerationRecord();
                    pending.setKeyId(chargedKeyId);
                    pending.setUserId(lockedUser.getId());
                    pending.setUserEmail(lockedUser.getEmail());
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
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void markCompleted(Integer recordId, String imagePath) {
        runDbOperationWithRetry(() -> {
            com.example.chatimg2.entity.GenerationRecord saved = transactionTemplate.execute(status -> {
                com.example.chatimg2.entity.GenerationRecord record = recordRepository.findById(recordId)
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

    public void markFailedForUser(Integer recordId, Integer userId, String errorMessage) {
        try {
            runDbOperationWithRetry(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    recordRepository.findById(recordId).ifPresent(record -> {
                        record.setStatus("failed");
                        record.setErrorMessage(truncateError(errorMessage));
                        recordRepository.save(record);
                    });
                    userRepository.findById(userId).ifPresent(user -> {
                        user.setUsedCredits(Math.max(0, user.getUsedCredits() - CREDITS_PER_GENERATION));
                        userRepository.save(user);
                        keyService.syncUserUsageToBoundKeys(user);
                    });
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Failed to mark record {} as failed and refund user {}", recordId, userId, e);
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
