package com.example.chatimg2.service;

import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.exception.BusinessException;
import com.example.chatimg2.repository.ActivationKeyRepository;
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

    public static final int CREDITS_PER_GENERATION = 20;

    private static final int DB_SAVE_MAX_ATTEMPTS = 6;
    private static final long DB_SAVE_RETRY_DELAY_MS = 3000L;

    private final GenerationRecordRepository recordRepository;
    private final UserAccountRepository userRepository;
    private final ActivationKeyRepository keyRepository;
    private final KeyService keyService;
    private final TaskCodeService taskCodeService;
    private final TransactionTemplate transactionTemplate;

    public record PendingRecord(GenerationRecord record, boolean created) {
    }

    public PendingRecord createPendingRecord(Integer keyId, String prompt, String generationType,
                                             String sourceImagePath, String clientMessageId) {
        String normalizedMessageId = normalizeClientMessageId(clientMessageId);
        return runDbOperationWithRetry(() -> requireTransactionResult(transactionTemplate.execute(status -> {
            ActivationKey key = keyRepository.findByIdForUpdate(keyId)
                    .orElseThrow(() -> new BusinessException("KEY_NOT_FOUND", "密钥不存在"));
            GenerationRecord existing = findExistingForKey(keyId, normalizedMessageId);
            if (existing != null) {
                return new PendingRecord(existing, false);
            }
            if (key.getStatus() == null || key.getStatus() != 1) {
                throw new BusinessException("KEY_INVALID", "密钥无效或已禁用");
            }
            if (key.getRemainingCredits() < CREDITS_PER_GENERATION) {
                throw new BusinessException("INSUFFICIENT_CREDITS", "积分不足，剩余 " + key.getRemainingCredits());
            }
            key.setUsedCredits(key.getUsedCredits() + CREDITS_PER_GENERATION);
            keyRepository.save(key);
            GenerationRecord record = recordRepository.save(newPendingRecord(
                    keyId, null, null, prompt, generationType, sourceImagePath, normalizedMessageId));
            return new PendingRecord(record, true);
        }), "创建生成记录失败"));
    }

    public PendingRecord createPendingRecordForUser(UserAccount user, String prompt, String generationType,
                                                    String sourceImagePath, String clientMessageId) {
        String normalizedMessageId = normalizeClientMessageId(clientMessageId);
        return runDbOperationWithRetry(() -> requireTransactionResult(transactionTemplate.execute(status -> {
            UserAccount lockedUser = userRepository.findByIdForUpdate(user.getId())
                    .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在"));
            GenerationRecord existing = findExistingForUser(lockedUser.getId(), normalizedMessageId);
            if (existing != null) {
                return new PendingRecord(existing, false);
            }
            if (lockedUser.getRemainingCredits() < CREDITS_PER_GENERATION) {
                throw new BusinessException(
                        "INSUFFICIENT_CREDITS", "积分不足，剩余 " + lockedUser.getRemainingCredits());
            }
            lockedUser.setUsedCredits(lockedUser.getUsedCredits() + CREDITS_PER_GENERATION);
            userRepository.save(lockedUser);
            Integer chargedKeyId = keyService.syncUserUsageToBoundKeys(lockedUser).orElse(null);
            GenerationRecord record = recordRepository.save(newPendingRecord(
                    chargedKeyId,
                    lockedUser.getId(),
                    lockedUser.getEmail(),
                    prompt,
                    generationType,
                    sourceImagePath,
                    normalizedMessageId));
            return new PendingRecord(record, true);
        }), "创建生成记录失败"));
    }

    public boolean markLocalImageAvailable(Integer recordId, String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            throw new IllegalArgumentException("Local image path must not be blank");
        }
        return runDbOperationWithRetry(() -> requireTransactionResult(transactionTemplate.execute(status -> {
            GenerationRecord record = findRecordForUpdate(recordId);
            if (GenerationState.isFailed(record.getStatus())) {
                if (GenerationState.isInterruptedFailure(record.getErrorMessage())
                        && chargeRecordCredits(record)) {
                    record.setImagePath(imagePath);
                    record.setRemoteImageUrl(null);
                    record.setStatus(GenerationState.COMPLETED);
                    record.setErrorMessage(null);
                    recordRepository.save(record);
                    log.info("Restored late image result after interrupted recovery: recordId={}", recordId);
                    return true;
                }
                log.warn("Ignore local image for failed generation: recordId={}", recordId);
                return false;
            }
            if (imagePath.equals(record.getImagePath())
                    && GenerationState.isCompleted(record.getStatus())
                    && record.getRemoteImageUrl() == null) {
                return true;
            }
            record.setImagePath(imagePath);
            record.setRemoteImageUrl(null);
            record.setStatus(GenerationState.COMPLETED);
            record.setErrorMessage(null);
            recordRepository.save(record);
            return true;
        }), "更新生成记录失败"));
    }

    public boolean markRemoteImageAvailable(Integer recordId, String remoteImageUrl) {
        if (remoteImageUrl == null || remoteImageUrl.isBlank()) {
            throw new IllegalArgumentException("Remote image URL must not be blank");
        }
        return runDbOperationWithRetry(() -> requireTransactionResult(transactionTemplate.execute(status -> {
            GenerationRecord record = findRecordForUpdate(recordId);
            if (GenerationState.isFailed(record.getStatus())) {
                if (GenerationState.isInterruptedFailure(record.getErrorMessage())
                        && chargeRecordCredits(record)) {
                    record.setRemoteImageUrl(remoteImageUrl);
                    record.setStatus(GenerationState.COMPLETED);
                    record.setErrorMessage(null);
                    recordRepository.save(record);
                    log.info("Restored late remote result after interrupted recovery: recordId={}", recordId);
                    return true;
                }
                log.warn("Ignore remote image for failed generation: recordId={}", recordId);
                return false;
            }
            if (record.getImagePath() != null && !record.getImagePath().isBlank()) {
                return true;
            }
            record.setRemoteImageUrl(remoteImageUrl);
            record.setStatus(GenerationState.COMPLETED);
            record.setErrorMessage(null);
            recordRepository.save(record);
            return true;
        }), "保存远程图片结果失败"));
    }

    public boolean markFailed(Integer recordId, Integer fallbackKeyId, String errorMessage) {
        return markFailed(recordId, fallbackKeyId, null, errorMessage);
    }

    public boolean markFailedForUser(Integer recordId, Integer fallbackUserId, String errorMessage) {
        return markFailed(recordId, null, fallbackUserId, errorMessage);
    }

    private boolean markFailed(Integer recordId, Integer fallbackKeyId, Integer fallbackUserId,
                               String errorMessage) {
        return runDbOperationWithRetry(() -> requireTransactionResult(transactionTemplate.execute(status -> {
            GenerationRecord record = findRecordForUpdate(recordId);
            if (!GenerationState.isProcessing(record.getStatus())) {
                return false;
            }
            record.setStatus(GenerationState.FAILED);
            record.setErrorMessage(truncateError(errorMessage));
            recordRepository.save(record);

            Integer userId = record.getUserId() != null ? record.getUserId() : fallbackUserId;
            if (userId != null) {
                refundUserCredits(userId, resolveCreditCost(record));
            } else {
                Integer keyId = record.getKeyId() != null ? record.getKeyId() : fallbackKeyId;
                refundKeyCredits(keyId, resolveCreditCost(record));
            }
            return true;
        }), "更新失败状态失败"));
    }

    private GenerationRecord newPendingRecord(Integer keyId, Integer userId, String userEmail,
                                              String prompt, String generationType, String sourceImagePath,
                                              String clientMessageId) {
        GenerationRecord record = new GenerationRecord();
        record.setKeyId(keyId);
        record.setUserId(userId);
        record.setUserEmail(userEmail);
        record.setTaskCode(taskCodeService.generate());
        record.setPrompt(prompt);
        record.setGenerationType(generationType);
        record.setSourceImagePath(sourceImagePath);
        record.setClientMessageId(clientMessageId);
        record.setCreditsCost(CREDITS_PER_GENERATION);
        record.setStatus(GenerationState.PROCESSING);
        return record;
    }

    private GenerationRecord findRecordForUpdate(Integer recordId) {
        return recordRepository.findByIdForUpdate(recordId)
                .orElseThrow(() -> new BusinessException("RECORD_NOT_FOUND", "记录不存在"));
    }

    private GenerationRecord findExistingForUser(Integer userId, String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        return recordRepository.findFirstByUserIdAndClientMessageIdOrderByCreatedAtDesc(userId, clientMessageId)
                .orElse(null);
    }

    private GenerationRecord findExistingForKey(Integer keyId, String clientMessageId) {
        if (clientMessageId == null) {
            return null;
        }
        return recordRepository.findFirstByKeyIdAndClientMessageIdOrderByCreatedAtDesc(keyId, clientMessageId)
                .orElse(null);
    }

    private void refundUserCredits(Integer userId, int cost) {
        userRepository.findByIdForUpdate(userId).ifPresentOrElse(user -> {
            user.setUsedCredits(Math.max(0, user.getUsedCredits() - cost));
            userRepository.save(user);
            keyService.syncUserUsageToBoundKeys(user);
        }, () -> log.error("Cannot refund generation credits because user is missing: userId={}", userId));
    }

    private void refundKeyCredits(Integer keyId, int cost) {
        if (keyId == null) {
            log.error("Cannot refund generation credits because keyId is missing");
            return;
        }
        keyRepository.findByIdForUpdate(keyId).ifPresentOrElse(key -> {
            key.setUsedCredits(Math.max(0, key.getUsedCredits() - cost));
            keyRepository.save(key);
        }, () -> log.error("Cannot refund generation credits because key is missing: keyId={}", keyId));
    }

    private boolean chargeRecordCredits(GenerationRecord record) {
        int cost = resolveCreditCost(record);
        if (record.getUserId() != null) {
            UserAccount user = userRepository.findByIdForUpdate(record.getUserId()).orElse(null);
            if (user == null) {
                log.error("Cannot restore generation charge because user is missing: recordId={}, userId={}",
                        record.getId(), record.getUserId());
                return false;
            }
            user.setUsedCredits(user.getUsedCredits() + cost);
            userRepository.save(user);
            keyService.syncUserUsageToBoundKeys(user);
            return true;
        }
        if (record.getKeyId() == null) {
            log.error("Cannot restore generation charge because keyId is missing: recordId={}", record.getId());
            return false;
        }
        ActivationKey key = keyRepository.findByIdForUpdate(record.getKeyId()).orElse(null);
        if (key == null) {
            log.error("Cannot restore generation charge because key is missing: recordId={}, keyId={}",
                    record.getId(), record.getKeyId());
            return false;
        }
        key.setUsedCredits(key.getUsedCredits() + cost);
        keyRepository.save(key);
        return true;
    }

    private int resolveCreditCost(GenerationRecord record) {
        return record.getCreditsCost() != null && record.getCreditsCost() > 0
                ? record.getCreditsCost()
                : CREDITS_PER_GENERATION;
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
                log.warn("Database operation failed transiently, retry {}/{}: {}",
                        attempt, DB_SAVE_MAX_ATTEMPTS, e.getMessage());
                sleepBeforeDbRetry();
            } catch (Exception e) {
                throw new IllegalStateException("Database operation failed", e);
            }
        }
        throw lastError;
    }

    private void sleepBeforeDbRetry() {
        try {
            Thread.sleep(DB_SAVE_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("数据库操作被中断", e);
        }
    }

    private boolean isDatabaseConnectionError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CannotCreateTransactionException
                    || current instanceof DataAccessResourceFailureException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && (sqlState.startsWith("08") || "57P01".equals(sqlState))) {
                    return true;
                }
            }
            if (current instanceof DataAccessException || current instanceof TransactionException) {
                String message = current.getMessage();
                if (message != null && (message.contains("I/O error")
                        || message.toLowerCase().contains("connection")
                        || message.toLowerCase().contains("rollback")
                        || message.toLowerCase().contains("closed")
                        || message.toLowerCase().contains("reset"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String normalizeClientMessageId(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        String value = clientMessageId.trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private <T> T requireTransactionResult(T result, String message) {
        if (result == null) {
            throw new IllegalStateException(message);
        }
        return result;
    }
}
