package com.example.chatimg2.service;

import com.example.chatimg2.entity.ActivationKey;
import com.example.chatimg2.entity.GenerationRecord;
import com.example.chatimg2.entity.UserAccount;
import com.example.chatimg2.repository.ActivationKeyRepository;
import com.example.chatimg2.repository.GenerationRecordRepository;
import com.example.chatimg2.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationRecordServiceTest {

    @Mock
    private GenerationRecordRepository recordRepository;
    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private ActivationKeyRepository keyRepository;
    @Mock
    private KeyService keyService;
    @Mock
    private TaskCodeService taskCodeService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private GenerationRecordService service;

    @BeforeEach
    void setUp() {
        service = new GenerationRecordService(
                recordRepository, userRepository, keyRepository, keyService, taskCodeService, transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void duplicateClientMessageDoesNotChargeOrCreateAgain() {
        ActivationKey key = key(1, 100, 20);
        GenerationRecord existing = record(11, GenerationState.PROCESSING);
        when(keyRepository.findByIdForUpdate(1)).thenReturn(Optional.of(key));
        when(recordRepository.findFirstByKeyIdAndClientMessageIdOrderByCreatedAtDesc(1, "message-1"))
                .thenReturn(Optional.of(existing));

        GenerationRecordService.PendingRecord result = service.createPendingRecord(
                1, "prompt", "text2img", null, "message-1");

        assertThat(result.created()).isFalse();
        assertThat(result.record()).isSameAs(existing);
        assertThat(key.getUsedCredits()).isEqualTo(20);
        verify(keyRepository, never()).save(any());
        verify(recordRepository, never()).save(any());
    }

    @Test
    void newRequestChargesAndCreatesInOneTransaction() {
        ActivationKey key = key(1, 100, 20);
        when(keyRepository.findByIdForUpdate(1)).thenReturn(Optional.of(key));
        when(recordRepository.findFirstByKeyIdAndClientMessageIdOrderByCreatedAtDesc(1, "message-2"))
                .thenReturn(Optional.empty());
        when(taskCodeService.generate()).thenReturn("TASK-TEST-1");
        when(recordRepository.save(any(GenerationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerationRecordService.PendingRecord result = service.createPendingRecord(
                1, "prompt", "text2img", null, "message-2");

        assertThat(result.created()).isTrue();
        assertThat(result.record().getTaskCode()).isEqualTo("TASK-TEST-1");
        assertThat(key.getUsedCredits()).isEqualTo(40);
        verify(keyRepository).save(key);
        verify(recordRepository).save(any(GenerationRecord.class));
    }

    @Test
    void repeatedFailureRefundsUserOnlyOnce() {
        GenerationRecord record = record(12, GenerationState.PROCESSING);
        record.setUserId(7);
        record.setCreditsCost(20);
        UserAccount user = new UserAccount();
        user.setId(7);
        user.setTotalCredits(100);
        user.setUsedCredits(40);
        when(recordRepository.findByIdForUpdate(12)).thenReturn(Optional.of(record));
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(user));

        assertThat(service.markFailedForUser(12, 7, "failed")).isTrue();
        assertThat(service.markFailedForUser(12, 7, "failed again")).isFalse();

        assertThat(record.getStatus()).isEqualTo(GenerationState.FAILED);
        assertThat(user.getUsedCredits()).isEqualTo(20);
        verify(userRepository, times(1)).save(user);
        verify(keyService, times(1)).syncUserUsageToBoundKeys(user);
    }

    @Test
    void failedRecordCannotBeRevivedByLateImage() {
        GenerationRecord record = record(13, GenerationState.FAILED);
        when(recordRepository.findByIdForUpdate(13)).thenReturn(Optional.of(record));

        assertThat(service.markRemoteImageAvailable(13, "https://cdn.example/result.png")).isFalse();
        assertThat(service.markLocalImageAvailable(13, "user/TASK-13.png")).isFalse();

        assertThat(record.getStatus()).isEqualTo(GenerationState.FAILED);
        assertThat(record.getImagePath()).isNull();
        assertThat(record.getRemoteImageUrl()).isNull();
        verify(recordRepository, never()).save(any());
    }

    @Test
    void interruptedRecordIsRestoredWhenLateImageWasSaved() {
        GenerationRecord record = record(15, GenerationState.FAILED);
        record.setUserId(7);
        record.setCreditsCost(20);
        record.setErrorMessage(GenerationState.INTERRUPTED_MESSAGE);
        UserAccount user = new UserAccount();
        user.setId(7);
        user.setTotalCredits(100);
        user.setUsedCredits(20);
        when(recordRepository.findByIdForUpdate(15)).thenReturn(Optional.of(record));
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(user));

        assertThat(service.markLocalImageAvailable(15, "user/TASK-15.png")).isTrue();

        assertThat(record.getStatus()).isEqualTo(GenerationState.COMPLETED);
        assertThat(record.getImagePath()).isEqualTo("user/TASK-15.png");
        assertThat(record.getErrorMessage()).isNull();
        assertThat(user.getUsedCredits()).isEqualTo(40);
        verify(keyService).syncUserUsageToBoundKeys(user);
    }

    @Test
    void interruptedRecordIsRestoredWhenLateRemoteResultArrives() {
        GenerationRecord record = record(16, GenerationState.FAILED);
        record.setKeyId(3);
        record.setCreditsCost(20);
        record.setErrorMessage(GenerationState.INTERRUPTED_MESSAGE);
        ActivationKey key = key(3, 100, 0);
        when(recordRepository.findByIdForUpdate(16)).thenReturn(Optional.of(record));
        when(keyRepository.findByIdForUpdate(3)).thenReturn(Optional.of(key));

        assertThat(service.markRemoteImageAvailable(16, "https://cdn.example/late.png")).isTrue();

        assertThat(record.getStatus()).isEqualTo(GenerationState.COMPLETED);
        assertThat(record.getRemoteImageUrl()).isEqualTo("https://cdn.example/late.png");
        assertThat(record.getErrorMessage()).isNull();
        assertThat(key.getUsedCredits()).isEqualTo(20);
    }

    @Test
    void remoteCompletedRecordCanUpgradeToLocalDelivery() {
        GenerationRecord record = record(14, GenerationState.PROCESSING);
        when(recordRepository.findByIdForUpdate(14)).thenReturn(Optional.of(record));

        assertThat(service.markRemoteImageAvailable(14, "https://cdn.example/result.png")).isTrue();
        assertThat(service.markLocalImageAvailable(14, "user/TASK-14.png")).isTrue();

        assertThat(record.getStatus()).isEqualTo(GenerationState.COMPLETED);
        assertThat(record.getImagePath()).isEqualTo("user/TASK-14.png");
        assertThat(record.getRemoteImageUrl()).isNull();
        verify(recordRepository, times(2)).save(record);
    }

    private ActivationKey key(int id, int total, int used) {
        ActivationKey key = new ActivationKey();
        key.setId(id);
        key.setStatus((short) 1);
        key.setTotalCredits(total);
        key.setUsedCredits(used);
        return key;
    }

    private GenerationRecord record(int id, String status) {
        GenerationRecord record = new GenerationRecord();
        record.setId(id);
        record.setStatus(status);
        return record;
    }
}
